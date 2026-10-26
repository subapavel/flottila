package io.flottila.components.http;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.util.HashMap;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-056, the http consumer stopped mid-delivery: {@code stop()} reports health DOWN at once, answers a request whose body
 * completes after the stop began with 503 + {@code retry-after} without calling the handler, lets the deliveries in flight
 * answer, and closes through {@code HttpServer.shutdown} with the endpoint option {@code shutdownTimeoutMs} (finite,
 * positive, default 30 000) as the transport's backstop. The contract cases (the stop waits, a cancel is a retryable NACK)
 * run in {@link HttpComponentContractTest}.
 */
@Timeout(30)
class HttpConsumerStopTest {

    /** How long a case waits for an answer or a stop before it fails (a bound, never a timing). */
    static final long BOUND_SECONDS = 10;

    static Vertx vertx;
    static HttpClientAgent raw;
    final FakeRuntime rt = FakeRuntime.create();
    final GatedBlobs blobs = new GatedBlobs(rt.blobs());
    final HttpComponent http = new HttpComponent(vertx, blobs, rt.ids());
    HttpComponent.HttpConsumer consumer;

    /**
     * The runtime's blob store with one twist: after {@link #holdNextWrite()} the next streaming write stores its bytes as
     * usual but its future completes only when the returned gate does — a request body that completes when the test says.
     */
    static final class GatedBlobs implements BlobStore {
        private final BlobStore delegate;
        private volatile Promise<Void> gate;
        final CountDownLatch gatedWriteBegan = new CountDownLatch(1);

        GatedBlobs(BlobStore delegate) { this.delegate = delegate; }

        Promise<Void> holdNextWrite() {
            Promise<Void> g = Promise.promise();
            gate = g;
            return g;
        }

