package io.flottila.components.mock;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.IdGenerator;
import io.flottila.api.Key;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03b1 (D-080): {@code MockComponent.ingest(endpoint, headers, bytes)} — what {@code flottila test} sends a case with — builds
 * the ingress envelope as the endpoint's running consumer would (an id from the mock's generator, the flow and the
 * {@code maxBodyBytes} of the consumer's config, the body under {@code BlobKey.body(id, 0)}; on an http-scheme mock the props
 * {@code http.method} POST and {@code http.path} the endpoint's {@code path}) and delivers it through that consumer's handler,
 * answering what the handler answers. No running consumer on the endpoint: {@link IllegalStateException}; a body above the
 * consumer's {@code maxBodyBytes}: {@link BodyTooLargeException}, the handler never called. {@code delivered(endpoint)} is the
 * consumer-side record; an ingest is never recorded as sent.
 *
 * <p>Either failure may be thrown by the call or be the cause of the future it returns: {@link #failureOf} accepts both.
 */
class MockIngestTest {

    static final Key<String> METHOD = new Key<>("http.method");   // HttpComponent.METHOD / PATH: the mock depends on the api only
    static final Key<String> PATH = new Key<>("http.path");

    final FakeRuntime rt = FakeRuntime.create();
    final AtomicInteger minted = new AtomicInteger();
    final IdGenerator ids = () -> "in-" + minted.incrementAndGet();
    final MockComponent http = new MockComponent("http", rt.blobs(), rt.timer(), ids);
    /** As Deployments hands it to the consumer: {@code <scheme>://<id>}, the endpoint's config plus the flow and its maxBodyBytes. */
    final EndpointConfig in = new EndpointConfig("http-in", URI.create("http://http-in"),
            Map.of("host", "0.0.0.0", "port", 8080, "path", "/in", "flow", "orders", "maxBodyBytes", 8L));
    final List<Consumer> started = new ArrayList<>();
    final List<Promise<Ack>> holds = new ArrayList<>();

    @AfterEach
    void stopAll() {
        holds.forEach(h -> h.tryComplete(new Ack.Accepted("released")));
        started.forEach(c -> c.stop().await());
    }

    record Handled(List<Envelope> envelopes, List<Thread> threads) {}

    /** Starts a consumer of {@code mock} on {@code cfg} whose handler records what it gets and on which thread, and answers {@code answer}. */
    Handled consume(MockComponent mock, EndpointConfig cfg, Function<Envelope, Future<Ack>> answer) {
        Handled handled = new Handled(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        Consumer c = mock.consumer(cfg, e -> {
            handled.threads().add(Thread.currentThread());
            handled.envelopes().add(e);
            return answer.apply(e);
        });
        c.start().await();
        started.add(c);
        return handled;
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("not within 10 s: " + what);
            Thread.onSpinWait();
        }
    }

    /** What the call failed with: thrown by it, or the cause of the future it returned; an AssertionError when it did not fail. */
    static Throwable failureOf(Supplier<Future<Ack>> call) {
        Future<Ack> f;
        try {
            f = call.get();
        } catch (RuntimeException thrown) {
            return thrown;
        }
        until(f::isComplete, "the ingest's answer");
        if (f.succeeded()) throw new AssertionError("the ingest did not fail: " + f.result());
        return f.cause();
    }

    @Test
    void ingestAnswersTheConsumersAck() {
        Promise<Ack> answer = Promise.promise();
        holds.add(answer);
        Handled handled = consume(http, in, e -> answer.future());

        Future<Ack> ingested = http.ingest("http-in", Headers.empty().with("x-request-id", "r-7"), Buffer.buffer("<a/>"));

        until(() -> handled.envelopes().size() == 1, "the consumer's handler got the ingested envelope");
        assertFalse(ingested.isComplete(), "the ingest answers when the consumer's handler does");
        Consumer consumer = started.removeFirst();
        Future<Void> stopped = consumer.stop();
        assertFalse(stopped.isComplete(), "delivered through the consumer's own tracked handler: its stop waits for the answer (D-056)");
        Ack ack = new Ack.Accepted("m-x");
        answer.complete(ack);
        until(ingested::isComplete, "the ingest's answer");
        assertSame(ack, ingested.result(), "the consumer's Ack as it answered it");
        stopped.await();

        assertTrue(handled.threads().getFirst().isVirtual(), "the handler runs on a virtual thread of its own (D-057): " + handled.threads());
        Envelope e = handled.envelopes().getFirst();
        assertEquals("in-1", e.id(), "the id from the mock's generator");
        assertEquals("orders", e.flow(), "the flow from the consumer's config");
        assertEquals(0, e.step());
        assertEquals("r-7", e.header("x-request-id").orElseThrow());
        assertEquals("<a/>", e.body().whole().toString());
        assertEquals(BlobKey.body("in-1", 0), e.body().key(), "stored as the consumer stores an ingress body");
    }

    @Test
    void ingestWithoutConsumerFails() {
        assertInstanceOf(IllegalStateException.class, failureOf(() -> http.ingest("http-in", Headers.empty(), Buffer.buffer("x"))),
                "no consumer at all");

        Handled other = consume(http, new EndpointConfig("other-in", URI.create("http://other-in"), Map.of("flow", "other", "maxBodyBytes", 8L)),
                e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        assertInstanceOf(IllegalStateException.class, failureOf(() -> http.ingest("http-in", Headers.empty(), Buffer.buffer("x"))),
                "a consumer of another endpoint only");
        assertEquals(List.of(), other.envelopes(), "the other endpoint's consumer got nothing");

        Handled mine = consume(http, in, e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        Ack delivered = http.ingest("http-in", Headers.empty(), Buffer.buffer("x")).await();
        assertEquals(1, mine.envelopes().size(), "running: delivered");
        assertEquals(new Ack.Accepted(mine.envelopes().getFirst().id()), delivered, "the handler's answer");
        started.removeLast().stop().await();
        assertInstanceOf(IllegalStateException.class, failureOf(() -> http.ingest("http-in", Headers.empty(), Buffer.buffer("x"))),
                "the consumer has stopped");
        assertEquals(1, mine.envelopes().size(), "nothing reached the stopped consumer");
    }

    @Test
    void ingestAboveMaxBodyBytesFails() {
        Handled handled = consume(http, in, e -> Future.succeededFuture(new Ack.Accepted(e.id())));

        Throwable tooLarge = failureOf(() -> http.ingest("http-in", Headers.empty(), Buffer.buffer("123456789")));

        assertInstanceOf(BodyTooLargeException.class, tooLarge, "9 bytes above the consumer's maxBodyBytes 8");
        assertEquals(List.of(), handled.envelopes(), "the handler is never called");
        assertEquals(List.of(), http.delivered("http-in"));

        Ack ack = http.ingest("http-in", Headers.empty(), Buffer.buffer("12345678")).await();

        assertInstanceOf(Ack.Accepted.class, ack, "8 bytes, the limit itself, go through");
        assertEquals(1, handled.envelopes().size());
        assertEquals("12345678", handled.envelopes().getFirst().body().whole().toString());
    }

    @Test
    void ingestOnHttpSetsMethodAndPath() {
        Handled viaHttp = consume(http, in, e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        MockComponent sftp = new MockComponent("sftp", rt.blobs(), rt.timer(), ids);
        Handled viaSftp = consume(sftp, new EndpointConfig("drop", URI.create("sftp://drop"),
                Map.of("user", "u", "flow", "orders", "maxBodyBytes", 8L)), e -> Future.succeededFuture(new Ack.Accepted(e.id())));

        http.ingest("http-in", Headers.empty(), Buffer.buffer("<a/>")).await();
        sftp.ingest("drop", Headers.empty(), Buffer.buffer("<a/>")).await();

        Envelope e = viaHttp.envelopes().getFirst();
        assertEquals("POST", e.get(METHOD).orElseThrow(), "an http ingress is a POST");
        assertEquals("/in", e.get(PATH).orElseThrow(), "at the endpoint's path (its config's, not the URI's)");
        Envelope dropped = viaSftp.envelopes().getFirst();
        assertTrue(dropped.get(METHOD).isEmpty() && dropped.get(PATH).isEmpty(), "no http props on another scheme: " + dropped.props());
    }

    /** An http endpoint without a {@code path} is served at the root: the ingress envelope's {@code http.path} is {@code /}. */
    @Test
    void ingestOnHttpWithoutAPathIsAtTheRoot() {
        Handled handled = consume(http, new EndpointConfig("http-root", URI.create("http://http-root"),
                Map.of("host", "0.0.0.0", "port", 8080, "flow", "orders", "maxBodyBytes", 8L)), e -> Future.succeededFuture(new Ack.Accepted(e.id())));

        http.ingest("http-root", Headers.empty(), Buffer.buffer("<a/>")).await();

        Envelope e = handled.envelopes().getFirst();
        assertEquals("POST", e.get(METHOD).orElseThrow());
        assertEquals("/", e.get(PATH).orElseThrow(), "no path in the config: the root");
    }

    @Test
    void deliveredRecordsIngested() {
        Handled handled = consume(http, in, e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        assertEquals(List.of(), http.delivered("http-in"), "nothing yet");

        http.ingest("http-in", Headers.empty().with("n", "1"), Buffer.buffer("one")).await();
        http.ingest("http-in", Headers.empty().with("n", "2"), Buffer.buffer("two")).await();

        List<Envelope> delivered = http.delivered("http-in");
        assertEquals(handled.envelopes(), delivered, "the envelopes the consumer's handler got, in order");
        assertEquals(List.of("in-1", "in-2"), delivered.stream().map(Envelope::id).toList());
        assertEquals(List.of(), http.delivered("other-in"), "per endpoint");
    }

    @Test
    void ingestIsNotSent() {
        consume(http, in, e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        EndpointConfig partner = new EndpointConfig("partner", URI.create("http://partner"), Map.of());
        Envelope out = rt.ingress("orders", Headers.empty(), Buffer.buffer("<b/>"));
        http.producer(partner).send(out).await();
        assertEquals(List.of(out), http.sent(), "a producer's send is recorded");

        http.ingest("http-in", Headers.empty(), Buffer.buffer("<a/>")).await();

        assertEquals(List.of(out), http.sent(), "the ingest is not a send");
        assertEquals(List.of(), http.sentTo("http-in"));
        assertEquals(1, http.delivered("http-in").size(), "it was delivered");
    }
}
