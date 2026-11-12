package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.node.FencedException;
import io.flottila.core.node.InMemoryMembership;
import io.flottila.core.node.Membership;
import io.flottila.core.node.NodeLease;
import io.flottila.core.node.ScriptedMembership;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static io.flottila.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a acceptance 4 and 5 (D-101 (2), D-055, D-103 Q-7): the worker under the node's fence. The fence is checked by the
 * at-least-once {@code to} before the producer's send and by the worker before complete, completeAndTakeNext and hold; a
 * fenced send fails with {@code FencedException}, which is never retried and never parked, and leaves the item unsettled for
 * whoever runs it next; sync and at-most-once runs are not fenced. A node killed mid-send is taken over once its node lease is
 * released: the rerun is in doubt, carries the same message id, counts an attempt and is parked past maxAttempts.
 *
 * <p>The API assumed (P2-15a, test-author): {@code FlowBuilder.fence(BooleanSupplier)} — the node's fence, checked by the
 * workers built from then on (no call: always open); {@code io.flottila.core.node.FencedException} (a RuntimeException);
 * {@code NodeLease}, {@code InMemoryMembership} and {@code InMemoryWorkQueue(Clock, InMemoryMembership)} as in
 * {@code NodeLeaseTest} and {@code InMemoryWorkQueueTest}; {@link WorkQueue.Item#doubts()}. A worker id is its node id.
 *
 * <p>Review r1 decisions 3 and 4: a step's failure in a worker's pipeline is parked only while the fence is open — closed, the
 * park fails with a {@code FencedException} and writes no DLQ row; and a worker whose fence is closed claims nothing. The
 * settle-side cases therefore close the fence after the claim was issued.
 */
@Timeout(60)
class FlowWorkerFenceTest {

    static final Duration LEASE = Duration.ofSeconds(30);        // the item lease (WorkerPolicy)
    static final Duration NODE_LEASE = Duration.ofSeconds(30);   // L
    static final Duration RENEW = Duration.ofSeconds(5);         // R

    final List<CountDownLatch> holds = new CopyOnWriteArrayList<>();
    final List<NodeLease> leases = new CopyOnWriteArrayList<>();
    final List<World> worlds = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        holds.forEach(CountDownLatch::countDown);   // no send stays held past its case
        leases.forEach(NodeLease::close);
        worlds.forEach(w -> { w.gate.open(); w.workers.forEach(FlowWorker::stop); });
    }

    /** One shared database: store, membership, queue and DLQ; the partner; the workers built over it. */
    final class World {
        final FakeRuntime rt = FakeRuntime.create();
        final RecordingComponent partner = new RecordingComponent("mock");
        final InMemoryMessageStore store = new InMemoryMessageStore();
        final InMemoryMembership members = new InMemoryMembership(rt.clock());
        final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock(), members);
        final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
        final InMemoryDlq parked = new InMemoryDlq();
        final FailingDlq dlq = new FailingDlq(parked);
        final RunGate gate = new RunGate();
        final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
        final LongSupplier nanos = () -> Duration.between(FakeRuntime.T0, rt.clock().instant()).toNanos();

        World() {
            rt.withComponent(partner);
            worlds.add(this);
        }

        FlowBuilder builder(Runtime runtime, BooleanSupplier fence) {
            return new FlowBuilder(runtime, List.of(gate), List.of(BuiltInStepsTest.HEADERS), endpoints)
                    .recording(store, Meter.system()).queue(queue).dlq(dlq).fence(fence);
        }

        FlowWorker worker(FlowBuilder b, FlowDefinition def, String node, int maxAttempts) {
            FlowWorker w = FlowWorkerTest.policy(LEASE, maxAttempts).worker(b, def, node);
            workers.add(w);
            return w;
        }

        NodeLease lease(String node, Membership membership, io.flottila.api.Timer timer, AtomicInteger halts) throws Exception {
            NodeLease lease = new NodeLease(node, membership, NODE_LEASE, RENEW, nanos, timer, halts::incrementAndGet);
            leases.add(lease);
            lease.start().await(5, SECONDS);
            return lease;
        }

        Runtime runtimeWith(io.flottila.api.Timer timer) {
            Runtime r = rt.runtime();
            return new Runtime(r.clock(), timer, r.ids(), r.blobs(), r.hasher(), r.components());
        }

        Envelope accept(Delivery delivery) throws Exception {
            Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
            assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await(5, SECONDS));
            return in;
        }

        List<String> sentIds() { return partner.sent().stream().map(Envelope::id).toList(); }

        List<Parked> dlqRows() { return parked.list(Optional.empty(), Page.MAX_LIMIT).await().items(); }

        List<Long> queued() { return rows.snapshot().stream().map(WorkQueue.Item::seq).toList(); }

        /**
         * Advances past an item's lease ({@link #advance}) and waits for what {@code worker}'s own lease-end wake (D-121 A6)
         * claims there — not a poll of the test: its claim answered and every run it started settled.
         */
        void wokenPast(Duration total, FlowWorker worker, String node) {
            int before = queue.claimsBy(node).size();
            advance(total);
            until(() -> queue.claimsBy(node).size() > before && queue.claimsInFlight() == 0 && worker.unsettled() == 0,
                    () -> "the lease-end wake of " + node + " claimed and its runs settled: claims " + queue.claims() + ", running " + worker.unsettled());
        }

        /** Advances the fake clock a second at a time up to {@code total}, each timer task firing at its own second. */
        void advance(Duration total) {
            for (Duration left = total; !left.isZero() && !left.isNegative(); left = left.minusSeconds(1)) {
                rt.clock().advance(left.compareTo(Duration.ofSeconds(1)) < 0 ? left : Duration.ofSeconds(1));
            }
        }
    }

    static Map<String, Object> atLeastOnce(List<Map<String, Object>> steps) {
        return FlowWorkerTest.flow(4, steps);
    }

    @Test
    void aPausedThenResumedNodeSendsNothingAfterItsFence() throws Exception {
        World w = new World();
        ScriptedMembership scripted = new ScriptedMembership(w.rt.clock());
        AtomicInteger halts = new AtomicInteger();
        NodeLease lease = w.lease("n1", scripted, w.rt.timer(), halts);   // joined at T0: open until T0+15 s
        FlowBuilder b = w.builder(w.rt.runtime(), lease);
        FlowDefinition def = FlowDefinition.from(atLeastOnce(List.of(RunGate.STEP, TO_PARTNER)));
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope first = w.accept(delivery);
        Envelope second = w.accept(delivery);
        FlowWorker worker = w.worker(b, def, "n1", 5);

        Future<Integer> round = worker.poll();
        until(() -> w.gate.entered() == 2, () -> "both runs at the gate: " + w.gate.runs());
        w.gate.runsOf(first.id()).get(0).release();
        w.partner.awaitSent(1);
        assertEquals(List.of(first.id()), w.sentIds(), "witness: with the fence open the run sends");
        until(() -> w.queue.completes().size() == 1, () -> "the first item completed: " + w.queue.completeCalls());

        scripted.mode(ScriptedMembership.Mode.HANG);   // the node pauses: its renews reach nobody and come back to nobody
        w.advance(Duration.ofSeconds(15));
        assertFalse(lease.getAsBoolean(), "fixture: the fence closed at T0+15 s, s + L/2 of the join");

        w.gate.runsOf(second.id()).get(0).release();   // the run resumes after the fence
        assertEquals(2, round.await(10, SECONDS), "the round ends: the fenced run is done with");
        assertEquals(List.of(first.id()), w.sentIds(), "nothing sent after the fence: the second message never reached the partner");
        long secondSeq = w.queue.claims().get(0).items.stream().filter(i -> i.messageId().equals(second.id())).findFirst().orElseThrow().seq();
        assertEquals(List.of(secondSeq), w.queued(), "its item left unsettled: not completed");
        assertEquals(1, w.queue.completes().size(), "only the first item was completed: " + w.queue.completeCalls());
        assertEquals(List.of(), w.queue.holds(), "nor held");
        assertEquals(List.of(), w.dlqRows(), "nor parked: a fenced send is no failure of the message");

        // resumed: the renews sent during the pause answer now — a peer released the node meanwhile
        List<ScriptedMembership.Call> calls = scripted.renews();
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).pending() != null) scripted.answer(i, false);
        assertEquals(1, halts.get(), "the superseded node halts");
        assertFalse(lease.getAsBoolean());
        assertEquals(List.of(first.id()), w.sentIds(), "and still sends nothing");
    }

    @Test
    void aFencedWorkerCompletesHoldsAndParksNothing() throws Exception {
        // complete, the same-key fast path and a fenced send (retryable: never retried, never parked)
        World w = new World();
        AtomicBoolean open = new AtomicBoolean(true);
        FlowBuilder b = w.builder(w.rt.runtime(), open::get);
        Map<String, Object> doc = atLeastOnce(List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner", "retryable", true)));
        doc.put("retry", Map.of("attempts", 2, "initial", "1s", "max", "1s"));
        FlowDefinition def = FlowDefinition.from(doc);
        Envelope sent = w.accept(b.delivery(def, NO_FILES));
        w.queue.enqueue("ghost-1", "orders", 1, null).await();   // no ingress snapshot: a ghost, completed when run
        w.queue.enqueue("ghost-2", "orders", 1, "K").await();    // keyed: settled by the fast path
        w.queue.enqueue("ghost-3", "orders", 1, "K").await();    // what that fast path would take
        FlowWorker worker = w.worker(b, def, "n1", 5);

        w.queue.holdNext();   // the claim is issued with the fence open (a closed fence claims nothing) and answers once it closed
        Future<Integer> fencedRound = worker.poll();
        until(() -> w.queue.heldClaims() == 1, () -> "the claim issued: " + w.queue.claims());
        open.set(false);
        w.queue.releaseHeld();
        assertEquals(3, fencedRound.await(10, SECONDS), "the round ends at once, the clock unmoved: the fenced send waited for no retry backoff");
        assertEquals(List.of(), w.partner.sent(), "nothing sent");
        assertEquals(List.of(), w.queue.completeCalls(), "neither complete nor completeAndTakeNext asked while fenced");
        assertEquals(List.of(), w.queue.holds());
        assertEquals(List.of(), w.dlqRows(), "the fenced send is not parked");
        assertEquals(4, w.rows.size(), "every item left unsettled");

        // witness: the same items, the fence open, once their leases ran out
        open.set(true);
        w.advance(LEASE.plusSeconds(1));
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (w.rows.size() > 0 && System.nanoTime() < deadline) worker.poll().await(10, SECONDS);
        assertEquals(0, w.rows.size(), "with the fence open every item is settled: " + w.rows.snapshot());
        assertEquals(List.of(sent.id()), w.sentIds(), "the message sent once");
        assertTrue(w.queue.completeCalls().size() >= 3, "complete and the fast path asked: " + w.queue.completeCalls());
        assertEquals(List.of(), w.dlqRows());

        // hold: an item past maxAttempts whose park fails is held — not while fenced
        World h = new World();
        AtomicBoolean hOpen = new AtomicBoolean(true);
        FlowBuilder hb = h.builder(h.rt.runtime(), hOpen::get);
        FlowDefinition hdef = FlowDefinition.from(atLeastOnce(List.of(TO_PARTNER)));
        Envelope x = h.accept(hb.delivery(hdef, NO_FILES));
        assertEquals(1, h.rows.claim("crashed", "orders", 1, LEASE).await().size(), "fixture: a first hand-out to a node that is gone");
        h.advance(LEASE.plusSeconds(1));
        h.dlq.failParks(new IllegalStateException("the DLQ is down"));
        FlowWorker hw = h.worker(hb, hdef, "n1", 1);

        h.queue.holdNext();   // as above: the claim issued with the fence open, answered once it closed
        Future<Integer> hRound = hw.poll();
        until(() -> h.queue.heldClaims() == 1, () -> "the claim issued: " + h.queue.claims());
        hOpen.set(false);
        h.queue.releaseHeld();
        assertEquals(1, hRound.await(10, SECONDS), "fixture: the exhausted item reached the worker");
        assertEquals(List.of(), h.queue.holds(), "past maxAttempts, the park failed: hold not asked while fenced");
        assertEquals(List.of(), h.rows.held(Optional.empty(), 10).await().items(), "nothing held");
        assertEquals(List.of(), h.partner.sent(), "an exhausted item never runs");

        hOpen.set(true);   // witness: the fence open, the next hand-out — the lease-end wake's — is held
        h.wokenPast(LEASE.plusSeconds(1), hw, "n1");
        assertEquals(1, h.queue.holds().size(), "with the fence open the hold is asked: " + h.queue.holds());
        assertEquals(List.of(x.id()), h.rows.held(Optional.empty(), 10).await().items().stream().map(held -> held.item().messageId()).toList());
    }

    @Test
    void aNodeKilledMidSendIsRerunInDoubtWithTheSameIdAndParkedPastMaxAttempts() throws Exception {
        World w = new World();
        FlowDefinition def = FlowDefinition.from(atLeastOnce(List.of(TO_PARTNER)));
        List<FlowWorkerTest.KillableTimer> timers = new ArrayList<>();
        List<FlowWorker> workers = new ArrayList<>();
        Map<String, AtomicInteger> halts = new HashMap<>();
        Delivery delivery = null;
        for (String node : List.of("n1", "n2", "n3")) {
            FlowWorkerTest.KillableTimer timer = new FlowWorkerTest.KillableTimer(w.rt.timer());
            halts.put(node, new AtomicInteger());
            NodeLease lease = w.lease(node, w.members, timer, halts.get(node));
            FlowBuilder b = w.builder(w.runtimeWith(timer), lease);
            if (delivery == null) delivery = b.delivery(def, NO_FILES);
            timers.add(timer);
            workers.add(w.worker(b, def, node, 2));
        }
        Envelope in = w.accept(delivery);

        holds.add(w.partner.holdNext(1));   // n1's send reaches the partner, its answer never comes back
        workers.get(0).poll();
        w.partner.awaitSent(1);
        timers.get(0).kill();               // n1 dies mid-send: no renew of its node lease or of its item lease from now on

        holds.add(w.partner.holdNext(1));
        takeOver(w, workers.get(1), "n2");
        w.partner.awaitSent(2);
        WorkQueue.Item byN2 = w.queue.claimsBy("n2").stream().filter(c -> c.items != null).flatMap(c -> c.items.stream()).findFirst().orElseThrow();
        assertEquals(2, byN2.attempts(), "the takeover counts an attempt (D-055)");
        assertEquals(1, byN2.doubts(), "n1 died mid-send: the rerun is in doubt");
        assertEquals(List.of(in.id(), in.id()), w.sentIds(), "the rerun sends with the same message id");
        timers.get(1).kill();               // n2 dies mid-send too

        takeOver(w, workers.get(2), "n3");
        WorkQueue.Item byN3 = w.queue.claimsBy("n3").stream().filter(c -> c.items != null).flatMap(c -> c.items.stream()).findFirst().orElseThrow();
        assertEquals(3, byN3.attempts());
        assertEquals(2, byN3.doubts(), "in doubt a second time");
        until(() -> w.rows.size() == 0, () -> "past maxAttempts the item is parked and completed: " + w.rows.snapshot());
        List<Parked> dlq = w.dlqRows();
        assertEquals(List.of(in.id()), dlq.stream().map(Parked::messageId).toList(), "parked past maxAttempts 2 under the same id");
        assertTrue(dlq.get(0).error().contains("AttemptsExhaustedException"), dlq.get(0).error());
        assertEquals(2, w.partner.sent().size(), "no third send: the third hand-out is parked, not run");
        assertEquals(0, halts.get("n3").get(), "the survivor runs on");
    }

    /** Advances a second at a time, polling {@code worker} after each, until it was handed the item (bounded by 3 node leases). */
    void takeOver(World w, FlowWorker worker, String node) throws Exception {
        Duration waited = Duration.ZERO;
        while (w.queue.handedOutTo(node) == 0) {
            if (waited.compareTo(NODE_LEASE.multipliedBy(3)) > 0) {
                throw new AssertionError(node + " never took the item over within " + waited + ": " + w.rows.snapshot() + "; claims " + w.queue.claims());
            }
            w.advance(Duration.ofSeconds(1));
            waited = waited.plusSeconds(1);
            worker.poll();
            until(() -> w.queue.claimsInFlight() == 0, () -> "the claim of " + node + " answered");
        }
    }

    @Test
    void aStepFailureWhileFencedIsNotParkedAndWithTheFenceOpenIsParkedOnce() throws Exception {
        World w = new World();
        AtomicBoolean open = new AtomicBoolean(true);
        List<RuntimeException> seen = new CopyOnWriteArrayList<>();   // what the steps after the probe threw at it
        StepFactory probe = ParkingTest.factory("probe", s -> (e, rest) -> {
            try {
                return rest.apply(e);
            } catch (RuntimeException failure) {
                seen.add(failure);
                throw failure;
            }
        });
        StepFactory boom = ParkingTest.factory("boom", s -> Stage.of(e -> { throw new IllegalStateException("boom at " + s.name()); }));
        FlowBuilder b = new FlowBuilder(w.rt.runtime(), List.of(w.gate, probe, boom), List.of(BuiltInStepsTest.HEADERS), w.endpoints)
                .recording(w.store, Meter.system()).queue(w.queue).dlq(w.dlq).fence(open::get);
        FlowDefinition def = FlowDefinition.from(atLeastOnce(List.of(RunGate.STEP, Map.of("type", "probe", "name", "probe"),
                Map.of("type", "boom", "name", "x"))));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker worker = w.worker(b, def, "n1", 2);   // CORE-14a: count 1, so the second hand-out's failure is its last and parks

        Future<Integer> round = worker.poll();   // claimed with the fence open
        until(() -> w.gate.entered() == 1, () -> "the run at the gate: " + w.gate.runs());
        open.set(false);                          // the fence closes before the step fails
        w.gate.runsOf(in.id()).get(0).release();
        assertEquals(1, round.await(10, SECONDS));
        assertEquals(1, seen.size(), "the failing step ran: " + seen);
        assertInstanceOf(IllegalStateException.class, seen.get(0), "CORE-14a P6: no per-step park, the step's failure reaches the worker as it is: " + seen.get(0));
        assertEquals(List.of(), w.dlqRows(), "no DLQ row from a closed fence");
        assertEquals(List.of(), w.queue.completeCalls(), "nor a complete");
        assertEquals(List.of(), w.queue.holds(), "nor a hold");
        assertEquals(1, w.rows.size(), "the item left unsettled for whoever runs it next");

        // witness: the fence open, the next hand-out's failure is parked — once
        open.set(true);
        w.gate.open();
        w.wokenPast(LEASE.plusSeconds(1), worker, "n1");   // the item handed out again, by the lease-end wake
        assertEquals(List.of(1, 2), w.queue.claimsBy("n1").stream().filter(c -> c.items != null).flatMap(c -> c.items.stream())
                .map(WorkQueue.Item::attempts).toList(), "the item handed out again");
        assertEquals(2, seen.size(), "the failing step ran again: " + seen);
        assertInstanceOf(IllegalStateException.class, seen.get(1), "the step's failure as it is: the worker parks it: " + seen.get(1));
        assertEquals(List.of(in.id()), w.dlqRows().stream().map(Parked::messageId).toList(), "with the fence open the last hand-out's failure is parked: one DLQ row");
        assertEquals(1, w.dlqRows().get(0).step(), "the ingress, at step 1 (CORE-14a P6)");
        assertEquals(Optional.of(2), w.dlqRows().get(0).props().get(Envelope.ATTEMPT), "with the attempt it failed at");
        assertEquals(0, w.rows.size(), "and the item completed");
    }

    @Test
    void aWorkerWhoseFenceIsClosedClaimsNothingUntilItReopens() throws Exception {
        World w = new World();
        AtomicBoolean open = new AtomicBoolean(false);
        FlowBuilder b = w.builder(w.rt.runtime(), open::get);
        FlowDefinition def = FlowDefinition.from(atLeastOnce(List.of(TO_PARTNER)));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker worker = w.worker(b, def, "n1", 5);

        assertEquals(0, worker.poll().await(10, SECONDS), "a worker whose fence is closed claims nothing");
        assertEquals(List.of(), w.queue.claims(), "no TAKE issued while the fence is closed");
        WorkQueue.Item queued = w.rows.snapshot().get(0);
        assertEquals(0, queued.attempts(), "the item never handed out");
        assertEquals(Optional.empty(), w.rows.leaseHolder(queued.seq()), "nor leased");
        assertEquals(List.of(), w.partner.sent());

        open.set(true);   // witness: the same worker, the fence open
        assertEquals(1, worker.poll().await(10, SECONDS), "with the fence open the worker claims");
        assertEquals(1, w.queue.claimsBy("n1").stream().filter(c -> c.items != null && !c.items.isEmpty()).count(),
                "one TAKE hands the item (the chain's end polls once more and takes nothing): " + w.queue.claims());
        assertEquals(List.of(in.id()), w.sentIds());
        assertEquals(0, w.rows.size(), "and completes");
    }

    @Test
    void syncAndAtMostOnceRunsAreNotFenced() throws Exception {
        World w = new World();
        FlowBuilder b = w.builder(w.rt.runtime(), () -> false);   // a fence that never opens
        Envelope s = w.rt.ingress("orders", Headers.empty(), Buffer.buffer("<sync/>"));
        Ack reply = b.delivery(FlowDefinition.from(DeliveryTest.flow("sync", List.of(TO_PARTNER))), NO_FILES).apply(s).await(10, SECONDS);
        assertInstanceOf(Ack.Reply.class, reply, "a sync run sends and answers under a closed fence: " + reply);
        assertEquals(List.of(s.id()), w.sentIds());

        Envelope m = w.rt.ingress("orders", Headers.empty(), Buffer.buffer("<amo/>"));
        Ack accepted = b.delivery(FlowDefinition.from(DeliveryTest.flow("at-most-once", List.of(TO_PARTNER))), NO_FILES).apply(m).await(10, SECONDS);
        assertEquals(new Ack.Accepted(m.id()), accepted);
        w.partner.awaitSent(2);
        assertEquals(List.of(s.id(), m.id()), w.sentIds(), "an at-most-once run sends under a closed fence");
    }
}
