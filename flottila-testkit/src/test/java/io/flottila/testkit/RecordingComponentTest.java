package io.flottila.testkit;

import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recording mock passes the component contract and records, replies and fails as configured. */
class RecordingComponentTest extends ComponentContract {

    final RecordingComponent http = new RecordingComponent("http");
    final EndpointConfig partner = new EndpointConfig("partner", URI.create("http://partner/in"), Map.of());
    final EndpointConfig other = new EndpointConfig("other", URI.create("http://other/in"), Map.of());
    final FakeRuntime rt = FakeRuntime.create();

    @Override protected Component component() { return new RecordingComponent("http"); }

    @Override protected EndpointConfig endpoint() { return partner; }

    @Test
    void recordsPerEndpointAndRepliesWithTheConfiguredFunctionWhenNoConsumerRuns() {
        http.replyWith(e -> e.withHeader("ack", "ok"));
        Producer p1 = http.producer(partner);
        Producer p2 = http.producer(other);
        Envelope a = rt.ingress("f", Headers.empty(), Buffer.buffer("a"));
        Envelope b = rt.ingress("f", Headers.empty(), Buffer.buffer("b"));
        assertEquals("ok", p1.send(a).await().header("ack").orElseThrow());
        p2.send(b).await();
        assertEquals(List.of(a, b), http.sent());
        assertEquals(List.of(a), http.sentTo("partner"));
        assertEquals(List.of(b), http.sentTo("other"));
        assertEquals(List.of(), http.sentTo("nobody"));
        assertEquals(2, http.producersBuilt());
        assertEquals(Health.Status.DOWN, http.health().await().status(), "no consumer runs");
        p1.close().await();
    }

    @Test
    void queuedFailuresAreUsedInOrderBeforeAnyReplyAndEachIsAFreshException() {
        http.failNext(2, () -> new IllegalStateException("503"));
        assertEquals(2, http.failuresLeft());
        Producer p = http.producer(partner);
        Envelope e = rt.ingress("f", Headers.empty(), Buffer.buffer("a"));
        Throwable first = p.send(e).cause();
        Throwable second = p.send(e).cause();
        assertTrue(first instanceof IllegalStateException && second instanceof IllegalStateException);
        assertTrue(first != second, "a fresh exception per failure: suppressed lists do not tangle");
        assertSame(e, p.send(e).await(), "then the identity reply");
        assertEquals(3, http.sentTo("partner").size(), "failed sends are recorded too: the retries are visible");
        assertEquals(0, http.failuresLeft());
        assertThrows(IllegalArgumentException.class, () -> http.failNext(-1, () -> new RuntimeException()));
        assertThrows(NullPointerException.class, () -> http.replyWith(null));
        assertThrows(NullPointerException.class, () -> new RecordingComponent(null));
    }

    @Test
    void aRunningConsumersHandlerAnswersAndANonReplyAckEchoesTheEnvelope() throws ExecutionException, InterruptedException {
        var consumer = http.consumer(partner, e -> io.vertx.core.Future.succeededFuture(new io.flottila.api.Ack.Accepted(e.id())));
        consumer.start().await();
        Envelope e = rt.ingress("f", Headers.empty(), Buffer.buffer("a"));
        assertSame(e, http.producer(partner).send(e).await(), "Accepted is not a reply: the envelope itself comes back");
        assertEquals("ok", http.replyWith(x -> x.withHeader("ack", "ok")).producer(other).send(e).await().header("ack").orElseThrow(), "another endpoint keeps the configured reply: the consumer answers its own endpoint only");
        assertEquals(Health.Status.UP, http.health().await().status());
        consumer.stop().await();
        var nacking = http.consumer(partner, x -> io.vertx.core.Future.succeededFuture(new io.flottila.api.Ack.Nack("no room", true)));
        nacking.start().await();
        io.vertx.core.Future<Envelope> refused = http.producer(partner).send(e);
        refused.otherwiseEmpty().await();
        assertTrue(refused.cause() instanceof io.flottila.api.NackException n && n.retryable() && n.reason().equals("no room"), String.valueOf(refused.cause()));
        nacking.stop().await();
        assertEquals(Health.Status.DOWN, http.health().await().status());
    }
}
