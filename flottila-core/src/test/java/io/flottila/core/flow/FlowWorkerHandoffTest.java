package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-16a (D-121 (a), D-102 (1), F-30): the local hand-off. A {@link Workers} registry — one per node, handed out by the
 * {@link FlowBuilder} like the targets — gives the intake a {@link FlowWorker.Slot} of the flow's started worker before the insert;
 * the item is written leased to the node with attempts 1 when it heads its key, and the slot runs it after the save: no claim.
 * Without a slot (the worker full, its fence closed, stopped, a backlog known) the item is a plain row and the backlog flag is set:
 * a chain end claims only while that flag is set (A6), the flag is cleared by a claim answering less than it asked; an item left
 * to its lease is claimed at its lease end by one coalesced wake.
 *
 * <p>Fixtures: core's {@link SequentialIntake} over the {@link HoldingMessageStore} and a {@link RecordingWorkQueue} (the store
 * boundary: every claim, leased enqueue, renew and complete counted) over the in-memory queue; a {@link RunGate}; the fake clock and
 * timer. The poll interval is ten minutes and no case advances the clock that far: a claim seen is never the tick's.
 *
 * <p>The API assumed (packet P2-16a, A1/A2; constructors chosen by the test-author where the packet names none):
 * {@code new Workers()}; {@code Optional<FlowWorker.Slot> Workers.reserve(String flow)}; {@code FlowWorker.Slot { Duration lease();
 * void run(WorkQueue.Item leased); void cancel(boolean mayHaveLanded); }}; {@code FlowBuilder.workers(Workers)}, the workers it builds
 * registering there at {@code start()}; {@code new SequentialIntake(MessageStore, WorkQueue, Workers, String workerId)};
 * {@code WorkQueue.enqueueLeased(id, flow, step, key, workerId, lease) → Future<Optional<Item>>}.
 */
@Timeout(30)
class FlowWorkerHandoffTest {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Duration LEASE = Duration.ofSeconds(30);
    /** Longer than any case advances the clock: no claim here is the tick's (P2-16a keeps the tick). */
    static final Duration TICK = Duration.ofMinutes(10);
    static final String NODE = "w1";
    static final String FLOW = "orders";
    /** Throws an {@link Error} on a message's first run (no park catches it: the item is left to its lease); a rerun passes. */
    static final Map<String, Object> CRASH_FIRST = Map.of("type", "crash-first", "name", "crash-first");
    /** Records the run's thread ({@link World#probed}). */
    static final Map<String, Object> PROBE = Map.of("type", "probe", "name", "probe");

    static WorkerPolicy policy() {
        return new WorkerPolicy(LEASE, 5, TICK);
    }

    /** Waits (spinning, never sleeping) until {@code condition} holds; fails after a 5 s bound naming what it waited for. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 5 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** Everything one case runs on. */
    static final class World {
        final FakeRuntime rt = FakeRuntime.create();
        final HoldingMessageStore store = new HoldingMessageStore(new InMemoryMessageStore());
        final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
        final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
        final InMemoryDlq dlq = new InMemoryDlq();
        final RunGate gate = new RunGate();
        final StepFactory crashFirst = ParkingTest.factory("crash-first", s -> Stage.of(e -> {
            if (e.get(Envelope.ATTEMPT).isEmpty()) throw new FlowWorkerTest.Crash("crashed at " + s.name() + " on the first run");
            return e;
        }));
        final Workers workers = new Workers();
        final AtomicBoolean fence = new AtomicBoolean(true);
        final List<FlowWorker> built = new CopyOnWriteArrayList<>();
        final List<FlowWorker.Slot> slots = new CopyOnWriteArrayList<>();
        /** The thread of every run that passed a {@code probe} step: joined, its chain end is done (settle, wake armed, claim issued). */
        final List<Thread> probed = new CopyOnWriteArrayList<>();
        final StepFactory probe = ParkingTest.factory("probe", s -> Stage.of(e -> {
            probed.add(Thread.currentThread());
            return e;
        }));

        FlowBuilder builder() {
            return new FlowBuilder(rt.runtime(), List.of(gate, crashFirst, probe), List.of(BuiltInStepsTest.HEADERS), Map.of())
                    .recording(store, Meter.system()).queue(queue).dlq(dlq).fence(fence::get).workers(workers)
                    .intake(new SequentialIntake(store, queue, workers, NODE));
        }

        static FlowDefinition def(int maxInFlight, List<Map<String, Object>> steps) {
            return FlowDefinition.from(FlowWorkerTest.flow(maxInFlight, steps));
        }

