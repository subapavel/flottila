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
import io.flottila.api.NackException;
import io.flottila.api.Producer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The cases every component passes, real or mock (ARCHITECTURE §11: "both must pass the same contract suite"). A
 * component's test extends this class and supplies the component and an endpoint that selects it. Every case asserts
 * an observable: the scheme, the config schema's shape, delivery to the running consumer's handler with the body
 * intact (the receiver mints its own message id — a transport carries bytes and headers), a stopped consumer receiving nothing, health, the send completing only after the handler acknowledged, a
 * Nack failing the send, a body above the tree limit streaming through, a second send of the same envelope delivering
 * the same body again (a retry re-opens the body, nothing is consumed), and a consumer stopped mid-delivery (D-056):
 * {@link Consumer#stop()} accepts nothing new, waits for the delivery in flight and lets its answer reach the sender — a
 * cancel reaches the component as a retryable {@link Ack.Nack} the sender sees as a retryable {@link NackException} — and
 * each delivery calling the handler on a virtual thread of its own, never the sender's (D-057); a handler that throws or
 * answers null fails the send, and a stop after it completes.
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

    /**
     * Waits until {@code f} has completed, either way, for at most {@link #patience()}; a future still pending then fails
     * the case (a transport that never answers must not hang the suite).
     */
    private <T> Future<T> settled(Future<T> f) {
        try {
            f.otherwiseEmpty().await(patience().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException pending) {
            fail("still pending after " + patience() + ": " + f);
        }
        return f;
    }

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
            if (pending.get() != null) pending.get().tryComplete(new Ack.Nack("contract cleanup", true));   // a stop drains the answers in flight (D-056)
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
    public void aHandlerThatThrowsFailsTheSendAndStopStillCompletes() {
        aBrokenHandlerFailsTheSendAndStopStillCompletes("throws", e -> { throw new IllegalStateException("handler-threw"); });
    }

    @Test
    public void aHandlerThatAnswersNullFailsTheSendAndStopStillCompletes() {
        aBrokenHandlerFailsTheSendAndStopStillCompletes("answers null", e -> null);
    }

    /**
     * A handler that {@code what} acknowledged nothing: the send fails — it neither succeeds nor hangs — and the answer the
     * delivery counted (D-056) counts as answered, so a stop that follows completes.
     */
    private void aBrokenHandlerFailsTheSendAndStopStillCompletes(String what, Function<Envelope, Future<Ack>> broken) {
        Component component = component();
        AtomicInteger invoked = new AtomicInteger();
        Consumer consumer = component.consumer(endpoint(), e -> { invoked.incrementAndGet(); return broken.apply(e); });
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        Future<Void> stopped = null;
        try {
            Future<Envelope> sent = producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("broken-handler")));
            assertTrue(soon(() -> invoked.get() == 1), "the handler was invoked");
            assertTrue(settled(sent).failed(), "a handler that " + what + " acknowledged nothing: the send fails: " + sent);
            stopped = consumer.stop();
            assertTrue(settled(stopped).succeeded(), "the failed answer counts as answered: stop completes: " + stopped.cause());
        } finally {
            if (stopped == null) consumer.stop();   // the case has failed already; a send that never settled may hold this stop
            producer.close().await();
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

    /**
     * Each delivery calls the handler on a virtual thread of its own (D-057): never on the sender's thread — a stop deadline's
     * interrupt aimed at the handler's thread then never reaches a sender — and never on one thread shared by the deliveries:
     * two deliveries in flight at once are in their handlers at the same time, on two threads. The senders are virtual
     * threads, the way a flow sends, so a component that calls the handler on the caller's thread fails on the sender check,
     * not on the thread kind. A handler blocks only on a virtual thread: a broken component's event loop is never held.
     */
    @Test
    public void theHandlerRunsOnAVirtualThreadOfItsOwnPerDelivery() throws Exception {
        Component component = component();
        List<Thread> handlers = new CopyOnWriteArrayList<>();
        List<Boolean> together = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(2);
        Consumer consumer = component.consumer(endpoint(), e -> {
            Thread self = Thread.currentThread();
            handlers.add(self);
            entered.countDown();
            if (self.isVirtual()) together.add(bothEntered(entered));   // waits until the other delivery is in its handler too
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        List<Thread> senders = new CopyOnWriteArrayList<>();
        List<Future<Envelope>> sends = new CopyOnWriteArrayList<>();
        try {
            List<Thread> sending = new ArrayList<>();
            for (String body : List.of("first", "second")) {
                sending.add(Thread.ofVirtual().name("contract-sender-" + body).start(() -> {
                    senders.add(Thread.currentThread());
                    sends.add(producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer(body))));
                }));
            }
            for (Thread t : sending) {
                assertTrue(t.join(patience().multipliedBy(2)), "the sender " + t + " is still inside send(), at " + java.util.Arrays.toString(t.getStackTrace()));
            }
            assertEquals(2, sends.size(), "both sends were issued");
            for (Future<Envelope> s : sends) {   // a handler that waited for the other one in vain answers after a patience: wait for both
                try {
                    s.otherwiseEmpty().await(patience().toMillis() * 3, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pending) {
                    fail("still pending after three times " + patience() + ": " + s);
                }
                assertTrue(s.succeeded(), "delivered: " + s.cause());
            }
            assertTrue(soon(() -> handlers.size() >= 2), "both deliveries reached the handler: " + handlers);   // a send acknowledged early must not race the check
            assertEquals(2, handlers.size(), "one handler call per delivery: " + handlers);
            for (Thread h : handlers) assertTrue(h.isVirtual(), "the handler runs on a virtual thread, not on " + h);
            for (Thread h : handlers) assertFalse(senders.contains(h), "the handler runs on a thread of its own, not on the sender's " + h + " (senders " + senders + ")");
            assertNotSame(handlers.get(0), handlers.get(1), "two deliveries in flight at once run on two threads, not both on " + handlers.get(0));
            assertTrue(soon(() -> together.size() == 2), "both handlers finished waiting for each other: " + together);
            assertEquals(List.of(true, true), together, "both deliveries were in their handlers at the same time (false: one waited " + patience() + " for the other)");
        } finally {
            producer.close().await();
            consumer.stop().await();
        }
    }

    /** Waits, at most {@link #patience()}, until both deliveries entered the handler; an interrupt is an answer of {@code false}, the flag kept. */
    private boolean bothEntered(CountDownLatch entered) {
        try {
            return entered.await(patience().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The reason a delivery cancelled by its flow's stop deadline answers with (D-056: core's {@code Delivery} decides it). */
    static final String CANCELLED = "cancelled: flow contract stopping after PT30S";

    @Test
    public void stopWaitsForTheDeliveryInFlightAndItsAnswerReachesTheSender() throws Exception {
        Component component = component();
        AtomicInteger invoked = new AtomicInteger();
        Promise<Ack> answer = Promise.promise();
        Consumer consumer = component.consumer(endpoint(), e -> invoked.incrementAndGet() == 1
                ? answer.future()
                : Future.succeededFuture(new Ack.Reply(e.withHeader("x-answer", "reached-after-stop"))));
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        Future<Void> stopped = null;
        try {
            Envelope sent = FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("in-flight"));
            Future<Envelope> first = producer.send(sent);
            assertTrue(soon(() -> invoked.get() == 1), "the handler was invoked");
            stopped = consumer.stop();
            assertFalse(stopped.isComplete(), "stop waits for the delivery in flight: " + stopped);
            Future<Envelope> second = settled(producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("late"))));
            assertEquals(1, invoked.get(), "a send after stop began never reaches the handler (it was answered " + second + ")");
            assertFalse(first.isComplete(), "the delivery in flight is answered by its handler, not by the stop: " + first);
            assertFalse(stopped.isComplete(), "stop still waits: the delivery in flight has no answer yet");
            answer.complete(new Ack.Reply(sent.withHeader("x-answer", "given-while-stopping")));
            assertTrue(settled(first).succeeded(), "the answer given while the consumer stops reaches the sender: " + first.cause());
            assertEquals("given-while-stopping", first.result().header("x-answer").orElse(null), "the reply is what the handler answered");
            assertTrue(settled(stopped).succeeded(), "stop completes once the delivery in flight was answered: " + stopped.cause());
        } finally {
            answer.tryComplete(new Ack.Nack("contract cleanup", true));
            (stopped == null ? consumer.stop() : stopped).otherwiseEmpty().await(patience().toMillis(), TimeUnit.MILLISECONDS);
            producer.close().await();
        }
    }

    @Test
    public void aDeliveryCancelledWhileTheConsumerStopsFailsTheSendRetryable() throws Exception {
        Component component = component();
        AtomicInteger invoked = new AtomicInteger();
        Promise<Ack> answer = Promise.promise();
        Consumer consumer = component.consumer(endpoint(), e -> invoked.incrementAndGet() == 1
                ? answer.future()
                : Future.succeededFuture(new Ack.Reply(e.withHeader("x-answer", "reached-after-stop"))));
        consumer.start().await();
        Producer producer = component.producer(producerEndpoint(consumer));
        Future<Void> stopped = null;
        try {
            Future<Envelope> sent = producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("cancel-me")));
            assertTrue(soon(() -> invoked.get() == 1), "the handler was invoked");
            stopped = consumer.stop();
            assertFalse(stopped.isComplete(), "the consumer is stopping, not stopped, while the delivery is in flight: " + stopped);
            // the stop has acted by the time a later send is answered (a refused connection, a refusal, a behaviour)
            Future<Envelope> late = settled(producer.send(FakeRuntime.create().ingress("contract", Headers.empty(), Buffer.buffer("late"))));
            assertEquals(1, invoked.get(), "a send after stop began never reaches the handler (it was answered " + late + ")");
            assertFalse(sent.isComplete(), "the delivery in flight waits for its answer while the consumer stops: " + sent);
            answer.complete(new Ack.Nack(CANCELLED, true));
            assertTrue(settled(sent).failed(), "a cancelled delivery is not a delivery: " + sent);
            NackException nack = assertInstanceOf(NackException.class, sent.cause(), "a producer reports a NACK as NackException");
            assertTrue(nack.retryable(), "the sender may try again once the flow runs again: " + nack);
            assertTrue(nack.reason().contains(CANCELLED), "the reason travels: " + nack.reason());
            assertTrue(settled(stopped).succeeded(), "stop completes once the cancelled delivery was answered: " + stopped.cause());
        } finally {
            answer.tryComplete(new Ack.Nack("contract cleanup", true));
            (stopped == null ? consumer.stop() : stopped).otherwiseEmpty().await(patience().toMillis(), TimeUnit.MILLISECONDS);
            producer.close().await();
        }
    }
}
