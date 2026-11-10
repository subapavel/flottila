package io.flottila.core.flow;

import static io.flottila.core.flow.FlowWorkerHandoffTest.CRASH_FIRST;
import static io.flottila.core.flow.FlowWorkerHandoffTest.FLOW;
import static io.flottila.core.flow.FlowWorkerHandoffTest.LEASE;
import static io.flottila.core.flow.FlowWorkerHandoffTest.NODE;
import static io.flottila.core.flow.FlowWorkerHandoffTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerHandoffTest.PROBE;
import static io.flottila.core.flow.FlowWorkerHandoffTest.joined;
import static io.flottila.core.flow.FlowWorkerHandoffTest.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.flottila.api.Envelope;
import io.flottila.api.Runtime;
import io.flottila.api.Timer;
import io.flottila.core.flow.FlowWorkerHandoffTest.World;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The hand-off's edges beyond {@link FlowWorkerHandoffTest} (P2-16a, D-121 A1, A4–A6), on that test's world: a slot used once,
 * the wake of every flow, the lease-end wake armed again for a later end (and not for one the clock already passed), a hand-off
 * whose settle fails, the wake a slot cancelled in doubt arms — against the cancel of a save that did not land —, a wake while a
 * claim is out, and a queue that cannot hand off.
 */
class FlowWorkerHandoffEdgeTest {

    World w;
    FlowWorkerTest.Logs logs;

    @BeforeEach
    void setUp() {
        w = new World();
        logs = FlowWorkerTest.Logs.attach();
    }

    @AfterEach
    void tearDown() {
        logs.detach();
        w.close();
    }

    /** A row of a recorded message written leased to the node, as a save that reserved a slot writes it. */
    WorkQueue.Item leasedRow() {
        Envelope in = w.recorded();
        return w.queue.enqueueLeased(in.id(), FLOW, 1, null, NODE, LEASE).await().orElseThrow();
    }