        @Override public long treeLimit() { return delegate.treeLimit(); }
        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) { return delegate.write(key, whole, maxSize); }
        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            Promise<Void> g = gate;   // taken before the write is signalled as begun
            gate = null;
            Future<Body> written = delegate.write(key, in, maxSize);
            if (g == null) return written;
            gatedWriteBegan.countDown();
            return written.compose(body -> g.future().map(body));
        }
        @Override public ReadStream<Buffer> open(BlobKey key) { return delegate.open(key); }
        @Override public Future<Buffer> read(BlobKey key) { return delegate.read(key); }
    }

    @BeforeAll
    static void start() {
        vertx = Vertx.vertx();
        raw = vertx.createHttpClient();
    }

    @AfterAll
    static void stop() {
        raw.close().await();
        vertx.close().await();
    }

    @AfterEach
    void down() throws Exception {
        if (consumer != null) consumer.stop().otherwiseEmpty().await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    static EndpointConfig endpoint(Map<String, Object> extra) {
        Map<String, Object> options = new HashMap<>(Map.of("host", "127.0.0.1", "port", 0, "path", "/in", "flow", "orders", "maxBodyBytes", 1024));
        options.putAll(extra);
        return new EndpointConfig("http-in", URI.create("http://127.0.0.1:0/in"), options);
    }

    HttpComponent.HttpConsumer listen(Map<String, Object> extra, Function<Envelope, Future<Ack>> handler) {
        consumer = (HttpComponent.HttpConsumer) http.consumer(endpoint(extra), handler);
        consumer.start().await();   // port 0: the port the server got (FIX-8)
        return consumer;
    }

    record Answer(int status, MultiMap headers, String body) {}

    Future<Answer> send(String body) {
        return raw.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + consumer.port() + "/in"))
                .compose(req -> req.send(Buffer.buffer(body)))
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.headers(), b.toString())));
    }

    /** {@code f} once it has completed, either way; still pending after {@link #BOUND_SECONDS} fails the case. */
    static <T> Future<T> within(Future<T> f) {
        try {
            f.otherwiseEmpty().await(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after " + BOUND_SECONDS + " s: " + f);
        }
        return f;
    }

    static void reached(CountDownLatch latch, String what) throws InterruptedException {
        assertTrue(latch.await(BOUND_SECONDS, TimeUnit.SECONDS), what + " within " + BOUND_SECONDS + " s");
    }

    @Test
    void aBodyCompletingAfterStopBeganIs503AndTheHandlerIsNotCalled() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        listen(Map.of(), e -> { handled.incrementAndGet(); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        Answer before = within(send("<before/>")).result();
        assertEquals(202, before.status(), "a body completing while the consumer runs goes to the handler: " + before.body());
        assertEquals(1, handled.get());

        Promise<Void> bodyComplete = blobs.holdNextWrite();
        Future<Answer> late = send("<late/>");
        reached(blobs.gatedWriteBegan, "the request reached the consumer and its body is being stored");
        Future<Void> stopped = consumer.stop();
        bodyComplete.complete();   // the body completes now: after the stop began

        assertTrue(within(late).succeeded(), "the late request is answered, its connection not dropped: " + late.cause());
        Answer a = late.result();
        assertEquals(503, a.status(), "a body completing after stop began is refused: " + a.body());
        assertEquals("1", a.headers().get("retry-after"), "a retryable refusal says when to retry");
        assertEquals(1, handled.get(), "the handler is never called for a body that completed after stop began");
        assertTrue(within(stopped).succeeded(), "stop completes once the late request was answered: " + stopped.cause());
    }

    @Test
    void healthIsDownOnceStopBegins() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();
        Promise<Ack> answer = Promise.promise();
        listen(Map.of(), e -> {
            invoked.countDown();
            return handled.incrementAndGet() == 1 ? answer.future() : Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        assertEquals(Health.Status.UP, http.health().await().status(), "a consumer listens");
        Future<Answer> inFlight = send("<in-flight/>");
        reached(invoked, "the handler was invoked");

        Future<Void> stopped = consumer.stop();
        assertEquals(Health.Status.DOWN, http.health().await().status(), "DOWN as soon as the stop began, with a delivery still in flight");
        Future<Answer> late = within(send("<late/>"));   // by the time a new request is refused the stop has acted
        assertTrue(late.failed() || late.result().status() == 503, "a new request after stop began is refused: " + late);
        assertEquals(1, handled.get(), "and never reaches the handler");
        assertFalse(inFlight.isComplete(), "the delivery in flight still waits for its answer: " + inFlight);
        assertFalse(stopped.isComplete(), "the stop is under way: a delivery is in flight");
        answer.complete(new Ack.Accepted("m-in-flight"));
        assertTrue(within(inFlight).succeeded(), "the delivery in flight is answered after health went DOWN: " + inFlight.cause());
        assertEquals(202, inFlight.result().status(), inFlight.result().body());
        assertTrue(within(stopped).succeeded(), "stop completes once the delivery in flight was answered: " + stopped.cause());
        assertEquals(Health.Status.DOWN, http.health().await().status(), "and stays DOWN");
    }

    @Test
    void shutdownTimeoutMsMustBePositive() {
        Function<Envelope, Future<Ack>> handler = e -> Future.succeededFuture(new Ack.Accepted(e.id()));
        for (Object bad : List.<Object>of(0, -1, -30_000L)) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> http.consumer(endpoint(Map.of("shutdownTimeoutMs", bad)), handler),
                    "shutdownTimeoutMs " + bad + " is refused: the transport backstop is finite and positive");
            assertTrue(refused.getMessage().contains("shutdownTimeoutMs"), "names the option: " + refused.getMessage());
        }
        HttpComponent.HttpConsumer byDefault = (HttpComponent.HttpConsumer) http.consumer(endpoint(Map.of()), handler);
        assertEquals(Duration.ofSeconds(30), byDefault.shutdownTimeout(), "absent: the 30 000 ms default");
        HttpComponent.HttpConsumer oneMilli = (HttpComponent.HttpConsumer) http.consumer(endpoint(Map.of("shutdownTimeoutMs", 1)), handler);
        assertEquals(Duration.ofMillis(1), oneMilli.shutdownTimeout(), "one millisecond is positive, read as milliseconds");
        JsonObject option = new JsonObject(http.configSchema()).getJsonObject("properties").getJsonObject("shutdownTimeoutMs");
        assertEquals("integer", option == null ? null : option.getString("type"), "the option is in the config schema: " + http.configSchema());
        assertEquals(1, option.getInteger("minimum"), "and the schema refuses a non-positive value too");
    }

    @Test
    void aStopWhoseDeliveryNeverAnswersEndsAtTheShutdownTimeout() throws Exception {
        CountDownLatch invoked = new CountDownLatch(1);
        Promise<Ack> never = Promise.promise();
        listen(Map.of("shutdownTimeoutMs", 200), e -> { invoked.countDown(); return never.future(); });
        Future<Answer> inFlight = send("<never-answered/>");
        reached(invoked, "the handler was invoked");

        Future<Void> stopped = consumer.stop();
        assertTrue(within(stopped).succeeded(), "the transport backstop ends the stop after shutdownTimeoutMs (200 ms), not after the 30 s default: " + stopped.cause());
        within(inFlight);   // the request is not left hanging once the server closed its connection, whatever it was told
        assertEquals(Health.Status.DOWN, http.health().await().status());
    }
}
