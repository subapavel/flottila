package dev.rail8.components.http;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Body;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.settings.Direction;
import dev.rail8.api.settings.Settings;
import dev.rail8.ingress.SharedHttpPort;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
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
 * The http consumer stopped mid-delivery: {@code stop()} reports health DOWN at once, answers a request whose body completes
 * after the stop began with 503 + {@code retry-after} without calling the handler, lets the deliveries in flight answer, and
 * closes its mount on the node's HTTP port, whose drain deadline is the backstop for a delivery that never answers. The
 * contract cases (the stop waits, a cancel is a retryable NACK) run in {@link HttpComponentContractTest}.
 */
@Timeout(30)
class HttpConsumerStopTest {

    /** How long a case waits for an answer or a stop before it fails (a bound, never a timing). */
    static final long BOUND_SECONDS = 10;

    static Vertx vertx;
    static HttpClientAgent raw;
    static SharedHttpPort port;
    final FakeRuntime rt = FakeRuntime.create();
    final GatedBlobs blobs = new GatedBlobs(rt.blobs());
    final HttpComponent http = new HttpComponent(vertx, blobs, rt.ids(), rt.timer(), port);
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
        @Override public Future<Void> persist(BlobKey key) { return delegate.persist(key); }
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
    void down() throws Exception {
        if (consumer != null) consumer.stop().otherwiseEmpty().await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    static EndpointConfig endpoint(Map<String, Object> extra) {
        Map<String, Object> options = new HashMap<>(Map.of("address", "/in", "flow", "orders", "maxBodyBytes", 1024));
        options.putAll(extra);
        return new EndpointConfig("http-in", URI.create("http://in"), options);
    }

    HttpComponent.HttpConsumer listen(Map<String, Object> extra, Function<Envelope, Future<Ack>> handler) {
        return listen(http, extra, handler);
    }

    HttpComponent.HttpConsumer listen(HttpComponent on, Map<String, Object> extra, Function<Envelope, Future<Ack>> handler) {
        consumer = (HttpComponent.HttpConsumer) on.consumer(endpoint(extra), handler);
        consumer.start().await();
        return consumer;
    }

    record Answer(int status, MultiMap headers, String body) {}

    Future<Answer> send(String body) {
        return sendTo(consumer.url().orElseThrow().toString(), body);
    }

    Future<Answer> sendTo(String url, String body) {
        return raw.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(url))
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
            int n = handled.incrementAndGet();   // the state the test reads after the latch, set before it opens
            invoked.countDown();
            return n == 1 ? answer.future() : Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        assertEquals(Health.Status.UP, http.health().await().status(), "a consumer listens");
        String inFlightUrl = consumer.url().orElseThrow().toString();
        Future<Answer> inFlight = send("<in-flight/>");
        reached(invoked, "the handler was invoked");

        Future<Void> stopped = consumer.stop();
        assertEquals(Health.Status.DOWN, http.health().await().status(), "DOWN as soon as the stop began, with a delivery still in flight");
        Answer late = within(sendTo(inFlightUrl, "<late/>")).result();
        assertEquals(404, late.status(), "a new request after stop began finds no mount (the node answers 503 while the flow is deployed): " + late.body());
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
    void shutdownTimeoutMsIsNoOptionAnyMore() {
        assertEquals(List.of(new Settings.Problem("/shutdownTimeoutMs", "no such setting")),
                http.settings().check(Map.of("address", "/in", "shutdownTimeoutMs", 5_000), Direction.IN),
                "the drain deadline is the node's stop deadline, the same for every endpoint on its HTTP port");
    }

    @Test
    void aStopWhoseDeliveryNeverAnswersEndsAtThePortsDrainDeadline() throws Exception {
        Duration drain = Duration.ofMillis(200);
        SharedHttpPort quick = new SharedHttpPort(vertx, rt.timer(), "127.0.0.1", 0, drain);
        quick.listen(Map.of("http", HttpComponent.PREFIX), path -> false).await();
        try {
            CountDownLatch invoked = new CountDownLatch(1);
            Promise<Ack> never = Promise.promise();
            HttpComponent onShort = new HttpComponent(vertx, blobs, rt.ids(), rt.timer(), quick);
            listen(onShort, Map.of(), e -> { invoked.countDown(); return never.future(); });
            Future<Answer> inFlight = send("<never-answered/>");
            reached(invoked, "the handler was invoked");

            Future<Void> stopped = consumer.stop();
            assertFalse(stopped.isComplete(), "the stop waits for the delivery in flight");
            rt.clock().advance(drain);
            assertTrue(within(stopped).succeeded(), "the port's drain deadline ends the stop: " + stopped.cause());
            assertTrue(within(inFlight).failed(), "the request still open at the deadline is reset, not left hanging: " + inFlight);
            assertEquals(Health.Status.DOWN, onShort.health().await().status());
        } finally {
            quick.close().await();
        }
    }
}
