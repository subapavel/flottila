package dev.rail8.components.http;

import dev.rail8.api.Ack;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Fault;
import dev.rail8.api.NackException;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-049: a failed handler future is answered by the failure's {@link Fault.Kind}; anything that is no fault is a 500 — a
 * checked exception a handler throws (sneakily, as an interrupted {@code await} does) included (D-056).
 */
@Timeout(30)
class HttpConsumerStatusTest {

    static Vertx vertx;
    static HttpClientAgent raw;
    static SharedHttpPort port;
    final FakeRuntime rt = FakeRuntime.create();
    final HttpComponent http = new HttpComponent(vertx, rt.blobs(), rt.ids(), rt.timer(), port);
    HttpComponent.HttpConsumer consumer;

    /** A failure that says what kind it is and nothing else. */
    static final class TestFault extends RuntimeException implements Fault {
        private final Kind kind;
        TestFault(Kind kind, String message) { super(message); this.kind = kind; }
        @Override public Kind kind() { return kind; }
    }

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
        raw = vertx.createHttpClient();
        port = SharedHttpPort.loopback(vertx, Map.of("http", HttpComponent.PREFIX)).await();
    }

    @AfterAll
    static void stop() {
        raw.close().await();
        port.close().await();
        vertx.close().await();
    }

    @AfterEach
    void down() {
        if (consumer != null) consumer.stop().await();
    }

    record Answer(int status, MultiMap headers, String body) {}

    Answer post(String failureCase) {
        return raw.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(consumer.url().orElseThrow().toString()))
                .compose(req -> { req.putHeader("x-case", failureCase); return req.send(Buffer.buffer("<x/>")); })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b.toString()))).await();
    }

    @Test
    void aFaultFromTheHandlerIsAnsweredByItsKind() {
        Map<String, RuntimeException> failures = new LinkedHashMap<>();
        failures.put("malformed", new TestFault(Fault.Kind.MALFORMED, "the body is not xml"));
        failures.put("invalid", new TestFault(Fault.Kind.INVALID, "validation failed at step 'validate': no Id"));
        failures.put("downstream-final", new NackException("partner refused the order", false));
        failures.put("downstream-retryable", new NackException("partner is busy", true));
        failures.put("internal", new TestFault(Fault.Kind.INTERNAL, "stylesheet raised an error"));
        failures.put("no-fault", new IllegalStateException("boom"));
        failures.put("partner-5xx", new HttpStatusException(500, "partner exploded"));
        failures.put("partner-4xx", new HttpStatusException(404, "no such partner path"));
        EndpointConfig in = new EndpointConfig("http-in", URI.create("http://in"), Map.of("address", "/in", "flow", "orders", "maxBodyBytes", 1024));
        consumer = (HttpComponent.HttpConsumer) http.consumer(in, (Envelope e) -> Future.<Ack>failedFuture(failures.get(e.headers().get("x-case"))));
        consumer.start().await();

        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("malformed", 400);
        expected.put("invalid", 422);
        expected.put("downstream-final", 502);
        expected.put("downstream-retryable", 503);
        expected.put("internal", 500);
        expected.put("no-fault", 500);
        expected.put("partner-5xx", 503);
        expected.put("partner-4xx", 502);
        for (Map.Entry<String, Integer> c : expected.entrySet()) {
            Answer a = post(c.getKey());
            assertEquals(c.getValue(), a.status(), c.getKey() + ": " + a.body());
            assertEquals(failures.get(c.getKey()).getMessage(), a.body(), c.getKey() + ": the body is the failure's message, not its toString()");
            String contentType = a.headers().get("content-type");
            assertTrue(contentType != null && contentType.startsWith("text/plain"), c.getKey() + ": content-type " + contentType);
            if (c.getValue() == 503) assertEquals("1", a.headers().get("retry-after"), c.getKey() + ": a retryable answer says when to retry");
            else assertNull(a.headers().get("retry-after"), c.getKey() + ": only a retryable answer carries retry-after");
        }
    }

    /** Throws a checked {@code failure} from code that declares none, as Vert.x {@code Future.await()} does with an interrupt. */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable failure) throws T {
        throw (T) failure;
    }

    @Test
    void aHandlerThrowingACheckedExceptionIsAnswered500() throws Exception {
        Map<String, Exception> thrown = new LinkedHashMap<>();
        thrown.put("io", new IOException("disk gone"));
        thrown.put("interrupted", new InterruptedException("woken by the stop deadline"));
        EndpointConfig in = new EndpointConfig("http-in", URI.create("http://in"), Map.of("address", "/in", "flow", "orders", "maxBodyBytes", 1024));
        consumer = (HttpComponent.HttpConsumer) http.consumer(in, (Envelope e) -> { throw sneaky(thrown.get(e.headers().get("x-case"))); });
        consumer.start().await();
        for (Map.Entry<String, Exception> c : thrown.entrySet()) {
            Future<Answer> answered = raw.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(consumer.url().orElseThrow().toString()).setTimeout(5_000))
                    .compose(req -> { req.putHeader("x-case", c.getKey()); return req.send(Buffer.buffer("<x/>")); })
                    .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b.toString())));
            try {
                answered.otherwiseEmpty().await(10, TimeUnit.SECONDS);
            } catch (TimeoutException pending) {
                fail(c.getKey() + ": no answer within 10 s");
            }
            assertTrue(answered.succeeded(), c.getKey() + ": a handler that throws a checked exception is answered, not left hanging: " + answered.cause());
            Answer a = answered.result();
            assertEquals(500, a.status(), c.getKey() + ": " + a.body());
            assertEquals(c.getValue().getMessage(), a.body(), c.getKey() + ": the body is the failure's message");
            String contentType = a.headers().get("content-type");
            assertTrue(contentType != null && contentType.startsWith("text/plain"), c.getKey() + ": content-type " + contentType);
            assertNull(a.headers().get("retry-after"), c.getKey() + ": a 500 carries no retry-after");
        }
    }
}