        /** {@code def} keyed by the header {@code x-customer} (partitionBy {@code customer}), the extractKey step first. */
        static FlowDefinition keyed(int maxInFlight, List<Map<String, Object>> steps) {
            List<Map<String, Object>> all = new ArrayList<>();
            all.add(Map.of("type", "extractKey", "name", "key", "language", "header", "key", "customer", "xpath", "x-customer"));
            all.addAll(steps);
            Map<String, Object> doc = FlowWorkerTest.flow(maxInFlight, all);
            doc.put("partitionBy", "customer");
            doc.put("onFailure", "park");
            return FlowDefinition.from(doc);
        }

        FlowWorker worker(FlowBuilder b, FlowDefinition def) {
            FlowWorker w = b.worker(def, NO_FILES, NODE, policy());
            built.add(w);
            return w;
        }

        /** Starts {@code w}; its one start claim answered (A6: start() claims once). */
        FlowWorker started(FlowWorker w) {
            int before = queue.claims().size();
            w.start();
            until(() -> queue.claims().size() == before + 1 && queue.claimsInFlight() == queue.heldClaims(),
                    () -> "the start claim answered; claims " + queue.claims());
            return w;
        }

        Envelope ingress(String customer) {
            Headers h = Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml");
            if (customer != null) h = h.with("x-customer", customer);
            return rt.ingress(FLOW, h, ORDER);
        }

        /** A fresh message through {@code delivery}, accepted. */
        Envelope accept(Delivery delivery, String customer) {
            Envelope in = ingress(customer);
            assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await(), "fixture: accepted");
            return in;
        }

        Envelope accept(Delivery delivery) {
            return accept(delivery, null);
        }

        /** A message whose ingress snapshot is recorded and body stored, nothing queued: what a slot is run with. */
        Envelope recorded() {
            Envelope in = ingress(null);
            store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
            return in;
        }

        /** The queue's row of {@code id}; fails when it has none. */
        WorkQueue.Item row(String id) {
            return rows.snapshot().stream().filter(i -> i.messageId().equals(id)).findFirst()
                    .orElseThrow(() -> new AssertionError("no row of " + id + ": " + rows.snapshot()));
        }

        Optional<String> holder(String id) {
            return rows.leaseHolder(row(id).seq());
        }

        /** The one run of {@code id} at the gate, waited for. */
        RunGate.Run runOf(String id) {
            until(() -> !gate.runsOf(id).isEmpty(), () -> "a run of " + id + " at the gate; runs " + gate.runs() + ", claims " + queue.claims());
            List<RunGate.Run> runs = gate.runsOf(id);
            assertEquals(1, runs.size(), "one run of " + id + ": " + runs);
            return runs.get(0);
        }

        /** Lets {@code id}'s run go and joins its thread: its chain end — and any claim it issues — is done. */
        void finish(String id) {
            RunGate.Run run = runOf(id);
            run.release();
            run.joined();
        }

        List<String> entered() {
            return gate.runs().stream().map(r -> r.messageId).toList();
        }

