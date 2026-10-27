package io.flottila.core.flow;

import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.flottila.core.flow.FlowBuilderTest.NO_FILES;
import static io.flottila.core.flow.FlowBuilderTest.factory;
import static io.flottila.core.flow.FlowBuilderTest.flow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c2 (D-067), the edges of {@link Replay} the packet's cases leave open: a second {@link Replay#close()} while a run is in
 * flight releases nothing more — the producers still close only once that run ended — and a retry that went through but whose
 * DLQ row could not be removed is a {@link RowNotRemovedException} naming both ids, the row kept, the permit given back.
 */
class ReplayEdgeTest {

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final AtomicBoolean down = new AtomicBoolean();
    final RunGate gate = new RunGate();

    final StepFactory mark = factory("mark", s -> Stage.of(e -> e.withHeader("seen-" + s.name(), "yes")));
    final StepFactory flaky = factory("flaky", s -> Stage.of(e -> {
        if (down.get()) throw new IllegalStateException(s.name() + " down");
        return e;
    }));

    static Map<String, Object> step(String type, String name) {
        return Map.of("type", type, "name", name);
    }

    /** {@code flow(steps)} with this delivery mode and one request in flight. */
    static FlowDefinition def(String mode, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>(flow(steps));
        doc.put("delivery", Map.of("mode", mode));
        doc.put("limits", Map.of("maxBodyBytes", 4096, "maxInFlight", 1));
        return FlowDefinition.from(doc);
    }

    static CompletableFuture<Envelope> onVirtualThread(java.util.concurrent.Callable<Envelope> call) {
        CompletableFuture<Envelope> out = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                out.complete(call.call());
            } catch (Throwable failed) {
                out.completeExceptionally(failed);
            }
        });
        return out;
    }

    /**
     * Two closes while a run is held at the gate: neither completes, and the replay's producer stays open under the run; once the
     * run ended through it, both complete and the producer is closed once.
     */
    @Test
    void aSecondCloseWhileARunIsInFlightStillWaitsForTheRun() throws Exception {
        ClosingPartner partner = new ClosingPartner();
        rt.withComponent(partner);
        Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(mark, gate), List.of(), endpoints).recording(store, () -> 0);
        Envelope original = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        b.build(def("sync", List.of(step("mark", "a"), step("mark", "b"))), NO_FILES).apply(original);
        Replay replay = b.replay(def("sync", List.of(step("mark", "a"), RunGate.STEP, Map.of("type", "to", "name", "send", "endpoint", "partner"))), NO_FILES);
        ClosingPartner.Out own = partner.built().get(0);
        CompletableFuture<Envelope> run = onVirtualThread(() -> replay.from(original.id(), 2, "held"));
        DeliveryTest.until(() -> gate.runsOf("held").size() == 1, () -> "the run reached the gate: " + gate.runs());

        Future<Void> first = replay.close();
        Future<Void> second = replay.close();

        assertFalse(first.isComplete() || second.isComplete(), "a run is in flight");
        assertEquals(0, own.closes(), "a second close gives back no hold of the run's: the producer stays open under it");
        gate.runsOf("held").get(0).release();
        assertEquals("held", run.get(5, TimeUnit.SECONDS).id(), "the run sent through its open producer");
        second.await(5, TimeUnit.SECONDS);
        first.await(5, TimeUnit.SECONDS);
        assertEquals(1, own.closes(), "closed once, after the run");
        assertEquals(List.of("held"), partner.partner().sent().stream().map(Envelope::id).toList());
    }

    /** A {@link Dlq} over an {@link InMemoryDlq} whose removals fail while {@link #failRemoves} is set (the store boundary). */
    static final class RemovalFailingDlq implements Dlq {
        final InMemoryDlq inner = new InMemoryDlq();
        volatile boolean failRemoves;

        @Override public Future<Void> park(Parked parked) { return inner.park(parked); }
        @Override public Future<Page<Parked>> list(Optional<String> after, int limit) { return inner.list(after, limit); }
        @Override public Future<Optional<Parked>> get(String messageId) { return inner.get(messageId); }
        @Override public Future<Void> remove(String messageId) {
            return failRemoves ? Future.failedFuture(new IllegalStateException("removal refused for " + messageId)) : inner.remove(messageId);
        }
    }

    /**
     * A retry whose run went through but whose row removal failed: a {@link RowNotRemovedException} — not the run's failure —
     * naming the parked id and the new one, the removal's failure its cause; the new message's rows are written, the row stays,
     * and the permit is back: the next retry of the one-request flow runs and removes the row.
     */
    @Test
    void aRetryWhoseRowRemovalFailsIsRowNotRemovedNamingBothIds() {
        RemovalFailingDlq dlq = new RemovalFailingDlq();
        FlowBuilder b = new FlowBuilder(rt.runtime(), List.of(mark, flaky), List.of(), Map.of()).recording(store, () -> 0).dlq(dlq);
        FlowDefinition def = def("at-least-once", List.of(step("mark", "a"), step("flaky", "c")));
        Envelope in = rt.ingress("f", Headers.empty(), Buffer.buffer("<x/>"));
        down.set(true);
        assertEquals(2, assertThrows(ParkedException.class, () -> b.build(def, NO_FILES).apply(in)).parked().step(), "fixture: parked at c");
        down.set(false);
        Replay replay = b.replay(def, NO_FILES);
        dlq.failRemoves = true;

        RowNotRemovedException kept = assertThrows(RowNotRemovedException.class, () -> replay.retry(in.id(), "retry-1"));

        assertTrue(kept.getMessage().contains("retry-1") && kept.getMessage().contains(in.id()), "names both ids: " + kept.getMessage());
        assertEquals("removal refused for " + in.id(), assertInstanceOf(IllegalStateException.class, kept.getCause()).getMessage());
        assertEquals(List.of(Replay.REPLAY_INPUT, "c"), store.timeline("retry-1").await().stream().map(Snapshot::stepName).toList(), "the retry went through");
        assertTrue(dlq.get(in.id()).await().isPresent(), "the row stays");
        dlq.failRemoves = false;
        assertEquals("retry-2", replay.retry(in.id(), "retry-2").id(), "the permit came back: the next retry runs");
        assertEquals(Optional.empty(), dlq.get(in.id()).await(), "and removes the row");
    }
}
