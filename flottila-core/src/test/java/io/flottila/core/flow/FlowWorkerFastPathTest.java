package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-01 (D-075): the worker's same-key fast path. When a run of a keyed item ends with its item to complete (it succeeded, or it
 * was parked: park-and-continue [A]) and the worker is not stopped and the chain is shorter than {@link WorkerPolicy#maxChain()},
 * the worker settles with {@code completeAndTakeNext}: {@code done(next)} runs the next item of the key at once — in the same
 * slot (its seq replaces the finished one among the running items), under a lease of its own that renews, on a fresh virtual
 * thread, chain + 1 — without a claim; {@code done(empty)} ends the chain; {@code STALE} is one WARNING, counted in
 * {@code staleSettles}, nothing taken; a failed answer fails the round. Otherwise (no key, an item left to its lease or held,
 * stopped, the chain at its bound) it settles as before with a plain {@code complete}. A next item past
 * {@link WorkerPolicy#maxAttempts()} is parked, not run, by the usual path. The one poll after an item is done moves to the end
 * of its chain; {@code poll()}'s future and {@code close()} wait for the chains they started.
 *
 * <p>Chain counting (the reading of D-075 these cases pin): the claimed item is chain 0; the fast path is taken while chain &lt;
 * maxChain, so {@code maxChain} is the number of items a worker takes by the fast path after the one it claimed (0: none).
 *
 * <p>Fixtures: the in-memory queue under a {@link RecordingWorkQueue}, under a {@link TakeQueue} that keys the items the test
 * names and logs every claim, complete and fast path in answer order; a {@link RunGate} holding each run; a {@link ClosingPartner};
 * the fake clock and timer. Items are numbered by the queue in enqueue order: seq 1, 2, … .
 */
@Timeout(30)
class FlowWorkerFastPathTest {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration TICK = Duration.ofSeconds(1);
    static final Map<String, Object> OUTCOME = Map.of("type", "outcome", "name", "outcome");
    static final Map<String, Object> TO_PARTNER = DeliveryTest.TO_PARTNER;
    /** What the cases here run: the gate, the outcome the message's header asks for, the partner. */
    static final List<Map<String, Object>> STEPS = List.of(RunGate.STEP, OUTCOME, TO_PARTNER);

    static WorkerPolicy policy(int maxAttempts, int maxChain) {
        return new WorkerPolicy(LEASE, maxAttempts, TICK, maxChain);
    }

    /** Waits (spinning, never sleeping) until {@code condition} holds; fails after a 5 s bound naming what it waited for. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 5 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    static String at(WorkQueue.Item i) {
        return i.seq() + "@" + i.attempts();
    }

    /**
     * The queue a fast-path case runs on, a decorator at the store boundary over a {@link RecordingWorkQueue}: it gives the items of
     * the messages the test keyed their key at enqueue ({@link #key}), and logs, in answer order, every claim
     * ({@code claim w1 -> [1@1]}), complete ({@code complete 1@1 -> true}) and fast path ({@code take 1@1 -> 2@1 | none | STALE |
     * failed}); renewals go to {@link #renews()}. Staging: the next fast path fails ({@link #failNextTake}); the next fast path that
     * hands out an item hands it out with {@code extra} more attempts than the delegate counted ({@link #bumpNextTake}) — the row as
     * a queue would hand it out after earlier hand-outs — and every later call for that row is translated back; the next fast
     * path reaches the queue at once (it completes and leases there) but its answer reaches the worker, and is logged, only once
     * the promise {@link #holdNextTake} returned completes ({@link #takesHeld()} counts the held calls that reached the queue).
     */
    static final class TakeQueue implements WorkQueue {
        private final RecordingWorkQueue inner;
        private final Map<String, String> keys = new ConcurrentHashMap<>();
        private final Map<Long, Integer> bumped = new ConcurrentHashMap<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<String> renews = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failNextTake = new AtomicReference<>();
        private final AtomicInteger bumpNextTake = new AtomicInteger();
        private final AtomicReference<Promise<Void>> holdNextTake = new AtomicReference<>();
        private final List<Promise<Void>> takeHolds = new CopyOnWriteArrayList<>();
        private final AtomicInteger takesHeld = new AtomicInteger();

        TakeQueue(RecordingWorkQueue inner) {
            this.inner = inner;
        }

        void key(String messageId, String key) { keys.put(messageId, key); }

        String keyOf(String messageId) { return keys.get(messageId); }

        void failNextTake(Throwable cause) { failNextTake.set(cause); }

        void bumpNextTake(int extra) { bumpNextTake.set(extra); }

        /** Holds the answer of the next fast path (armed before it is asked) until the returned promise completes. */
        Promise<Void> holdNextTake() {
            Promise<Void> hold = Promise.promise();
            takeHolds.add(hold);
            holdNextTake.set(hold);
            return hold;
        }

        /** Fast paths whose answer was held, counted once the queue itself has answered them. */
        int takesHeld() { return takesHeld.get(); }

        /** Lets every held fast-path answer through (teardown). */
        void releaseTakes() { takeHolds.forEach(Promise::tryComplete); }

        List<String> events() { return List.copyOf(events); }

        /** The completes and fast paths only, in answer order. */
        List<String> settles() { return events.stream().filter(e -> e.startsWith("complete") || e.startsWith("take")).toList(); }

        List<String> takes() { return events.stream().filter(e -> e.startsWith("take")).toList(); }

        List<String> claims() { return events.stream().filter(e -> e.startsWith("claim")).toList(); }

        List<String> renews() { return List.copyOf(renews); }

        private int real(long seq, int attempts) { return attempts - bumped.getOrDefault(seq, 0); }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
            return inner.enqueue(messageId, flow, step, keys.getOrDefault(messageId, partitionKey));
        }

        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
            return inner.claim(workerId, flow, max, lease).andThen(r -> events.add("claim " + workerId + " -> "
                    + (r.succeeded() ? r.result().stream().map(FlowWorkerFastPathTest::at).toList() : "failed")));
        }

        @Override public Future<Boolean> complete(long seq, int attempts) {
            return inner.complete(seq, real(seq, attempts)).andThen(r -> events.add("complete " + seq + "@" + attempts + " -> "
                    + (r.succeeded() ? r.result() : "failed")));
        }

        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            Throwable failure = failNextTake.getAndSet(null);
            if (failure != null) {
                events.add("take " + seq + "@" + attempts + " -> failed");
                return Future.failedFuture(failure);
            }
            Promise<Void> hold = holdNextTake.getAndSet(null);
            Future<Completion> answered = inner.completeAndTakeNext(seq, real(seq, attempts), workerId, lease);
            if (hold != null) {
                answered = answered.andThen(r -> takesHeld.incrementAndGet()).compose(c -> hold.future().map(c));
            }
            return answered.map(c -> {
                Completion out = c;
                if (c.next().isPresent()) {
                    int extra = bumpNextTake.getAndSet(0);
                    if (extra > 0) {
                        Item n = c.next().get();
                        bumped.put(n.seq(), extra);
                        out = Completion.done(Optional.of(new Item(n.seq(), n.messageId(), n.flow(), n.step(), n.partitionKey(), n.attempts() + extra)));
                    }
                }
                events.add("take " + seq + "@" + attempts + " -> "
                        + (!out.completed() ? "STALE" : out.next().map(FlowWorkerFastPathTest::at).orElse("none")));
                return out;
            });
        }

        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) {
            return inner.renew(seq, real(seq, attempts), lease).andThen(r -> renews.add("renew " + seq + "@" + attempts + " -> "
                    + (r.succeeded() ? r.result() : "failed")));
        }

        @Override public Future<Boolean> hold(long seq, int attempts) {
            return inner.hold(seq, real(seq, attempts)).andThen(r -> events.add("hold " + seq + "@" + attempts + " -> "
                    + (r.succeeded() ? r.result() : "failed")));
        }

        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return inner.held(after, limit); }

        @Override public Future<Boolean> release(long seq) { return inner.release(seq); }

        @Override public Future<Boolean> hasItems(String flow) { return inner.hasItems(flow); }
    }

    /** What a crashed step throws: an {@link Error}, past every handler of the pipeline — the item is left to its lease. */
    static final class Crash extends Error {
        Crash(String message) { super(message); }
    }

    /**
     * Everything one case (or one try of a property) runs on. Flow {@code orders}; the {@code outcome} step does what the
     * message's {@code outcome} header says: {@code park} (a step failure: parked, the item completed), {@code crash} (an Error:
     * the item left to its lease), {@code crash-once} (an Error on the first run only), else nothing.
     */
    static final class Line {
        final FakeRuntime rt = FakeRuntime.create();
        final ClosingPartner partner = new ClosingPartner();
        final InMemoryMessageStore store = new InMemoryMessageStore();
        final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
        final RecordingWorkQueue recorded = new RecordingWorkQueue(rows, rt.clock());
        final TakeQueue queue = new TakeQueue(recorded);
        final InMemoryDlq dlq = new InMemoryDlq();
        final RunGate gate = new RunGate();
        final StepFactory outcome = ParkingTest.factory("outcome", s -> Stage.of(e -> {
            String o = e.header("outcome").orElse("pass");
            if (o.equals("park")) throw new IllegalStateException("refused at " + s.name());
            if (o.equals("crash")) throw new Crash("crashed at " + s.name());
            if (o.equals("crash-once") && e.get(Envelope.ATTEMPT).isEmpty()) throw new Crash("crashed at " + s.name() + " on the first run");
            return e;
        }));
        final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
        final List<StepFactory> extra = new CopyOnWriteArrayList<>();

        Line() {
            rt.withComponent(partner);
        }

        FlowBuilder builder() {
            List<StepFactory> factories = new ArrayList<>(List.of(gate, outcome));
            factories.addAll(extra);
            return new FlowBuilder(rt.runtime(), factories, List.of(BuiltInStepsTest.HEADERS), endpoints)
                    .recording(store, Meter.system()).queue(queue).dlq(dlq);
        }

        /** At-least-once flow {@code orders} with {@code maxInFlight} (the worker's max) and these steps. */
        static FlowDefinition def(int maxInFlight, List<Map<String, Object>> steps) {
            return FlowDefinition.from(FlowWorkerTest.flow(maxInFlight, steps));
        }

        FlowWorker worker(FlowBuilder b, FlowDefinition def, String id, WorkerPolicy policy) {
            FlowWorker worker = b.worker(def, NO_FILES, id, policy);
            workers.add(worker);
            return worker;
        }

        /** A message accepted through {@code delivery} whose item gets {@code key} (null: none), with an {@code outcome} header (null: none). */
        Envelope accept(Delivery delivery, String key, String outcome) {
            Headers headers = Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml");
            if (outcome != null) headers = headers.with("outcome", outcome);
            Envelope in = rt.ingress("orders", headers, ORDER);
            if (key != null) queue.key(in.id(), key);
            assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await(), "fixture: accepted");
            return in;
        }

        List<String> sentIds() {
            return partner.partner().sentTo("partner").stream().map(Envelope::id).toList();
        }

        /** The attempts the queue counted for {@code seq} (0: never handed out); fails when the row is gone. */
        int attemptsOf(long seq) {
            return rows.snapshot().stream().filter(i -> i.seq() == seq).findFirst().map(WorkQueue.Item::attempts)
                    .orElseThrow(() -> new AssertionError("no row " + seq + ": " + rows.snapshot()));
        }

        /** Every claim answered and every item handed out has entered the gate. */
        void settle() {
            until(() -> recorded.claimsInFlight() == 0 && gate.entered() == recorded.handedOutTotal(),
                    () -> "claims in flight " + recorded.claimsInFlight() + ", runs entered " + gate.entered() + " of " + recorded.handedOutTotal()
                            + " handed out; " + queue.events());
        }

        void close() {
            workers.forEach(FlowWorker::stop);
            recorded.claimsOk();
            recorded.renewsOk();
            recorded.releaseHeld();
            queue.releaseTakes();
            gate.open();
        }
    }

    Line l;
    FlowWorkerTest.Logs logs;

    @BeforeEach
    void setUp() {
        l = new Line();
        logs = FlowWorkerTest.Logs.attach();
    }

    @AfterEach
    void tearDown() {
        logs.detach();
        l.close();
    }

    RunGate.Run runOf(Envelope in) {
        until(() -> !l.gate.runsOf(in.id()).isEmpty(), () -> "the run of " + in.id() + " at the gate; " + l.queue.events());
        List<RunGate.Run> runs = l.gate.runsOf(in.id());
        assertEquals(1, runs.size(), "one run of " + in.id() + ": " + runs);
        return runs.get(0);
    }

    // --- the fast path

    /**
     * A1 and A2 share key A: the claim hands out A1 only. A1's run ends: one call completes A1 and leases A2 to the same worker —
     * no claim in between, no plain complete — and A2 runs under a lease of its own, renewed on the timer (A1's ended with its
     * run). The round waits for the chain it started; once A2 is done ({@code done(empty)}), the chain's end polls once.
     */
    @Test
    void keyedSuccessorTakenByFastPathWithoutAClaim() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", null);
        Envelope a2 = l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));

        Future<Integer> round = w1.poll();
        RunGate.Run first = runOf(a1);
        assertEquals(List.of("claim w1 -> [1@1]"), l.queue.events(), "the claim hands out A1 only: A2 waits behind its key");

        first.release();
        RunGate.Run second = runOf(a2);
        first.joined();   // A1's thread ended: whatever the worker did after A1's run is done

        assertEquals(List.of("claim w1 -> [1@1]", "take 1@1 -> 2@1"), l.queue.events(),
                "A1 completed and A2 taken in one call: no claim, no plain complete in between");
        assertEquals(1, l.recorded.claims().size(), "one claim so far: " + l.recorded.claims());
        assertEquals(List.of(2L), l.rows.snapshot().stream().map(WorkQueue.Item::seq).toList(), "A1's row is gone");
        assertEquals(Optional.of("w1"), l.rows.leaseHolder(2), "A2 is leased to w1");
        assertFalse(round.isComplete(), "the round waits for the chain it started");

        until(() -> l.rt.timer().pending() == 1, () -> "one renewal scheduled, A2's: pending " + l.rt.timer().pending());
        l.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> l.queue.renews().size() == 1, () -> "a renewal: " + l.queue.renews());
        assertEquals(List.of("renew 2@1 -> true"), l.queue.renews(), "the taken item's lease renews under its own attempts; A1's ended with its run");

        second.release();
        assertTrue(round.await(5, SECONDS) >= 1, "the round succeeds once its chain is done");
        second.joined();
        until(() -> l.recorded.claimsInFlight() == 0 && l.queue.claims().size() == 2, () -> "the chain's end polled: " + l.queue.events());
        assertEquals(List.of("claim w1 -> [1@1]", "take 1@1 -> 2@1", "take 2@1 -> none", "claim w1 -> []"), l.queue.events(),
                "A2 ends the chain (nothing left of key A); the chain's end polls once");
        assertEquals(List.of(a1.id(), a2.id()), l.sentIds(), "in key order");
        assertEquals(0, l.rows.size());
        assertEquals(0, w1.staleSettles());
    }

    /**
     * An item without a key is completed plainly — never through the fast path — and so is nothing of an item left to its lease:
     * K1 (key A) crashes, so it is neither completed nor does it hand K2 over; K2 is never handed out.
     */
    @Test
    void unkeyedOrLeftItemNeverTakesNext() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(1, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        l.accept(delivery, null, null);   // seq 1
        l.accept(delivery, null, null);   // seq 2
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));
        l.gate.open();

        w1.poll().await(5, SECONDS);
        until(() -> l.recorded.claimsInFlight() == 0 && l.queue.claims().size() == 3, () -> "both unkeyed items done: " + l.queue.events());
        l.gate.joinPassed();
        assertEquals(List.of("claim w1 -> [1@1]", "complete 1@1 -> true", "claim w1 -> [2@1]", "complete 2@1 -> true", "claim w1 -> []"),
                l.queue.events(), "unkeyed items are completed plainly, one claim each");

        Envelope k1 = l.accept(delivery, "A", "crash");   // seq 3
        l.accept(delivery, "A", null);                    // seq 4
        w1.poll().await(5, SECONDS);
        l.gate.joinPassed();
        until(() -> l.recorded.claimsInFlight() == 0 && l.queue.claims().size() == 5, () -> "the crashed item's poll: " + l.queue.events());

        assertEquals(List.of(), l.queue.takes(), "never a fast path: " + l.queue.events());
        assertEquals(List.of("claim w1 -> [3@1]", "claim w1 -> []"), l.queue.events().subList(5, l.queue.events().size()),
                "K1 crashed: no complete, no fast path; K2 stays behind it");
        assertEquals(1, l.attemptsOf(3), "K1 is left to its lease");
        assertEquals(Optional.of("w1"), l.rows.leaseHolder(3));
        assertEquals(0, l.attemptsOf(4), "K2 was never handed out");
        assertEquals(1, logs.at(Level.WARNING, k1.id()).size(), "the crash is the one WARNING: " + logs.all());
    }

    /**
     * maxChain 2: after the claimed A1 the worker takes A2 and A3 by the fast path; A3 is at the bound, so it is completed plainly
     * and the key goes back to the seq-ordered claim — the chain's end polls and the claim hands out A4, which starts a chain of
     * its own (A5 taken, then nothing left).
     */
    @Test
    void chainYieldsAfterMaxChain() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) ids.add(l.accept(delivery, "A", null).id());
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 2));
        l.gate.open();

        w1.poll().await(5, SECONDS);
        until(() -> l.recorded.claimsInFlight() == 0 && l.queue.claims().size() == 3, () -> "both chains done: " + l.queue.events());
        l.gate.joinPassed();

        assertEquals(List.of(
                "claim w1 -> [1@1]", "take 1@1 -> 2@1", "take 2@1 -> 3@1", "complete 3@1 -> true",
                "claim w1 -> [4@1]", "take 4@1 -> 5@1", "take 5@1 -> none",
                "claim w1 -> []"), l.queue.events(), "two items taken after each claim, then the key yields to the claim");
        assertEquals(ids, l.sentIds(), "in key order");
        assertEquals(0, l.rows.size());
    }

    @Test
    void maxChainZeroIsOff() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) ids.add(l.accept(delivery, "A", null).id());
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 0));
        l.gate.open();

        w1.poll().await(5, SECONDS);
        until(() -> l.recorded.claimsInFlight() == 0 && l.queue.claims().size() == 4, () -> "every item claimed: " + l.queue.events());
        l.gate.joinPassed();

        assertEquals(List.of(
                "claim w1 -> [1@1]", "complete 1@1 -> true",
                "claim w1 -> [2@1]", "complete 2@1 -> true",
                "claim w1 -> [3@1]", "complete 3@1 -> true",
                "claim w1 -> []"), l.queue.events(), "maxChain 0: every item is completed plainly and the next one claimed");
        assertEquals(ids, l.sentIds());
    }

    /**
     * w1 runs A1 (attempt 1); its lease is found lost and runs out, and w2 claims A1 again (attempt 2). When w1's run ends its fast
     * path, fenced by attempt 1, answers STALE: nothing completed, nothing taken — A2 is never handed out — one WARNING, counted,
     * never retried as a plain complete; the round succeeds.
     */
    @Test
    void staleFastPathCountsAndTakesNothing() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", null);
        l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));

        Future<Integer> round = w1.poll();
        RunGate.Run run = runOf(a1);
        until(() -> l.rt.timer().pending() == 1, () -> "A1's renewal scheduled");
        l.recorded.answerRenews(false);
        l.rt.clock().advance(LEASE.dividedBy(3));
        until(() -> l.queue.renews().size() == 1, () -> "the renewal, answered false");
        l.recorded.renewsOk();
        l.rt.clock().advance(LEASE);   // w1's lease ran out
        List<WorkQueue.Item> theirs = l.rows.claim("w2", "orders", 1, LEASE).await();
        assertEquals(List.of(new WorkQueue.Item(1, a1.id(), "orders", 1, "A", 2)), theirs, "fixture: w2 claims A1 while w1 still runs it");

        run.release();
        round.await(5, SECONDS);
        run.joined();
        until(() -> l.recorded.claimsInFlight() == 0, () -> "claims answered");

        assertEquals(List.of("take 1@1 -> STALE"), l.queue.settles(), "one fast path, fenced by attempt 1, answered STALE; never retried");
        assertEquals(0, l.attemptsOf(2), "A2 was never handed out");
        assertEquals(Optional.of("w2"), l.rows.leaseHolder(1), "A1 stays w2's");
        assertEquals(1, w1.staleSettles(), "counted");
        List<String> stale = logs.at(Level.WARNING, "left to its holder");
        assertEquals(1, stale.size(), "one WARNING: " + logs.all());
        assertTrue(stale.get(0).contains(a1.id()), "naming the message: " + stale.get(0));
    }

    /**
     * A1's fast path completes A1 and leases A2 to w1 (attempt 1), but its answer is late: before it reaches the worker, A2's
     * lease runs out and a claim of w1 hands A2 out again (attempt 2), and the worker runs it. When the take's answer arrives,
     * that run goes on and A1's chain ends there: A2 runs once in w1, not a second time in A1's slot; the chain's end polls, the
     * claim's run settles A2 under its own attempt, and once all is done the worker runs nothing (a poll asks for its whole max).
     */
    @Test
    void aTakenItemAClaimStartedFirstIsNotRunTwice() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", null);
        Envelope a2 = l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));
        Promise<Void> take = l.queue.holdNextTake();

        Future<Integer> round = w1.poll();
        RunGate.Run first = runOf(a1);
        first.release();
        first.joined();
        until(() -> l.queue.takesHeld() == 1, () -> "A1's fast path answered by the queue, its answer held: " + l.queue.events());
        assertEquals(List.of("claim w1 -> [1@1]"), l.queue.events(), "fixture: the take's answer has not reached the worker");
        assertEquals(1, l.attemptsOf(2), "fixture: the take leased A2 under attempt 1");
        assertEquals(Optional.of("w1"), l.rows.leaseHolder(2));

        l.rt.clock().advance(LEASE.plus(TICK));   // A2's lease from the take ran out: nothing of w1 renews it yet
        Future<Integer> again = w1.poll();
        RunGate.Run claimed = runOf(a2);
        assertEquals(List.of("claim w1 -> [1@1]", "claim w1 -> [2@2]"), l.queue.events(), "fixture: w1's claim hands A2 out again, first");

        take.complete();
        until(() -> round.isComplete() || l.gate.runsOf(a2.id()).size() > 1,
                () -> "A1's chain ends at the take's answer, or A2 runs a second time: " + l.gate.runs() + "; " + l.queue.events());
        assertEquals(1, l.gate.runsOf(a2.id()).size(), "A2 runs once in w1, the claim's run: " + l.gate.runs());
        assertEquals(1, round.await(5, SECONDS), "A1's chain ended at the take's answer: its round is done");
        assertEquals(List.of(), l.gate.overlaps(), "no message ran twice at once");

        claimed.release();
        assertEquals(1, again.await(5, SECONDS), "the claim's round ends with A2's run");
        claimed.joined();
        until(() -> l.recorded.claimsInFlight() == 0 && l.queue.claims().size() == 4, () -> "both chains' ends polled: " + l.queue.events());
        assertEquals(List.of("claim w1 -> [1@1]", "claim w1 -> [2@2]", "take 1@1 -> 2@1", "claim w1 -> []", "take 2@2 -> none", "claim w1 -> []"),
                l.queue.events(), "A1's chain ends at the late answer and polls; A2 is settled by the claim's run, under attempt 2");
        assertEquals(List.of(a1.id(), a2.id()), l.sentIds(), "A2 sent once");
        assertEquals(0, l.rows.size());
        assertEquals(0, w1.staleSettles());
        assertEquals(0, w1.poll().await(5, SECONDS));
        List<RecordingWorkQueue.ClaimCall> claims = l.recorded.claims();
        assertEquals(4, claims.get(claims.size() - 1).max, "nothing runs: the claim asks for the whole max: " + claims);
    }

    /** A fast path whose answer fails fails the round; nothing is taken and A1 is left to its lease. */
    @Test
    void aFailedFastPathFailsTheRound() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", null);
        l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));
        IllegalStateException cause = new IllegalStateException("pg away");
        l.queue.failNextTake(cause);
        l.gate.open();

        Future<Integer> round = w1.poll();
        until(round::isComplete, () -> "the round ends: " + l.queue.events());

        assertTrue(round.failed(), "a fast path that fails fails the round: " + round);
        assertSame(cause, round.cause());
        l.gate.joinPassed();
        until(() -> l.recorded.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(List.of("take 1@1 -> failed"), l.queue.settles(), "tried once, never as a plain complete after it");
        assertEquals(List.of(a1.id()), l.sentIds());
        assertEquals(1, l.attemptsOf(1), "A1 is left to its lease");
        assertEquals(0, l.attemptsOf(2), "A2 was not taken");
    }

    /** Stopped while A1 runs: A1 is completed plainly, A2 is not taken, and nothing polls. */
    @Test
    void afterStopKeyCompletedNotTaken() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", null);
        l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));

        Future<Integer> round = w1.poll();
        RunGate.Run run = runOf(a1);
        w1.stop();
        run.release();
        round.await(5, SECONDS);
        run.joined();

        assertEquals(List.of("claim w1 -> [1@1]", "complete 1@1 -> true"), l.queue.events(), "a stopped worker completes and takes nothing");
        assertEquals(0, l.attemptsOf(2), "A2 was not taken");
        assertEquals(1, l.gate.entered(), "and not run");
    }

    /**
     * maxAttempts 2: the fast path hands A2 out with its third attempt (the row as a queue hands it out after two earlier
     * hand-outs): A2 is not run but parked at step 1 with the attempts exhausted — and, parked, its key goes on: A3 runs.
     */
    @Test
    void takenSuccessorPastMaxAttemptsParked() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", null);
        Envelope a2 = l.accept(delivery, "A", null);
        Envelope a3 = l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(2, 32));
        l.queue.bumpNextTake(2);
        l.gate.open();

        w1.poll().await(5, SECONDS);
        until(() -> l.recorded.claimsInFlight() == 0 && l.queue.claims().size() == 2, () -> "the chain done: " + l.queue.events());
        l.gate.joinPassed();

        assertEquals(List.of("claim w1 -> [1@1]", "take 1@1 -> 2@3", "take 2@3 -> 3@1", "take 3@1 -> none", "claim w1 -> []"), l.queue.events(),
                "A2 handed out with attempt 3 of 2 is settled as parked, under that attempt, and its key goes on");
        assertEquals(List.of(), l.gate.runsOf(a2.id()), "A2 never ran");
        assertEquals(List.of(a1.id(), a3.id()), l.sentIds());
        List<Parked> parked = l.dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items();
        assertEquals(1, parked.size(), "one DLQ row: " + parked);
        assertEquals(a2.id(), parked.get(0).messageId());
        assertEquals(1, parked.get(0).step(), "parked at step 1");
        assertTrue(parked.get(0).error().contains("AttemptsExhaustedException"), parked.get(0).error());
        assertEquals(0, l.rows.size());
    }

    /** [A] park-and-continue: A1 fails and is parked — its item is completed through the fast path and A2 runs. */
    @Test
    void aParkedKeyedItemContinuesItsKey() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", "park");
        Envelope a2 = l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));
        l.gate.open();

        w1.poll().await(5, SECONDS);
        until(() -> l.recorded.claimsInFlight() == 0 && l.queue.claims().size() == 2, () -> "the chain done: " + l.queue.events());
        l.gate.joinPassed();

        assertEquals(List.of("claim w1 -> [1@1]", "take 1@1 -> 2@1", "take 2@1 -> none", "claim w1 -> []"), l.queue.events());
        List<Parked> parked = l.dlq.list(Optional.empty(), Page.MAX_LIMIT).await().items();
        assertEquals(List.of(a1.id()), parked.stream().map(Parked::messageId).toList(), "A1 parked");
        assertEquals(List.of(a2.id()), l.sentIds(), "A2 went on");
    }

    /**
     * maxInFlight 2. A2, taken after A1, takes A1's slot: with A2 running, a poll asks the claim for 2 − 1 = 1 and gets B1; with A2
     * and B1 running, a poll claims nothing. Once all are done a poll asks for 2 again.
     */
    @Test
    void chainKeepsOneSlot() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(2, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", null);
        Envelope a2 = l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));

        w1.poll();
        RunGate.Run first = runOf(a1);
        assertEquals(2, l.recorded.claims().get(0).max, "fixture: the first claim asks for 2");
        first.release();
        RunGate.Run second = runOf(a2);
        first.joined();
        assertEquals(1, l.recorded.claims().size(), "fixture: A2 came by the fast path");

        Envelope b1 = l.accept(delivery, "B", null);
        Future<Integer> other = w1.poll();
        RunGate.Run third = runOf(b1);
        assertEquals(2, l.recorded.claims().size(), "a second claim: " + l.recorded.claims());
        assertEquals(1, l.recorded.claims().get(1).max, "A2 holds one of the two slots: the claim asks for 1");
        assertEquals(List.of("claim w1 -> [1@1]", "take 1@1 -> 2@1", "claim w1 -> [3@1]"), l.queue.events());

        assertEquals(0, w1.poll().await(5, SECONDS), "A2 and B1 fill both slots: nothing claimed");
        assertEquals(2, l.recorded.claims().size(), "and no claim asked: " + l.recorded.claims());

        second.release();
        third.release();
        other.await(5, SECONDS);
        second.joined();
        third.joined();
        until(() -> l.recorded.claimsInFlight() == 0, () -> "claims answered");
        assertEquals(0, w1.poll().await(5, SECONDS));
        List<RecordingWorkQueue.ClaimCall> claims = l.recorded.claims();
        assertEquals(2, claims.get(claims.size() - 1).max, "nothing runs: the claim asks for 2 again: " + claims);
        assertEquals(0, l.rows.size());
    }

    @Test
    void eachChainedItemOnItsOwnVirtualThread() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        List<Envelope> in = new ArrayList<>();
        for (int i = 1; i <= 3; i++) in.add(l.accept(delivery, "A", null));
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));

        Future<Integer> round = w1.poll();
        Set<Thread> threads = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Envelope e : in) {
            RunGate.Run run = runOf(e);
            assertTrue(run.thread.isVirtual(), e.id() + " runs on a virtual thread: " + run.thread);
            assertNotEquals(Thread.currentThread(), run.thread);
            threads.add(run.thread);
            run.release();
        }
        round.await(5, SECONDS);
        l.gate.joinPassed();

        assertEquals(3, threads.size(), "each chained item on a thread of its own: " + threads);
        assertEquals(List.of("take 1@1 -> 2@1", "take 2@1 -> 3@1", "take 3@1 -> none"), l.queue.takes(), "fixture: one chain");
    }

    /**
     * A2, taken by the fast path, runs when the worker is stopped and closed: the close waits for it — no producer is closed while
     * it runs — and A2, done, is completed plainly (stopped: A3 not taken); then every producer is closed and the close completes.
     */
    @Test
    void closeWaitsForChain() throws Exception {
        FlowBuilder b = l.builder();
        FlowDefinition def = Line.def(4, STEPS);
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a1 = l.accept(delivery, "A", null);
        Envelope a2 = l.accept(delivery, "A", null);
        l.accept(delivery, "A", null);
        FlowWorker w1 = l.worker(b, def, "w1", policy(5, 32));

        w1.poll();
        RunGate.Run first = runOf(a1);
        first.release();
        RunGate.Run second = runOf(a2);
        first.joined();
        assertEquals(List.of("claim w1 -> [1@1]", "take 1@1 -> 2@1"), l.queue.events(), "fixture: A2 came by the fast path");

        w1.stop();
        Future<Void> close = w1.close();
        assertFalse(close.isComplete(), "the close waits for A2, which the chain started");
        assertFalse(l.partner.built().isEmpty(), "fixture: the worker's pipeline has a producer");
        assertEquals(l.partner.built(), l.partner.open(), "no producer is closed while A2 runs");

        second.release();
        close.await(5, SECONDS);
        second.joined();

        assertEquals(List.of(), l.partner.open(), "every producer closed once A2 was done");
        assertEquals(List.of(a1.id(), a2.id()), l.sentIds(), "A2's send went through an open producer");
        assertEquals(List.of("claim w1 -> [1@1]", "take 1@1 -> 2@1", "complete 2@1 -> true"), l.queue.events(), "stopped: A2 completed plainly");
        assertEquals(0, l.attemptsOf(3), "A3 not taken");
    }
}
