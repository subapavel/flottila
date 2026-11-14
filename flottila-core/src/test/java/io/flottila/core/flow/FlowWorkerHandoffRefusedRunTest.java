package io.flottila.core.flow;

import static io.flottila.core.flow.FlowWorkerHandoffTest.FLOW;
import static io.flottila.core.flow.FlowWorkerHandoffTest.LEASE;
import static io.flottila.core.flow.FlowWorkerHandoffTest.NODE;
import static io.flottila.core.flow.FlowWorkerHandoffTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerHandoffTest.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.flottila.api.Envelope;
import io.flottila.api.Runtime;
import io.flottila.core.flow.FlowWorkerHandoffEdgeTest.RefusingTimer;
import io.flottila.core.flow.FlowWorkerHandoffTest.World;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P2-16a review r1 R4: a hand-off whose run cannot start — the timer throws at the run's first renewal ({@code lease / 3}) — on
 * {@link FlowWorkerHandoffTest}'s world with a {@link RefusingTimer}: the room and a pending close come back, the item stays to its
 * lease and is claimed at its lease end, and the {@link SequentialIntake} save that handed it off still answers {@code Accepted}.
 */
class FlowWorkerHandoffRefusedRunTest {

    World w;
    FlowWorkerTest.Logs logs;
    RefusingTimer timer;
    FlowBuilder b;

    @BeforeEach
    void setUp() {
        w = new World();
        logs = FlowWorkerTest.Logs.attach();
        timer = new RefusingTimer(w.rt.timer());
        Runtime runtime = new Runtime(w.rt.clock(), timer, w.rt.ids(), w.rt.blobs(), w.rt.hasher(), Map.of());
        b = new FlowBuilder(runtime, List.of(w.gate), List.of(BuiltInStepsTest.HEADERS), Map.of())
                .recording(w.store, Meter.system()).queue(w.queue).dlq(w.dlq).fence(w.fence::get).workers(w.workers)
                .intake(new SequentialIntake(w.store, w.queue, w.workers, NODE));
    }

    @AfterEach
    void tearDown() {
        logs.detach();
        w.close();
    }

    @Test
    void aSaveWhoseHandOffCannotStartIsAcceptedAndItsItemRunsAtItsLeaseEnd() {
        FlowDefinition def = World.def(1, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = w.started(w.worker(b, def));
        w.gate.open();

        timer.refuseNext(LEASE.dividedBy(3));   // the slot's run arms its first renewal: refused
        Envelope in = w.accept(delivery);   // asserts Accepted: the slot's throw never decides the save
        assertEquals(1, timer.refused.get(), "fixture: the hand-off's run met the refusing timer");
        assertEquals(Optional.of(NODE), w.holder(in.id()), "the item is queued leased to the node, as the save wrote it");
        assertEquals(List.of(), w.entered(), "the run never started");
        assertEquals(0, w1.unsettled(), "out of the running items again");
        assertFalse(logs.at(Level.WARNING, "the hand-off slot of message " + in.id()).isEmpty(), "the throw is logged: " + logs.all());
        assertTrue(logs.at(Level.WARNING, "the hand-off slot of message " + in.id()).get(0).contains(RefusingTimer.REFUSAL.getMessage()), logs.all().toString());

        FlowWorker.Slot room = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("room 1 is back"));
        room.cancel(false);
        w.rt.clock().advance(LEASE.minusMillis(1));
        assertEquals(1, w.queue.claims().size(), "nothing claims it before its lease end: " + w.queue.claims());
        w.rt.clock().advance(java.time.Duration.ofMillis(1));   // the lease-end wake the refused run armed; the tick is 10 min away
        until(() -> w.rows.size() == 0, () -> "claimed and run at its lease end; claims " + w.queue.claims() + ", rows " + w.rows.snapshot());
        assertEquals(List.of(in.id()), w.entered(), "run once, by the lease-end claim");
    }

    @Test
    void aSlotRunThatCannotStartAfterTheStopLetsThePendingCloseComplete() {
        FlowWorker w1 = w.started(w.worker(b, World.def(1, List.of(RunGate.STEP))));
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("room 1: a slot"));
        Envelope in = w.recorded();
        WorkQueue.Item leased = w.queue.enqueueLeased(in.id(), FLOW, 1, null, NODE, LEASE).await().orElseThrow();
        w1.stop();
        Future<Void> closed = w1.close();
        assertFalse(closed.isComplete(), "fixture: the reserved slot holds the close");

        timer.refuseNext(LEASE.dividedBy(3));
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> slot.run(leased));
        assertSame(RefusingTimer.REFUSAL, thrown, "the timer's own failure passed on");
        assertEquals(0, w1.unsettled(), "the run that never started is not running");
        until(closed::isComplete, () -> "the close the slot held completes; unsettled " + w1.unsettled());
        assertEquals(List.of(), w.entered(), "nothing ran");
    }
}
