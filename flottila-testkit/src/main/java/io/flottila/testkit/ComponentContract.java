package io.flottila.testkit;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cases every component passes, real or mock (ARCHITECTURE §11: "both must pass the same contract suite"). A
 * component's test extends this class and supplies the component and an endpoint that selects it. Every case asserts
 * an observable: the scheme, the config schema's shape, delivery to the running consumer's handler with the body
 * intact (the receiver mints its own message id — a transport carries bytes and headers), a stopped consumer receiving nothing, health, the send completing only after the handler acknowledged, a
 * Nack failing the send, a body above the tree limit streaming through, a second send of the same envelope delivering
 * the same body again (a retry re-opens the body, nothing is consumed).
 *
 * @since 0.1
 */
@Timeout(60)
public abstract class ComponentContract {

    /** A fresh component under test; called once per test case. */
    protected abstract Component component();

    /**
     * An endpoint whose URI scheme selects {@link #component()}; its options bind the consumer. The producer's target is
     * {@link #producerEndpoint(Consumer)}.
     */
    protected abstract EndpointConfig endpoint();

    /**
     * The endpoint a producer sends to in order to reach {@code started}, a consumer of {@link #endpoint()} that has started;
     * by default {@link #endpoint()} itself. A transport that assigns the consumer's address only at start (an ephemeral
     * port) overrides it and reads the address from the started consumer.
     */
    protected EndpointConfig producerEndpoint(Consumer started) { return endpoint(); }

    /** How long a case waits for an asynchronous delivery before it fails (a real transport needs a moment). */
    protected Duration patience() { return Duration.ofSeconds(10); }

    /** Spins (yielding, never sleeping) until {@code condition} holds or {@link #patience()} is spent. */
    protected final boolean soon(BooleanSupplier condition) {
        long deadline = System.nanoTime() + patience().toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    @Test
    public void schemeMatchesTheEndpointUri() {
        String scheme = component().scheme();
        assertNotNull(scheme, "scheme");
        assertFalse(scheme.isBlank(), "scheme is blank");
        assertEquals(endpoint().scheme(), scheme, "the endpoint's URI scheme selects this component");
    }

    @Test
    public void configSchemaIsAJsonObjectSchema() {
        JsonObject schema = new JsonObject(component().configSchema());
        assertEquals("object", schema.getString("type"), "the endpoint options are validated against an object schema: " + schema);
    }

    @Test
    public void healthIsUpWhileAConsumerRunsAndDownAfterItStopped() {
        Component component = component();
        Consumer consumer = component.consumer(endpoint(), e -> Future.succeededFuture(new Ack.Accepted(e.id())));
        consumer.start().await();
        try {
            assertEquals(Health.Status.UP, component.health().await().status());
        } finally {
            consumer.stop().await();
        }
        assertEquals(Health.Status.DOWN, component.health().await().status(), "nothing listens any more");
    }

    @Test
    public void aStoppedConsumerReceivesNothing() {
        Component component = component();
        AtomicInteger delivered = new AtomicInteger();
        Consumer consumer = component.consumer(endpoint(), e -> { delivered.incrementAndGet(); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        consumer.start().await();
        EndpointConfig where = producerEndpoint(consumer);   // where the consumer was: the producer targets a stopped address
        consumer.stop().await();
        Producer producer = component.producer(where);
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("late"));
            Future<Envelope> outcome = producer.send(sent);
            outcome.otherwiseEmpty().await();   // a failed send is the expected answer of a stopped transport; what matters is below
            assertEquals(0, delivered.get(), "the stopped consumer's handler must not see the message");
        } finally {
            producer.close().await();
        }
    }

    @Test
    public void producerDeliversToTheRunningConsumersHandlerWithTheBodyIntact() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(endpoint(), e -> {
            seen.add(e);
            return Future.succeededFuture(new Ack.Reply(e.withHeader("x-seen", "1")));
        });
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty().with("content-type", "text/plain"), Buffer.buffer("ping"));
            Envelope reply = producer.send(sent).await();
            assertEquals("1", reply.header("x-seen").orElse(null), "the reply is what the handler answered");
            assertEquals(1, seen.size(), "delivered exactly once");
            assertFalse(seen.get(0).id().isBlank(), "the receiver's message has an id of its own (a transport carries bytes and headers, never the sender's id)");
            assertEquals(sent.body().hash(), seen.get(0).body().hash(), "the body arrives intact");
            assertEquals(sent.body().size(), seen.get(0).body().size());
            assertEquals("ping", seen.get(0).body().whole().toString());
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void theSendCompletesOnlyAfterTheHandlerAcknowledged() {
        Component component = component();
        AtomicReference<Promise<Ack>> pending = new AtomicReference<>();
        Consumer consumer = component.consumer(endpoint(), e -> {
            Promise<Ack> p = Promise.promise();
            pending.set(p);
            return p.future();
        });
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("slow"));
            Future<Envelope> outcome = producer.send(sent);
            assertTrue(soon(() -> pending.get() != null), "the handler was invoked");
            assertFalse(outcome.isComplete(), "ACK only after the handler acknowledged: nothing completes before");
            pending.get().complete(new Ack.Reply(sent.withHeader("x-acked", "yes")));
            assertEquals("yes", outcome.await().header("x-acked").orElse(null));
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void aNackFromTheHandlerFailsTheSendWithItsReason() {
        Component component = component();
        Consumer consumer = component.consumer(endpoint(), e -> Future.succeededFuture(new Ack.Nack("busy-right-now", true)));
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("x"));
            Future<Envelope> outcome = producer.send(sent);
            outcome.otherwiseEmpty().await();
            assertTrue(outcome.failed(), "a NACK is not a delivery");
            assertTrue(String.valueOf(outcome.cause().getMessage()).contains("busy-right-now"), "the reason travels: " + outcome.cause());
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void aBodyAboveTheTreeLimitStreamsThroughIntact() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(endpoint(), e -> { seen.add(e); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        try {
            FakeRuntime small = FakeRuntime.create().withTreeLimit(4096);
            long size = 300_000;
            Body big = small.blobs().write(BlobKey.body("big", 0), new GeneratedReadStream(size, 8192), size).await();
            Envelope sent = Envelope.ingress("big", "contract", Headers.empty(), big);
            producer.send(sent).await();
            assertTrue(soon(() -> seen.size() == 1), "delivered");
            assertEquals(size, seen.get(0).body().size(), "every byte arrived");
            assertEquals(big.hash(), seen.get(0).body().hash(), "and the same bytes: a body above the tree limit is streamed, never loaded whole");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    @Test
    public void aSecondSendOfTheSameEnvelopeDeliversTheSameBodyAgain() {
        Component component = component();
        List<Envelope> seen = new CopyOnWriteArrayList<>();
        Consumer consumer = component.consumer(endpoint(), e -> { seen.add(e); return Future.succeededFuture(new Ack.Accepted(e.id())); });
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("retry-me"));
            producer.send(sent).await();
            producer.send(sent).await();
            assertTrue(soon(() -> seen.size() == 2), "a retry is a second delivery");
            for (Envelope e : seen) {
                assertEquals(sent.body().hash(), e.body().hash(), "the body was re-opened, not consumed by the first send");
                assertEquals("retry-me", e.body().whole().toString());
            }
            assertEquals("retry-me", sent.body().whole().toString(), "and the original is still readable");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }
}