    @Test
    void aSlotIsRunOrCancelledOnceAndGivesItsRoomBackOnce() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(RunGate.STEP));
        w.started(w.worker(b, def));
        WorkQueue.Item leased = leasedRow();

        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("room 1: a slot"));
        slot.cancel(false);
        assertThrows(IllegalStateException.class, () -> slot.cancel(false), "a second cancel");
        assertThrows(IllegalStateException.class, () -> slot.run(leased), "a run after the cancel");
        assertEquals(List.of(), w.gate.runs(), "the refused run started nothing");

        FlowWorker.Slot again = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("the one cancel gave the room back"));
        assertEquals(java.util.Optional.empty(), w.workers.reserve(FLOW), "the refused second cancel gave nothing back: room 1 still");
        again.run(leased);
        assertThrows(IllegalStateException.class, () -> again.run(leased), "a second run");
        assertThrows(IllegalStateException.class, () -> again.cancel(false), "a cancel after the run");
        w.finish(leased.messageId());
        assertEquals(List.of(leased.messageId()), w.entered(), "the slot ran its item once");
    }

    @Test
    void aWakeOfEveryFlowClaimsForEachStartedWorker() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        w.started(w.worker(b, def));
        Envelope in = w.recorded();
        w.queue.enqueue(in.id(), FLOW, 1, null).await();   // a plain row nobody told the worker of
        w.workers.wake("another-flow");   // no worker of that flow here: nothing
        assertEquals(1, w.queue.claims().size(), "nothing claims without a wake of this flow: " + w.queue.claims());

        w.workers.wakeAll();
        w.finish(in.id());
        assertEquals(2, w.queue.claims().size(), "the wake of every flow claimed once: " + w.queue.claims());
        assertEquals(List.of(in.id()), w.queue.claims().get(1).items.stream().map(WorkQueue.Item::messageId).toList(), "and took the row");
    }

    @Test
    void aLaterLeaseEndIsArmedWhenTheFirstFires() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(3, List.of(PROBE, CRASH_FIRST, RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        w.started(w.worker(b, def));
        w.gate.open();

        Envelope m1 = w.accept(delivery);   // its first run crashes: left to its lease, the wake armed at now + lease
        until(() -> w.probed.size() == 1, () -> "m1's first run started");
        joined(w.probed.get(0));
        w.rt.clock().advance(Duration.ofSeconds(10));
        Envelope m2 = w.accept(delivery);   // left 10 s later: its end is the later end the armed wake re-arms for
        until(() -> w.probed.size() == 2, () -> "m2's first run started");
        joined(w.probed.get(1));
        assertEquals(1, w.queue.claims().size(), "nothing claims while the leases run: " + w.queue.claims());

        w.rt.clock().advance(LEASE.minusSeconds(10));   // m1's lease end
        until(() -> w.rows.size() == 1, () -> "m1 rerun and completed; claims " + w.queue.claims());
        assertEquals(List.of(m1.id()), w.queue.claims().get(1).items.stream().map(WorkQueue.Item::messageId).toList(), "m1 only: m2's lease runs");

        w.rt.clock().advance(Duration.ofSeconds(10).minusMillis(1));
        assertEquals(2, w.queue.claims().size(), "not before m2's lease end: " + w.queue.claims());
        w.rt.clock().advance(Duration.ofMillis(1));
        until(() -> w.rows.size() == 0, () -> "m2 rerun at its lease end; claims " + w.queue.claims());
        assertEquals(3, w.queue.claims().size(), w.queue.claims().toString());
        assertEquals(List.of(m2.id()), w.queue.claims().get(2).items.stream().map(WorkQueue.Item::messageId).toList());
    }

    @Test
    void aLaterLeaseEndTheClockPassedIsNotArmedAgain() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(3, List.of(PROBE, CRASH_FIRST, RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        w.started(w.worker(b, def));
        w.gate.open();

        w.accept(delivery);
        until(() -> w.probed.size() == 1, () -> "the first run started");
        joined(w.probed.get(0));
        w.rt.clock().advance(Duration.ofSeconds(10));
        w.accept(delivery);
        until(() -> w.probed.size() == 2, () -> "the second run started");
        joined(w.probed.get(1));

        w.rt.clock().advance(LEASE.plusSeconds(5));   // one jump past both ends: the first wake fires once, both leases over
        until(() -> w.rows.size() == 0, () -> "both rerun; claims " + w.queue.claims());
        assertEquals(2, w.queue.claims().get(1).items.size(), "one claim took both: " + w.queue.claims());
        w.rt.clock().advance(LEASE);
        assertEquals(2, w.queue.claims().size(), "no wake armed for an end already passed: " + w.queue.claims());
    }

    @Test
    void aHandOffWhoseSettleFailsIsLoggedAndClaimedAtItsLeaseEnd() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = w.started(w.worker(b, def));
        w.gate.open();
        w.queue.failCompletes(1, new IllegalStateException("the complete is refused"));

        Envelope in = w.accept(delivery);
        until(() -> !logs.at(Level.WARNING, "the hand-off of message " + in.id()).isEmpty(), () -> "the failed hand-off logged: " + logs.all());
        assertTrue(logs.at(Level.WARNING, "the hand-off of message " + in.id()).get(0).contains("the complete is refused"), logs.all().toString());
        until(() -> w1.unsettled() == 0, () -> "the run ended");
        assertEquals(1, w.row(in.id()).attempts(), "the row stays as handed off, left to its lease");

        w.rt.clock().advance(LEASE);
        until(() -> w.rows.size() == 0, () -> "rerun at its lease end; claims " + w.queue.claims());
        assertEquals(List.of(2), w.queue.claims().get(1).items.stream().map(WorkQueue.Item::attempts).toList(),
                "the lease-end claim took the row, its second hand-out: " + w.queue.claims());
    }

    @Test
    void aSlotCancelledInDoubtClaimsItsRowAtTheLeaseEnd() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        w.started(w.worker(b, def));
        w.gate.open();
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow();
        WorkQueue.Item landed = leasedRow();   // the commit landed, its reply was lost

        slot.cancel(true);
        w.rt.clock().advance(LEASE.minusMillis(1));
        assertEquals(1, w.queue.claims().size(), "not before the lease end: " + w.queue.claims());
        w.rt.clock().advance(Duration.ofMillis(1));
        until(() -> w.rows.size() == 0, () -> "the row claimed and run at its lease end; claims " + w.queue.claims());
        assertEquals(List.of(landed.messageId()), w.entered());
    }

    @Test
    void aSlotCancelledForASaveThatDidNotLandArmsNoWake() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        w.started(w.worker(b, def));
        w.gate.open();
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow();
        leasedRow();   // a row leased to the node that this save did not write (the fixture's: the cancel says nothing landed)

        slot.cancel(false);
        w.rt.clock().advance(LEASE.plusSeconds(1));
        assertEquals(1, w.queue.claims().size(), "no lease-end wake for a save that did not land: " + w.queue.claims());
    }

    @Test
    void aWakeWhileAClaimIsOutKeepsTheBacklogKnownUntilAClaimDrainsIt() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        w.started(w.worker(b, def));
        w.queue.holdNext();
        w.workers.wake(FLOW);   // a claim out, held
        w.workers.wake(FLOW);   // a wake while it is out: a follow-up round
        w.queue.throwOnNextClaim(new IllegalStateException("the follow-up claim fails"));   // a failed claim leaves the backlog as it is
        w.queue.releaseHeld();  // the held claim answers nothing — less than it asked, but a wake came since it began
        until(() -> w.queue.claims().size() == 3 && w.queue.claimsInFlight() == 0, () -> "the follow-up claim failed: " + w.queue.claims());
        assertEquals(Optional.empty(), w.workers.reserve(FLOW), "the backlog the second wake made known stays known: no hand-off");

        w.workers.wake(FLOW);   // a claim with no wake since it began, answering less than it asked: drained
        until(() -> w.queue.claims().size() == 4 && w.queue.claimsInFlight() == 0, () -> "the next claim answered: " + w.queue.claims());
        FlowWorker.Slot after = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("drained: the hand-off is back"));
        after.cancel(false);
    }

    @Test
    void anItemWhoseHoldFailsIsClaimedAtItsLeaseEnd() {
        FailingDlq failing = new FailingDlq(w.dlq);
        FlowBuilder b = w.builder().dlq(failing);
        FlowDefinition def = World.def(1, List.of(CRASH_FIRST));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = b.worker(def, NO_FILES, NODE, new WorkerPolicy(LEASE, 1, FlowWorkerHandoffTest.TICK));   // past its attempts at once
        w.built.add(w1);
        w.started(w1);
        failing.failParks(new IllegalStateException("dlq down"));
        w.queue.failHolds(1, new IllegalStateException("the hold is refused"));
        w.gate.open();

        Envelope in = w.accept(delivery);   // its first run crashes: left to its lease
        until(() -> w1.unsettled() == 0 && w.rows.size() == 1, () -> "the first run left the row");
        w.rt.clock().advance(LEASE);   // rerun past maxAttempts: the park fails, so does the hold — left to its lease again
        until(() -> w.queue.holds().size() == 1 && w1.unsettled() == 0, () -> "the hold tried and failed; holds " + w.queue.holds());
        failing.parksOk();
        w.rt.clock().advance(LEASE);
        until(() -> w.rows.size() == 0 && w1.unsettled() == 0, () -> "rerun at the failed hold's lease end and parked; claims " + w.queue.claims());
        List<String> parked = failing.list(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(p -> p.messageId()).toList();
        assertEquals(List.of(in.id()), parked, "parked on its third hand-out");
    }

    @Test
    void aStoppedWorkerLeavesNoTimerArmed() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(PROBE, CRASH_FIRST));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = w.started(w.worker(b, def));
        w.accept(delivery);   // left to its lease: the lease-end wake armed
        until(() -> w.probed.size() == 1, () -> "the run started");
        joined(w.probed.get(0));
        until(() -> w1.unsettled() == 0, () -> "the run ended");

        w1.stop();
        assertEquals(0, w.rt.timer().pending(), "the stop cancels the tick and the lease-end wake");
    }

    @Test
    void anItemLeftAfterTheStopArmsNoWake() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(PROBE, CRASH_FIRST));
        FlowWorker w1 = w.started(w.worker(b, def));
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow();
        WorkQueue.Item leased = leasedRow();
        w1.stop();
        assertEquals(0, w.rt.timer().pending(), "fixture: nothing armed at the stop");

        slot.run(leased);   // a reserved slot runs after the stop (A5); its run crashes: left to its lease
        until(() -> w.probed.size() == 1, () -> "the run started");
        joined(w.probed.get(0));
        until(() -> w1.unsettled() == 0, () -> "the run ended");
        assertEquals(1, w.row(leased.messageId()).attempts(), "left as handed off");
        assertEquals(0, w.rt.timer().pending(), "a stopped worker arms no lease-end wake");
    }

    /** The sample jqwik found (FlowWorkerTest.runningItemsNeverExceedTheInFlightLimit, max 1): a claim out, then an arrival. */
    @Test
    void aClaimInFlightHoldsTheRoomItAskedFor() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = w.started(w.worker(b, def));
        Envelope spilled = w.recorded();
        w.queue.enqueue(spilled.id(), FLOW, 1, null).await();
        w.queue.holdNext();
        w1.poll();   // the tick's claim: out, asking for the one room
        until(() -> w.queue.heldClaims() == 1, () -> "the claim held; claims " + w.queue.claims());

        Envelope late = w.accept(delivery);
        assertEquals(0, w.row(late.id()).attempts(), "no slot while the claim holds the room: the arrival is a plain row");
        w.queue.releaseHeld();   // the claim answers the spilled row into the room it asked for
        w.finish(spilled.id());
        w.finish(late.id());
        assertEquals(1, w.gate.peakActive(), "never more than max running");
    }

    @Test
    void aClaimAsksForTheRoomReservationsLeave() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(3, List.of(RunGate.STEP));
        w.started(w.worker(b, def));
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow();
        w.workers.wake(FLOW);
        until(() -> w.queue.claims().size() == 2 && w.queue.claimsInFlight() == 0, () -> "the wake's claim answered: " + w.queue.claims());
        assertEquals(2, w.queue.claims().get(1).max, "max − running − reserved: " + w.queue.claims());
        slot.cancel(false);
    }

    @Test
    void aCloseWaitsForAReservedSlotUntilItIsCancelled() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(RunGate.STEP));
        FlowWorker w1 = w.started(w.worker(b, def));
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow();
        w1.stop();
        Future<Void> closed = w1.close();
        assertEquals(false, closed.isComplete(), "a reserved slot may still run: the close waits");
        slot.cancel(false);
        assertTrue(closed.isComplete(), "the slot given back was the last thing the close waited for");
    }

    /** Every method by {@code rows}; {@code enqueueLeased} left to the interface: a queue that cannot hand off. */
    record Plain(WorkQueue rows) implements WorkQueue {
        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return rows.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return rows.claim(workerId, flow, max, lease); }
        @Override public Future<Boolean> complete(long seq, int attempts) { return rows.complete(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) { return rows.completeAndTakeNext(seq, attempts, workerId, lease); }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return rows.renew(seq, attempts, lease); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return rows.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return rows.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return rows.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return rows.hasItems(flow); }
    }

    @Test
    void anUnkeyedSaveOnAQueueThatCannotHandOffWakesItsWorker() {
        WorkQueue plain = new Plain(w.queue);
        FlowBuilder b = new FlowBuilder(w.rt.runtime(), List.of(w.gate), List.of(BuiltInStepsTest.HEADERS), Map.of())
                .recording(w.store, Meter.system()).queue(plain).dlq(w.dlq).fence(w.fence::get).workers(w.workers)
                .intake(new SequentialIntake(w.store, plain, w.workers, NODE));
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        w.started(w.worker(b, def));

        Envelope in = w.accept(delivery);   // a slot reserved, the row written plain: the slot given back, the worker woken
        assertEquals(List.of(), w.queue.enqueueLeasedCalls(), "the queue was asked a plain enqueue: the interface's default");
        w.finish(in.id());
        assertEquals(List.of(in.id()), w.queue.claims().get(1).items.stream().map(WorkQueue.Item::messageId).toList(),
                "the wake's claim took the row at once, not the tick: " + w.queue.claims());
    }

    /**
     * A timer over {@code inner} that refuses — throws {@link #REFUSAL} at the call — the first schedule of {@code delay} after
     * {@link #refuseNext}; every other schedule goes to {@code inner}.
     */
    static final class RefusingTimer implements Timer {
        static final IllegalStateException REFUSAL = new IllegalStateException("schedule refused by the test");
        final Timer inner;
        final AtomicReference<Duration> armed = new AtomicReference<>();
        final AtomicInteger refused = new AtomicInteger();

        RefusingTimer(Timer inner) { this.inner = inner; }

        void refuseNext(Duration delay) { armed.set(delay); }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            Duration d = armed.get();
            if (d != null && d.equals(delay) && armed.compareAndSet(d, null)) {
                refused.incrementAndGet();
                throw REFUSAL;
            }
            return inner.schedule(delay, task);
        }
    }

    /**
     * Review r1 R4 (FIX-45 pattern, FlowWorker:401-403): a slot cancelled in doubt whose lease-end wake cannot be armed — the
     * timer throws at the schedule — still gives its room back; the failure that surfaces, if any, is the timer's own.
     */
    @Test
    void aSlotCancelledInDoubtWhoseWakeCannotBeArmedStillGivesItsRoomBack() {
        RefusingTimer timer = new RefusingTimer(w.rt.timer());
        Runtime runtime = new Runtime(w.rt.clock(), timer, w.rt.ids(), w.rt.blobs(), w.rt.hasher(), Map.of());
        FlowBuilder b = new FlowBuilder(runtime, List.of(w.gate), List.of(BuiltInStepsTest.HEADERS), Map.of())
                .recording(w.store, Meter.system()).queue(w.queue).dlq(w.dlq).fence(w.fence::get).workers(w.workers)
                .intake(new SequentialIntake(w.store, w.queue, w.workers, NODE));
        w.started(w.worker(b, World.def(1, List.of(RunGate.STEP))));
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("room 1: a slot"));
        assertEquals(Optional.empty(), w.workers.reserve(FLOW), "fixture: the one slot takes the room");

        timer.refuseNext(LEASE);   // the lease-end wake's schedule (D-121 A4: armed at now + lease)
        RuntimeException surfaced = null;
        try {
            slot.cancel(true);
        } catch (RuntimeException e) {
            surfaced = e;
        }
        assertEquals(1, timer.refused.get(), "fixture: the cancel in doubt asked the timer for its wake and was refused");
        if (surfaced != null) assertSame(RefusingTimer.REFUSAL, surfaced, "only the timer's own failure may surface from the cancel");
        FlowWorker.Slot again = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("the cancel gave its room back though its wake failed"));
        again.cancel(false);
    }

    /**
     * A timer over {@code inner} that refuses — throws the exception given — the first schedule of each delay set by
     * {@link #refuse}, one-shot per delay, and records the refused delays in call order; every other schedule goes to {@code inner}.
     */
    static final class RefusalsTimer implements Timer {
        final Timer inner;
        final Map<Duration, RuntimeException> refusals = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Duration> refused = new CopyOnWriteArrayList<>();

        RefusalsTimer(Timer inner) { this.inner = inner; }

        void refuse(Duration delay, RuntimeException refusal) { refusals.put(delay, refusal); }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            RuntimeException refusal = refusals.remove(delay);
            if (refusal != null) {
                refused.add(delay);
                throw refusal;
            }
            return inner.schedule(delay, task);
        }
    }

    /**
     * Review r2 N2 (FIX-15 pattern, FlowWorker.handedOff's catch): a hand-off run that cannot start — its {@code lease / 3} renewal
     * refused — whose item, left to its lease, cannot arm its lease-end wake either — the {@code lease} schedule refused too —
     * surfaces the first refusal with the second carried as suppressed, never the second in its place; the room is back.
     */
    @Test
    void aHandOffRunWhoseRenewalAndLeaseEndWakeAreBothRefusedSurfacesTheFirstRefusalWithTheSecondSuppressed() {
        RefusalsTimer timer = new RefusalsTimer(w.rt.timer());
        Runtime runtime = new Runtime(w.rt.clock(), timer, w.rt.ids(), w.rt.blobs(), w.rt.hasher(), Map.of());
        FlowBuilder b = new FlowBuilder(runtime, List.of(w.gate), List.of(BuiltInStepsTest.HEADERS), Map.of())
                .recording(w.store, Meter.system()).queue(w.queue).dlq(w.dlq).fence(w.fence::get).workers(w.workers)
                .intake(new SequentialIntake(w.store, w.queue, w.workers, NODE));
        FlowWorker w1 = w.started(w.worker(b, World.def(1, List.of(RunGate.STEP))));
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("room 1: a slot"));
        assertEquals(Optional.empty(), w.workers.reserve(FLOW), "fixture: the one slot takes the room");
        WorkQueue.Item leased = leasedRow();

        IllegalStateException renewalRefused = new IllegalStateException("the run's renewal schedule refused by the test");
        IllegalStateException wakeRefused = new IllegalStateException("the lease-end wake's schedule refused by the test");
        timer.refuse(LEASE.dividedBy(3), renewalRefused);   // the run arms its first renewal at lease / 3
        timer.refuse(LEASE, wakeRefused);   // the item left to its lease arms the lease-end wake at now + lease (D-121 A4)
        Throwable thrown = assertThrows(Throwable.class, () -> slot.run(leased));

        assertEquals(List.of(LEASE.dividedBy(3), LEASE), List.copyOf(timer.refused),
                "fixture: the run's renewal, then the wake of the item left to its lease, met the refusing timer");
        assertSame(renewalRefused, thrown, "the first refusal surfaces, not the one met while handling it; surfaced: " + thrown);
        assertEquals(List.of(wakeRefused), List.of(thrown.getSuppressed()), "the second refusal is carried as suppressed");
        assertEquals(0, w1.unsettled(), "the run that never started is not running");
        assertEquals(List.of(), w.entered(), "nothing ran");
        FlowWorker.Slot again = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("the room is back though both schedules failed"));
        again.cancel(false);
    }
}