        void close() {
            slots.forEach(s -> s.cancel(false));
            built.forEach(FlowWorker::stop);
            queue.claimsOk();
            queue.renewsOk();
            queue.releaseHeld();
            gate.open();
        }
    }

    World w;
    FlowWorkerTest.Logs logs;

    static void joined(Thread t) {
        try {
            if (!t.join(Duration.ofSeconds(5))) fail("run thread " + t + " did not end within 5 s: " + java.util.Arrays.toString(t.getStackTrace()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted joining " + t);
        }
    }

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

    @Test
    void aSavedMessageRunsOnItsNodeWithNoClaim() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = w.started(w.worker(b, def));
        assertEquals(1, w.queue.claims().size(), "start() claims once (A6)");

        Envelope in = w.accept(delivery);
        WorkQueue.Item item = w.row(in.id());
        assertEquals(1, item.attempts(), "written leased with attempts 1 by the save itself (A2): " + item);
        assertEquals(0, item.doubts(), "a first hand-out is in no doubt");
        assertEquals(Optional.of(NODE), w.holder(in.id()), "leased to the node that saved it");
        RunGate.Run run = w.runOf(in.id());
        assertEquals(1, w.queue.claims().size(), "the saved message runs with no claim: " + w.queue.claims());

        w.rt.clock().advance(LEASE.dividedBy(3));   // the hand-off run renews its lease at L/3 like a claimed one (A4)
        until(() -> w.queue.renews().size() == 1, () -> "one renewal at L/3; renews " + w.queue.renews());
        assertEquals(new RecordingWorkQueue.RenewCall(item.seq(), 1, LEASE, FakeRuntime.T0.plus(LEASE.dividedBy(3)), true), w.queue.renews().get(0),
                "renewed under the hand-off's attempts");

        run.release();
        run.joined();
        until(() -> w1.unsettled() == 0 && w.rows.size() == 0, () -> "the item settled; rows " + w.rows.snapshot());
        assertEquals(List.of(new RecordingWorkQueue.CompleteCall(item.seq(), 1, true)), w.queue.completeCalls(), "completed under attempts 1");
        assertEquals(1, w.queue.claims().size(), "no backlog known: the chain end claims nothing (A6): " + w.queue.claims());
    }

    @Test
    void aFullWorkerSpillsAndClaimsOnlyAtAChainEnd() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        w.started(w.worker(b, def));

        Envelope m1 = w.accept(delivery);
        w.runOf(m1.id());
        Envelope m2 = w.accept(delivery);   // the worker runs its limit: no slot
        assertEquals(0, w.row(m2.id()).attempts(), "spilled: a plain row, never handed out");
        assertEquals(Optional.empty(), w.holder(m2.id()), "leased to nobody");
        assertEquals(1, w.queue.claims().size(), "no statement while the worker is full: " + w.queue.claims());
        assertEquals(List.of(m1.id()), w.entered(), "the spilled message does not run");

        w.finish(m1.id());   // the chain end: the spill set the backlog flag
        RunGate.Run second = w.runOf(m2.id());
        assertEquals(2, w.queue.claims().size(), "the chain end claimed once: " + w.queue.claims());
        assertEquals(List.of(new WorkQueue.Item(w.row(m2.id()).seq(), m2.id(), FLOW, 1, null, 1)), w.queue.claims().get(1).items,
                "and got the spilled row, its first hand-out");

        second.release();
        second.joined();   // that claim filled its room: the flag stays, the chain end claims again and gets nothing
        assertEquals(3, w.queue.claims().size(), "a claim that filled its room keeps the backlog: " + w.queue.claims());
        assertEquals(List.of(), w.queue.claims().get(2).items, "the drain claim answered less than it asked: the flag is cleared");

        Envelope m3 = w.accept(delivery);
        assertEquals(Optional.of(NODE), w.holder(m3.id()), "the backlog drained: the next save is handed off again");
        w.finish(m3.id());
        assertEquals(3, w.queue.claims().size(), "handed off and finished without a claim: " + w.queue.claims());
        assertEquals(List.of(m1.id(), m2.id(), m3.id()), w.entered());
    }

    @Test
    void aKnownBacklogRefusesTheHandOffUntilAClaimDrainsIt() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = w.worker(b, def);
        Envelope b1 = w.accept(delivery);   // no worker started: no slot, three spilled rows
        Envelope b2 = w.accept(delivery);
        Envelope b3 = w.accept(delivery);
        assertEquals(List.of(0, 0, 0), w.rows.snapshot().stream().map(WorkQueue.Item::attempts).toList(), "a worker not started reserves nothing");

        w.started(w1);   // asks 2, gets b1 and b2: filled, the backlog is known
        w.runOf(b1.id());
        w.runOf(b2.id());
        w.queue.holdNext();
        w.finish(b1.id());   // its chain end claims (held): room 1 free, a claim in flight
        until(() -> w.queue.heldClaims() == 1, () -> "the chain end's claim held; claims " + w.queue.claims());

        Envelope late = w.accept(delivery);
        assertEquals(0, w.row(late.id()).attempts(), "a known backlog refuses the hand-off though the worker has room: the arrival is a plain row");
        assertEquals(Optional.empty(), w.holder(late.id()));

        w.queue.releaseHeld();   // answers b3: the oldest row, never the new arrival
        w.runOf(b3.id());
        w.finish(b2.id());   // its chain end claims the arrival behind b3
        w.runOf(late.id());
        w.finish(b3.id());   // asks 1, gets nothing: the backlog is drained
        w.finish(late.id());
        until(() -> w1.unsettled() == 0 && w.rows.size() == 0, () -> "all settled; rows " + w.rows.snapshot());
        int claims = w.queue.claims().size();
        assertEquals(List.of(), w.queue.claims().get(claims - 1).items, "the last claim answered less than it asked: " + w.queue.claims());

        Envelope after = w.accept(delivery);
        assertEquals(Optional.of(NODE), w.holder(after.id()), "drained: the hand-off is back");
        assertEquals(1, w.row(after.id()).attempts());
        w.finish(after.id());
        assertEquals(claims, w.queue.claims().size(), "no claim for the handed-off message: " + w.queue.claims());
        // b1 and b2 start together from the start claim: either may pass the gate first (P2-16a implementer: a race, not an order)
        assertEquals(java.util.Set.of(b1.id(), b2.id()), java.util.Set.copyOf(w.entered().subList(0, 2)), "the start claim's two first");
        assertEquals(List.of(b3.id(), late.id(), after.id()), w.entered().subList(2, 5), "a new arrival never overtakes a spilled row");
    }

    @Test
    void aReservationTakesRoomUntilItIsCancelled() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        assertEquals(Optional.empty(), w.workers.reserve(FLOW), "no worker registered before start()");
        w.started(w.worker(b, def));

        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("room 1: a slot"));
        w.slots.add(slot);
        assertEquals(LEASE, slot.lease(), "the slot's lease is the flow worker's lease");
        assertEquals(Optional.empty(), w.workers.reserve(FLOW), "room = max − running − reserved: the reservation takes the one slot");
        slot.cancel(false);
        w.slots.remove(slot);
        FlowWorker.Slot again = w.workers.reserve(FLOW).orElseThrow(() -> new AssertionError("the cancel gave the slot back"));
        again.cancel(false);
        assertEquals(Optional.empty(), w.workers.reserve("another-flow"), "a flow without a worker has no slot");

        Envelope in = w.accept(delivery);
        assertEquals(Optional.of(NODE), w.holder(in.id()), "the slot given back serves the next save");
        w.finish(in.id());
    }

    @Test
    void aClosedFenceReservesNothing() throws Exception {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = w.started(w.worker(b, def));

        w.fence.set(false);
        assertEquals(Optional.empty(), w.workers.reserve(FLOW), "a closed fence reserves nothing");
        Envelope fenced = w.accept(delivery);
        assertEquals(0, w.row(fenced.id()).attempts(), "saved as a plain row: a node that cannot prove its lease leases nothing to itself");
        assertEquals(Optional.empty(), w.holder(fenced.id()));
        assertEquals(1, w.queue.claims().size(), "and claims nothing while fenced: " + w.queue.claims());
        assertEquals(List.of(), w.entered());

        w.fence.set(true);
        Future<Integer> round = w1.poll();   // open again: a claim takes the plain row (asks 2, gets 1: the flag cleared)
        w.finish(fenced.id());
        assertEquals(1, (int) round.await(5, TimeUnit.SECONDS));
        until(() -> w1.unsettled() == 0, () -> "settled");
        Envelope open = w.accept(delivery);
        assertEquals(Optional.of(NODE), w.holder(open.id()), "the open fence hands off");
        w.finish(open.id());
    }

    @Test
    void aSlotReservedBeforeTheFenceClosedRunsFenced() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        FlowWorker w1 = w.started(w.worker(b, def));
        Envelope in = w.recorded();
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow();
        WorkQueue.Item leased = w.queue.enqueueLeased(in.id(), FLOW, 1, null, NODE, slot.lease()).await().orElseThrow();

        w.fence.set(false);
        slot.run(leased);   // a reserved slot is never refused (A5)
        w.finish(in.id());
        until(() -> w1.unsettled() == 0, () -> "the fenced run ended");
        assertEquals(List.of(), w.queue.completeCalls(), "run fenced: its settle is skipped under the closed fence");
        assertEquals(1, w.row(in.id()).attempts(), "the row stays, leased as written, for whoever runs it next");
        assertEquals(1, logs.at(java.util.logging.Level.WARNING, "fence is closed").size(), logs.all().toString());
    }

    @Test
    void aReservedSlotRunsAfterStopAndCloseWaitsForIt() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(2, List.of(RunGate.STEP));
        FlowWorker w1 = w.started(w.worker(b, def));
        Envelope in = w.recorded();
        FlowWorker.Slot slot = w.workers.reserve(FLOW).orElseThrow();

        w1.stop();
        assertEquals(Optional.empty(), w.workers.reserve(FLOW), "a stopped worker reserves nothing");
        Future<Void> closed = w1.close();
        assertFalse(closed.isComplete(), "close() waits for the reserved slot");

        WorkQueue.Item leased = w.queue.enqueueLeased(in.id(), FLOW, 1, null, NODE, slot.lease()).await().orElseThrow();
        slot.run(leased);
        RunGate.Run run = w.runOf(in.id());
        assertFalse(closed.isComplete(), "nor while its run goes on");
        run.release();
        run.joined();
        until(closed::isComplete, () -> "close() completes once the slot's run settled");
        assertEquals(List.of(new RecordingWorkQueue.CompleteCall(leased.seq(), 1, true)), w.queue.completeCalls(), "the slot's item ran and completed after stop()");
    }

    @Test
    void anItemLeftToItsLeaseIsClaimedAtItsLeaseEnd() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(3, List.of(PROBE, CRASH_FIRST, RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        FlowWorker w1 = w.started(w.worker(b, def));
        w.gate.open();

        Envelope m1 = w.accept(delivery);   // handed off; its first run crashes: left to its lease
        Envelope m2 = w.accept(delivery);
        until(() -> w.probed.size() == 2, () -> "both first runs started; rows " + w.rows.snapshot());
        for (Thread t : w.probed) joined(t);   // both first runs crashed and were left to their lease, their wakes armed
        assertEquals(0, w1.unsettled());
        assertEquals(List.of(1, 1), w.rows.snapshot().stream().map(WorkQueue.Item::attempts).toList(), "two rows left as handed off");
        assertEquals(1, w.queue.claims().size(), "nothing claims while the leases run: " + w.queue.claims());

        w.rt.clock().advance(LEASE.minusMillis(1));
        assertEquals(1, w.queue.claims().size(), "not before the lease end: " + w.queue.claims());
        w.rt.clock().advance(Duration.ofMillis(1));
        until(() -> w.rows.size() == 0 && w1.unsettled() == 0, () -> "both rerun and completed at the lease end; claims " + w.queue.claims());
        assertEquals(2, w.queue.claims().size(), "one coalesced wake, one claim at the lease end: " + w.queue.claims());
        assertEquals(List.of(2, 2), w.queue.claims().get(1).items.stream().map(WorkQueue.Item::attempts).toList(), "both rows, their second hand-out");
        assertEquals(2, w.entered().size(), "two reruns passed the gate: " + w.entered());
        assertEquals(java.util.Set.of(m1.id(), m2.id()), java.util.Set.copyOf(w.entered()), "each once, on its rerun");
    }

    @Test
    void aSameKeySaveBehindARunningItemIsHandedOnByItsHolder() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.keyed(2, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        w.started(w.worker(b, def));

        Envelope k1 = w.accept(delivery, "K");
        w.runOf(k1.id());
        Envelope k2 = w.accept(delivery, "K");   // a slot, but its key is busy: not leased, the slot given back
        assertEquals(0, w.row(k2.id()).attempts(), "the key's second row is not the head: written unleased");
        assertEquals(Optional.empty(), w.holder(k2.id()));
        Envelope l1 = w.accept(delivery, "L");
        assertEquals(Optional.of(NODE), w.holder(l1.id()), "the busy key's slot was given back: the other key's save takes it");
        w.runOf(l1.id());   // its run at the gate before k1 ends (P2-16a implementer: else k2's hand-on may pass the gate first)

        w.finish(k1.id());   // the holder hands its key on by the fast path
        RunGate.Run next = w.runOf(k2.id());
        assertEquals(1, w.queue.claims().size(), "handed on without a claim: " + w.queue.claims());
        assertEquals(1, w.row(k2.id()).attempts());
        next.release();
        next.joined();
        w.finish(l1.id());
        assertEquals(List.of(k1.id(), l1.id(), k2.id()), w.entered());
    }

    @Test
    void aFailedSaveGivesItsSlotBack() {
        FlowBuilder b = w.builder();
        FlowDefinition def = World.def(1, List.of(RunGate.STEP));
        Delivery delivery = b.delivery(def, NO_FILES);
        w.started(w.worker(b, def));

        w.queue.failNextEnqueue(new IllegalStateException("the insert refused"));   // the save fails at the insert, after its reservation
        Envelope refused = w.ingress(null);
        Future<Ack> answer = delivery.apply(refused);
        until(answer::isComplete, () -> "the failed save answered");
        assertNotEquals(Optional.of(new Ack.Accepted(refused.id())), Optional.ofNullable(answer.succeeded() ? answer.result() : null), "not accepted");
        assertEquals(0, w.rows.size(), "nothing queued");

        Envelope in = w.accept(delivery);
        assertEquals(Optional.of(NODE), w.holder(in.id()), "the failed save's slot was given back (cancel): the one slot serves this save");
        w.finish(in.id());
        assertEquals(1, w.queue.claims().size(), w.queue.claims().toString());
    }
}
