package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Fault;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.api.Stage;
import io.flottila.api.StepFactory;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.node.FencedException;
import io.flottila.core.node.InMemoryMembership;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static io.flottila.core.flow.FlowWorkerTest.until;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14a P1–P6 (D-135 draft, amends D-045, supersedes D-055's maxAttempts): a failed attempt of an at-least-once message is no
 * thread asleep but a work row with a not-before time ({@code WorkQueue.defer}), run again at the flow's schedule —
 * {@code delivery.retries}, else the node's default — wait before retry k = {@code delayBefore(k)}, on any node allowed to claim
 * the row, the same message id each time; after {@code count + 1} hand-outs (doubts included) the ingress envelope is parked at
 * step 1 with its {@code attempt} prop and the row completed. A final failure (the first {@link Fault} in the cause chain
 * MALFORMED or INVALID, a non-retryable {@link NackException}) parks at once; a {@link FencedException} and an interrupt are no
 * failed attempt: left to the lease. A worker wakes itself at the earliest due it set (one timer task).
 *
 * <p>Seams: core's {@link InMemoryWorkQueue} (an {@link InMemoryMembership}: the nodes stay live) behind the
 * {@link RecordingWorkQueue} (the store boundary, recording each defer), the in-memory store and DLQ, a {@link RecordingComponent}
 * partner; the fake clock and the fake timer drive every due and lease, and the worker's reconciliation is a week apart, so a
 * retry at its time is the worker's own wake, never its tick. A "nothing before its time" check is a claim that found nothing.
 *
 * <p>The API assumed (CORE-14a, test-author): {@code Future<Boolean> WorkQueue.defer(long seq, int attempts, Duration delay)};
 * {@code WorkerPolicy(Duration lease, Duration pollInterval)} (maxAttempts gone, maxChain 32); {@code FlowBuilder retries(RetryPolicy
 * nodeDefault)}, the node's default for a flow naming no {@code delivery.retries} — without a call 3 × PT5M, multiplier 1 (O1).
 *
 * <p>The API assumed (CORE-14b P8, test-author): {@code int WorkQueue.Item.maxAttempts()} — the row's own bound on its hand-outs,
 * 0 = the flow's (count + 1); {@code Future<Boolean> WorkQueue.restart(String messageId, String flow, String partitionKey, int attempt)}
 * — appends a row of step 1 as handed out {@code attempt} (k) times, unleased, no doubt, maxAttempts k + 1, so its next hand-out is
 * attempt k + 1; the worker's bound for an item is its maxAttempts when not 0. O7: {@code Optional<String> Parked.partitionKey()} —
 * the worker's park keeps the item's key, empty for an unkeyed item.
 */
@Timeout(60)
class FlowWorkerRetryTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    /** The worker's reconciliation: longer than any case runs, so only its own wake claims a due retry. */
    static final Duration POLL = Duration.ofDays(7);
    static final Map<String, Object> REWRITE = Map.of("type", "rewrite", "name", "rewrite");
    static final Map<String, Object> FAIL = Map.of("type", "fail", "name", "fail");
    static final Map<String, Object> VALIDATE = DeliveryTest.VALIDATE;

    /** A failure that says whose fault it is, with a cause. */
    static final class Kinded extends RuntimeException implements Fault {
        private final Kind kind;

        Kinded(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        @Override public Kind kind() { return kind; }
    }

    static NackException downstream() {
        return new NackException("partner down", true);
    }

    /** An at-least-once flow {@code orders} with these steps and {@code delivery.retries} (none when null), one in flight unless said. */
    static Map<String, Object> flow(Map<String, Object> retries, List<Map<String, Object>> steps) {
        return flow(retries, steps, 4);
    }

    static Map<String, Object> flow(Map<String, Object> retries, List<Map<String, Object>> steps, int maxInFlight) {
        Map<String, Object> doc = DeliveryTest.flow("at-least-once", steps);
        Map<String, Object> delivery = new HashMap<>();
        delivery.put("mode", "at-least-once");
        delivery.put("timeout", "1h");
        if (retries != null) delivery.put("retries", retries);
        doc.put("delivery", delivery);
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", maxInFlight));
        return doc;
    }

    static Map<String, Object> retries(int count, String interval) {
        return Map.of("count", count, "interval", interval);
    }

    /** Everything one case (or one try of {@link RetrySchedulePropertiesTest}) runs on. */
    static final class World {
        final FakeRuntime rt = FakeRuntime.create();
        final Instant t0 = rt.clock().instant();
        final RecordingComponent partner = new RecordingComponent("mock");
        final InMemoryMembership members = new InMemoryMembership(rt.clock());
        final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock(), members);
        final RecordingWorkQueue queue = new RecordingWorkQueue(rows, rt.clock());
        final InMemoryDlq parked = new InMemoryDlq();
        final InMemoryMessageStore store = new InMemoryMessageStore();
        /** What the next runs of the {@code fail} step throw, one each; empty: it hands on. */
        final ConcurrentLinkedDeque<Supplier<Throwable>> failures = new ConcurrentLinkedDeque<>();
        final AtomicInteger failRuns = new AtomicInteger();
        final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
        final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
        final StepFactory rewrite = ParkingTest.factory("rewrite", s -> Stage.of(e -> e.next(rt.store(e, Buffer.buffer("<rewritten/>")))));
        final StepFactory fail = ParkingTest.factory("fail", s -> Stage.of(e -> {
            failRuns.incrementAndGet();
            Supplier<Throwable> next = failures.pollFirst();
            if (next == null) return e;
            throw ParkingTest.sneaky(next.get());
        }));

        World() {
            rt.withComponent(partner);
            members.join("n1", Duration.ofDays(30)).await();
            members.join("n2", Duration.ofDays(30)).await();
        }

        FlowBuilder builder() {
            return builder(() -> true);
        }

        /**
         * A builder whose workers, once started, take its deliveries' items by the hand-off (D-121 (a)): its own {@link Workers}
         * and a {@link SequentialIntake} over them leased to {@code n1}, the node every case accepts on (the default intake hands
         * nothing off and wakes nobody).
         */
        FlowBuilder builder(BooleanSupplier fence) {
            Workers registry = new Workers();
            return new FlowBuilder(rt.runtime(), List.of(rewrite, fail), List.of(BuiltInStepsTest.HEADERS), endpoints)
                    .recording(store, Meter.system()).queue(queue).dlq(parked).fence(fence)
                    .workers(registry).intake(new SequentialIntake(store, queue, registry, "n1"));
        }

        /** {@code id}'s worker of {@code def}, started: registered for hand-offs, its reconciliation a week apart. */
        FlowWorker started(FlowBuilder b, FlowDefinition def, String id) {
            FlowWorker w = b.worker(def, NO_FILES, id, new WorkerPolicy(LEASE, POLL));
            workers.add(w);
            w.start();
            return w;
        }

        Envelope accept(Delivery delivery) throws Exception {
            Envelope in = rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
            assertEquals(new Ack.Accepted(in.id()), delivery.apply(in).await(5, SECONDS));
            return in;
        }

        List<String> sentIds() {
            return partner.sentTo("partner").stream().map(Envelope::id).toList();
        }

        List<Parked> dlq() {
            return parked.list(Optional.empty(), Page.MAX_LIMIT).await().items();
        }

        /** Nothing of {@code w}'s runs, claims or settles is still going. */
        void settled(FlowWorker w) {
            until(() -> w.unsettled() == 0 && queue.claimsInFlight() == 0,
                    () -> "settled: running " + w.unsettled() + ", claims " + queue.claims() + ", defers " + queue.deferCalls());
        }

        /** Waits until {@code n} sends reached the partner and {@code w} settled what it ran. */
        void sent(FlowWorker w, int n) {
            until(() -> partner.sent().size() >= n, () -> n + " sends: " + partner.sent().size());
            settled(w);
            assertEquals(n, partner.sent().size(), "exactly " + n + " sends");
        }

        /**
         * The clock 1 ms before {@code due}: a claim of {@code w} asks for room and finds nothing, and nothing more is sent; then at
         * {@code due} — no poll of the test, the worker's own wake — send {@code n} arrives and settles.
         */
        void retryAt(FlowWorker w, String id, Instant due, int n) throws Exception {
            rt.clock().set(due.minusMillis(1));
            assertEquals(0, w.poll().await(5, SECONDS), "1 ms before " + due + " a claim finds nothing due");
            RecordingWorkQueue.ClaimCall last = queue.claimsBy(id).getLast();
            assertTrue(last.max >= 1 && List.of().equals(last.items), "that claim asked for room and got nothing: " + last);
            assertEquals(n - 1, partner.sent().size(), "nothing sent before its time");
            rt.clock().set(due);
            sent(w, n);
        }

        void close() {
            workers.forEach(FlowWorker::stop);
        }
    }

    final World w = new World();

    @AfterEach
    void tearDown() {
        w.close();
    }

    /** The single DLQ row: the ingress envelope of {@code in}, parked at step 1 ({@code rewrite}) with its attempt. */
    void parkedIngress(Envelope in, int attempt) {
        List<Parked> all = w.dlq();
        assertEquals(1, all.size(), "one DLQ row: " + all);
        Parked p = all.get(0);
        assertEquals(in.id(), p.messageId(), "the message parked, under its own id");
        assertEquals(1, p.step(), "parked at step 1, not at the failing step");
        assertEquals("rewrite", p.stepName());
        assertEquals(in.body().key(), p.bodyKey(), "the ingress body, not the step's output");
        assertEquals(in.body().hash(), p.bodyHash());
        assertEquals(Optional.of(attempt), p.props().get(Envelope.ATTEMPT), "with the attempt it was parked after");
        assertEquals(Optional.empty(), p.partitionKey(), "O7: an unkeyed item's park keeps no key");
        assertEquals(0, w.rows.size(), "its row completed");
    }

    @Test
    void retryRunsAtItsTimeNotBefore() throws Exception {
        w.partner.failNext(1, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(1, "1m"), List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));

        w.sent(worker, 1);
        assertEquals(List.of(), w.dlq(), "a failed attempt with retries left is not parked");
        assertEquals(1, w.rows.size(), "its row stays");
        assertEquals(1, w.queue.deferCalls().size(), "deferred once: " + w.queue.deferCalls());
        RecordingWorkQueue.DeferCall deferred = w.queue.deferCalls().get(0);
        assertEquals(Duration.ofMinutes(1), deferred.delay(), "by the interval");
        assertEquals(1, deferred.attempts(), "under the hand-out's token");
        assertEquals(Boolean.TRUE, deferred.answer());

        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(1)), 2);
        assertEquals(List.of(in.id(), in.id()), w.sentIds(), "the same message id each time");
        assertEquals(Optional.of(2), w.partner.sent().get(1).get(Envelope.ATTEMPT), "the retry runs as attempt 2");
        assertEquals(0, w.rows.size(), "the retry succeeded: completed");
        assertEquals(List.of(), w.dlq());
    }

    /** 1m × 2 capped at 3m: sends at 0, 1m, 3m, 6m (waits 1m, 2m, 3m — not 4m); the fourth failure of count 3 parks. */
    @Test
    void backoffFollowsTheSchedule() throws Exception {
        w.partner.failNext(4, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(Map.of("count", 3, "interval", "1m", "multiplier", 2, "maxInterval", "3m"),
                List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));

        w.sent(worker, 1);
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(1)), 2);
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(3)), 3);
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(6)), 4);
        assertEquals(List.of(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(3)),
                w.queue.deferCalls().stream().map(RecordingWorkQueue.DeferCall::delay).toList(), "the waits before retries 1, 2, 3");
        assertEquals(List.of(1, 2, 3), w.queue.deferCalls().stream().map(RecordingWorkQueue.DeferCall::attempts).toList());
        parkedIngress(in, 4);
    }

    @Test
    void parkedAfterTheCount() throws Exception {
        w.partner.failNext(10, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(2, "1m"), List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));

        w.sent(worker, 1);
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(1)), 2);
        assertEquals(List.of(), w.dlq(), "two sends of count 2: not parked yet");
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(2)), 3);
        parkedIngress(in, 3);
        assertEquals(2, w.queue.deferCalls().size(), "the third failure is not deferred: " + w.queue.deferCalls());

        w.rt.clock().advance(Duration.ofDays(1));
        assertEquals(0, worker.poll().await(5, SECONDS), "a day later nothing is due");
        w.settled(worker);
        assertEquals(3, w.partner.sent().size(), "nothing sent after the park");
        assertEquals(1, w.dlq().size(), "parked once");
    }

    /** One permit: between the attempts of a deferred message nothing of it runs, so another message runs at once in that permit. */
    @Test
    void noThreadWaitsAcrossAnInterval() throws Exception {
        w.partner.failNext(1, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(1, "1h"), List.of(REWRITE, TO_PARTNER), 1));
        FlowWorker worker = w.started(b, def, "n1");
        Delivery delivery = b.delivery(def, NO_FILES);
        Envelope a = w.accept(delivery);
        w.sent(worker, 1);
        assertEquals(1, w.queue.deferCalls().size(), "a deferred: " + w.queue.deferCalls());
        assertEquals(0, worker.unsettled(), "nothing of it runs while it waits");

        Envelope other = w.accept(delivery);
        worker.poll().await(5, SECONDS);
        w.sent(worker, 2);
        assertEquals(List.of(a.id(), other.id()), w.sentIds(), "the other message ran within a's interval, in the one permit");
        assertTrue(w.rt.clock().instant().isBefore(w.t0.plus(Duration.ofHours(1))), "fixture: before a's due");

        w.retryAt(worker, "n1", w.t0.plus(Duration.ofHours(1)), 3);
        assertEquals(a.id(), w.sentIds().get(2), "a's retry at its time");
    }

    /**
     * O6: n1 deferred the row and went (its worker stopped, its membership still live); n2's claim takes it at its due — the defer
     * cleared the holder, so the row is not n1's until n1's lease runs out (10 s < the 30 s lease) — as attempt 2 in no doubt.
     */
    @Test
    void anotherNodeRunsTheRetry() throws Exception {
        w.partner.failNext(1, FlowWorkerRetryTest::downstream);
        FlowDefinition def = FlowDefinition.from(flow(retries(1, "10s"), List.of(REWRITE, TO_PARTNER)));
        FlowBuilder b1 = w.builder();
        FlowWorker n1 = w.started(b1, def, "n1");
        Envelope in = w.accept(b1.delivery(def, NO_FILES));
        w.sent(n1, 1);
        assertEquals(1, w.queue.deferCalls().size(), "deferred by n1: " + w.queue.deferCalls());
        n1.stop();

        FlowWorker n2 = w.started(w.builder(), def, "n2");
        w.rt.clock().set(w.t0.plus(Duration.ofSeconds(10)).minusMillis(1));
        assertEquals(0, n2.poll().await(5, SECONDS), "not before its time on another node either");
        w.rt.clock().set(w.t0.plus(Duration.ofSeconds(10)));
        assertEquals(1, n2.poll().await(5, SECONDS), "n2's claim takes the due row");
        w.sent(n2, 2);
        assertTrue(w.members.live("n1"), "fixture: n1's membership is live, its lease of the row not run out");
        WorkQueue.Item taken = w.queue.claimsBy("n2").getLast().items.get(0);
        assertEquals(in.id(), taken.messageId());
        assertEquals(2, taken.attempts(), "the retry is attempt 2");
        assertEquals(0, taken.doubts(), "a deferred row's next hand-out is in no doubt");
        assertEquals(List.of(in.id(), in.id()), w.sentIds(), "the same message id on the other node");
        assertEquals(0, w.rows.size());
    }

    /**
     * P5: a takeover hand-out of a row an earlier holder left unsettled counts as attempt k (here 2) and runs at once; failing, it
     * waits delayBefore(2) = 2m, not 1m; its retry, attempt 3 = count + 1, failing, parks.
     */
    @Test
    void inDoubtCountsAsAnAttempt() throws Exception {
        w.partner.failNext(10, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(Map.of("count", 2, "interval", "1m", "multiplier", 2, "maxInterval", "10m"),
                List.of(REWRITE, TO_PARTNER)));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        assertEquals(1, w.rows.claim("crashed", "orders", 1, LEASE).await().size(), "fixture: a first hand-out to a node that is gone");
        w.rt.clock().advance(LEASE.plusSeconds(1));
        Instant takeover = w.rt.clock().instant();

        FlowWorker worker = w.started(b, def, "n1");
        w.sent(worker, 1);
        assertEquals(takeover, w.rt.clock().instant(), "fixture: the takeover ran without the clock moving");
        WorkQueue.Item taken = w.queue.claimsBy("n1").stream().filter(c -> c.items != null && !c.items.isEmpty()).findFirst().orElseThrow().items.get(0);
        assertEquals(2, taken.attempts(), "the takeover is attempt 2");
        assertEquals(1, taken.doubts(), "in doubt");
        assertEquals(1, w.queue.deferCalls().size(), "its failure deferred: " + w.queue.deferCalls());
        assertEquals(Duration.ofMinutes(2), w.queue.deferCalls().get(0).delay(), "delayBefore(2): the in-doubt run counted as attempt 2");

        w.retryAt(worker, "n1", takeover.plus(Duration.ofMinutes(2)), 2);
        parkedIngress(in, 3);
    }

    /** P2: a {@link FencedException} is no failed attempt: not deferred, not parked, left to its lease (its lease end runs it again). */
    @Test
    void fencedFailureIsNotDeferred() throws Exception {
        w.failures.add(() -> new FencedException("fenced at the send (test)"));
        leftToItsLease();
    }

    /** P2: an interrupted run is no failed attempt either: not deferred, not parked, left to its lease. */
    @Test
    void anInterruptedRunIsNotDeferred() throws Exception {
        w.failures.add(() -> new InterruptedException("cancelled (test)"));
        leftToItsLease();
    }

    /** The first run of the {@code fail} step throws what the case queued; the rerun hands on. Retries are an hour apart. */
    private void leftToItsLease() throws Exception {
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1h"), List.of(REWRITE, FAIL, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        until(() -> w.failRuns.get() == 1, () -> "the first run reached the step: " + w.failRuns.get());
        w.settled(worker);

        assertEquals(List.of(), w.queue.deferCalls(), "not deferred");
        assertEquals(List.of(), w.dlq(), "not parked");
        assertEquals(List.of(), w.queue.completeCalls(), "not completed");
        assertEquals(1, w.rows.size(), "its row stays");
        long seq = w.rows.snapshot().get(0).seq();
        assertEquals(Optional.of("n1"), w.rows.leaseHolder(seq), "still leased: left to its lease");

        w.rt.clock().advance(LEASE);   // witness: the lease end, not the retry's hour, runs it again
        w.sent(worker, 1);
        assertEquals(List.of(in.id()), w.sentIds(), "rerun at its lease end and sent");
        assertEquals(List.of(), w.queue.deferCalls());
        assertEquals(0, w.rows.size(), "the rerun completed it");
    }

    /** P3: a defer that fails leaves the item to its lease: its lease end runs it again, not the hour of its schedule. */
    @Test
    void aFailedDeferIsLeftToItsLease() throws Exception {
        w.partner.failNext(1, FlowWorkerRetryTest::downstream);
        w.queue.failNextDefer(new IllegalStateException("queue down (test)"));
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1h"), List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        w.sent(worker, 1);
        assertEquals(1, w.queue.deferCalls().size(), "the defer was asked: " + w.queue.deferCalls());
        assertEquals(null, w.queue.deferCalls().get(0).answer(), "and failed");
        assertEquals(List.of(), w.dlq(), "nothing parked");

        w.rt.clock().advance(LEASE);
        w.sent(worker, 2);
        assertEquals(List.of(in.id(), in.id()), w.sentIds(), "run again at its lease end");
        assertEquals(0, w.rows.size());
    }

    /** P3: the defer is a settle: a fence closed when the run failed defers nothing (no other node's row is touched); reopened, the lease end reruns it. */
    @Test
    void aClosedFenceDefersNothing() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        w.failures.add(() -> {
            open.set(false);   // the fence closes during the failing run
            return downstream();
        });
        FlowBuilder b = w.builder(open::get);
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1h"), List.of(REWRITE, FAIL, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        until(() -> w.failRuns.get() == 1, () -> "the run failed: " + w.failRuns.get());
        w.settled(worker);
        assertEquals(List.of(), w.queue.deferCalls(), "no defer from a closed fence");
        assertEquals(List.of(), w.dlq(), "no park either");
        assertEquals(1, w.rows.size(), "left unsettled");

        open.set(true);
        w.rt.clock().advance(LEASE);
        w.sent(worker, 1);
        assertEquals(List.of(in.id()), w.sentIds(), "its lease end ran it again");
    }

    /**
     * Review r2 T1: a final failure, the node's fence closed during the run and open again before the settle: the park is a settle,
     * so no DLQ row; the round still succeeds, one WARNING says the message is not parked under the closed fence, and the item is
     * left to its lease — its lease end reruns it.
     */
    @Test
    void aFinalFailureUnderAFenceClosedDuringTheRunIsLeftToItsLease() throws Exception {
        AtomicBoolean closedForOneReading = new AtomicBoolean();
        BooleanSupplier fence = () -> !closedForOneReading.getAndSet(false);
        w.failures.add(() -> {
            closedForOneReading.set(true);   // the fence closes during the failing run; its next reading answers closed, the one after open
            return new Kinded(Fault.Kind.INVALID, "breaks a rule", null);
        });
        FlowBuilder b = w.builder(fence);
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1m"), List.of(REWRITE, FAIL, TO_PARTNER)));
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        FlowWorker worker = b.worker(def, NO_FILES, "n1", new WorkerPolicy(LEASE, POLL));
        w.workers.add(worker);
        FlowWorkerTest.Logs logs = FlowWorkerTest.Logs.attach();
        try {
            assertEquals(1, worker.poll().await(5, SECONDS), "the round claimed the item and succeeded");
            w.settled(worker);
            assertFalse(closedForOneReading.get(), "fixture: the closed reading was taken, the fence is open again");
            assertEquals(List.of(), w.dlq(), "no DLQ row from a closed fence");
            assertEquals(List.of(), w.queue.deferCalls(), "a final failure is never deferred");
            assertEquals(List.of(), w.queue.completeCalls(), "nor completed");
            assertEquals(1, w.rows.size(), "the item stays");
            List<String> notParked = logs.at(java.util.logging.Level.WARNING, "is not parked: the node's fence is closed");
            assertEquals(1, notParked.size(), "one WARNING: " + logs.all());
            assertTrue(notParked.get(0).contains(in.id()), "naming the message: " + notParked.get(0));

            w.rt.clock().advance(LEASE);   // its lease end: the worker's own wake claims it again
            w.sent(worker, 1);
            assertEquals(List.of(in.id()), w.sentIds(), "rerun at its lease end (the step passes now) and sent");
            assertEquals(0, w.rows.size(), "the rerun completed it");
            assertEquals(List.of(), w.dlq());
        } finally {
            logs.detach();
        }
    }

    /** Review r2 T2: a deferred run logs one WARNING naming the message, its attempt of the bound and the wait before its retry. */
    @Test
    void aDeferredRunLogsOneWarningNamingItsAttemptAndItsWait() throws Exception {
        w.partner.failNext(1, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1m"), List.of(REWRITE, TO_PARTNER)));
        FlowWorkerTest.Logs logs = FlowWorkerTest.Logs.attach();
        try {
            FlowWorker worker = w.started(b, def, "n1");
            Envelope in = w.accept(b.delivery(def, NO_FILES));
            w.sent(worker, 1);
            assertEquals(1, w.queue.deferCalls().size(), "fixture: deferred: " + w.queue.deferCalls());
            List<String> deferred = logs.at(java.util.logging.Level.WARNING, "its retry waits");
            assertEquals(1, deferred.size(), "one WARNING for the deferred run: " + logs.all());
            String line = deferred.get(0);
            assertTrue(line.contains(in.id()), "names the message: " + line);
            assertTrue(line.contains("attempt 1 of 4"), "its attempt of the bound, count 3 + 1: " + line);
            assertTrue(line.contains("its retry waits PT1M"), "the wait before its retry: " + line);
        } finally {
            logs.detach();
        }
    }

    /** What fails for good (P2): parked at once at the first failure, never deferred. */
    enum Final {
        INVALID_INPUT(List.of(REWRITE, VALIDATE)),   // the built-in validate: the message has no id header (ValidationFailedException, INVALID)
        A_FINAL_NACK_OF_THE_PARTNER(List.of(REWRITE, TO_PARTNER)),
        MALFORMED(List.of(REWRITE, FAIL, TO_PARTNER)),
        INVALID_AS_THE_FIRST_FAULT_IN_THE_CHAIN(List.of(REWRITE, FAIL, TO_PARTNER)),
        A_FINAL_NACK_AS_THE_FIRST_FAULT_IN_THE_CHAIN(List.of(REWRITE, FAIL, TO_PARTNER));

        final List<Map<String, Object>> steps;

        Final(List<Map<String, Object>> steps) { this.steps = steps; }

        void stage(World w) {
            switch (this) {
                case INVALID_INPUT -> { }
                case A_FINAL_NACK_OF_THE_PARTNER -> w.partner.failNext(1, () -> new NackException("refused for good", false));
                case MALFORMED -> w.failures.add(() -> new Kinded(Fault.Kind.MALFORMED, "not well-formed", null));
                case INVALID_AS_THE_FIRST_FAULT_IN_THE_CHAIN -> w.failures.add(() -> new IllegalStateException("wrapped",
                        new Kinded(Fault.Kind.INVALID, "breaks a rule", null)));
                case A_FINAL_NACK_AS_THE_FIRST_FAULT_IN_THE_CHAIN -> w.failures.add(() -> new IllegalStateException("wrapped",
                        new NackException("refused for good", false)));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Final.class)
    void invalidAndFinalNackParkAtOnce(Final failure) throws Exception {
        failure.stage(w);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1m"), failure.steps));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        until(() -> !w.dlq().isEmpty(), () -> "parked: " + w.dlq() + ", defers " + w.queue.deferCalls());
        w.settled(worker);

        assertEquals(List.of(), w.queue.deferCalls(), "never deferred");
        parkedIngress(in, 1);
        w.rt.clock().advance(Duration.ofHours(1));
        assertEquals(0, worker.poll().await(5, SECONDS), "nothing left to retry");
        assertTrue(w.partner.sent().size() <= 1, "no retry sent: " + w.partner.sent().size());
    }

    /** A failure whose first {@link Fault} in the cause chain is not final (or which has none) is a failed attempt: deferred. */
    enum Retried {
        A_RETRYABLE_NACK_OVER_AN_INVALID_CAUSE,
        DOWNSTREAM_OVER_A_MALFORMED_CAUSE,
        INTERNAL,
        NO_FAULT_AT_ALL;

        Throwable failure() {
            return switch (this) {
                case A_RETRYABLE_NACK_OVER_AN_INVALID_CAUSE -> new NackException("busy", true).initCause(new Kinded(Fault.Kind.INVALID, "breaks a rule", null));
                case DOWNSTREAM_OVER_A_MALFORMED_CAUSE -> new Kinded(Fault.Kind.DOWNSTREAM, "partner down", new Kinded(Fault.Kind.MALFORMED, "garbled", null));
                case INTERNAL -> new Kinded(Fault.Kind.INTERNAL, "a bug", null);
                case NO_FAULT_AT_ALL -> new IllegalStateException("a bug without a kind");
            };
        }
    }

    @ParameterizedTest
    @EnumSource(Retried.class)
    void aFailureWhoseFirstFaultIsNotFinalIsRetried(Retried failure) throws Exception {
        w.failures.add(failure::failure);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1m"), List.of(REWRITE, FAIL, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        until(() -> w.failRuns.get() == 1, () -> "the run failed: " + w.failRuns.get());
        w.settled(worker);
        assertEquals(1, w.queue.deferCalls().size(), "deferred: " + w.queue.deferCalls());
        assertEquals(List.of(), w.dlq(), "not parked");

        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(1)), 1);
        assertEquals(List.of(in.id()), w.sentIds(), "the retry passed and sent");
    }

    /** P1: a flow naming no delivery.retries runs under the node's default the builder was given. */
    @Test
    void omittedRetriesFollowTheNodeDefault() throws Exception {
        w.partner.failNext(10, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder().retries(new RetryPolicy(1, Duration.ofSeconds(10), Duration.ofSeconds(10), 1.0));
        FlowDefinition def = FlowDefinition.from(flow(null, List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        w.sent(worker, 1);
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofSeconds(10)), 2);
        parkedIngress(in, 2);
    }

    /** P1: the flow's own delivery.retries wins over the node's default: count 0 parks the first failure. */
    @Test
    void aFlowsOwnRetriesOverrideTheNodeDefault() throws Exception {
        w.partner.failNext(10, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder().retries(new RetryPolicy(5, Duration.ofSeconds(10), Duration.ofSeconds(10), 1.0));
        FlowDefinition def = FlowDefinition.from(flow(retries(0, "1m"), List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        w.sent(worker, 1);
        assertEquals(List.of(), w.queue.deferCalls(), "count 0: no retry");
        parkedIngress(in, 1);
    }

    /** O1: a builder never given a node default runs a flow without delivery.retries under 3 retries 5 minutes apart. */
    @Test
    void withoutANodeDefaultTheBuilderRetriesThreeTimesFiveMinutesApart() throws Exception {
        w.partner.failNext(10, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(null, List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        w.sent(worker, 1);
        List<Instant> dues = new ArrayList<>();
        for (int k = 1; k <= 3; k++) dues.add(w.t0.plus(Duration.ofMinutes(5L * k)));
        for (int k = 0; k < 3; k++) {
            assertFalse(w.dlq().size() > 0, "not parked before the last retry");
            w.retryAt(worker, "n1", dues.get(k), k + 2);
        }
        parkedIngress(in, 4);
    }

    // --- CORE-14b P8: the same-id manual restart

    /**
     * The in-memory half of {@code POST /admin/dlq/{id}/retry}: {@code in}'s DLQ row (attempt {@code k}) out, its work row in —
     * {@code WorkQueue.restart(id, flow, key, k)}: step 1, as handed out k times, its bound k + 1 (Postgres does both in one
     * transaction: DlqRestartIT).
     */
    void restart(Envelope in, int k) {
        restart(in, k, null);
    }

    /** {@link #restart(Envelope, int)} under the key {@code partitionKey} the DLQ row kept (O7). */
    void restart(Envelope in, int k, String partitionKey) {
        assertTrue(w.parked.get(in.id()).await().isPresent(), "fixture: " + in.id() + " is parked");
        w.parked.remove(in.id()).await();
        assertTrue(w.rows.restart(in.id(), "orders", partitionKey, k).await(), "the in-memory queue appends the restarted row");
        assertEquals(1, w.rows.size(), "fixture: one row, the restarted one");
    }

    /** The last item {@code worker} was handed out by a claim. */
    WorkQueue.Item lastTaken(String worker) {
        return w.queue.claimsBy(worker).stream().filter(c -> c.items != null && !c.items.isEmpty()).reduce((a, b) -> b).orElseThrow().items.getLast();
    }

    /**
     * P8: count 1 → parked after 2 sends (k = 2, the flow's bound). Restarted, the row is handed out once more as attempt 3 with
     * maxAttempts 3 — past the flow's bound of 2, so only the row's own bound lets it run — sends once with the same id and, failing,
     * is parked again at attempt 3, not deferred; nothing runs after a day.
     */
    @Test
    void restartRunsItOnce() throws Exception {
        w.partner.failNext(10, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(1, "1m"), List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        w.sent(worker, 1);
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(1)), 2);
        parkedIngress(in, 2);
        assertEquals(1, w.queue.deferCalls().size(), "fixture: one retry before the park");

        restart(in, 2);
        assertEquals(1, worker.poll().await(5, SECONDS), "the restarted row is due at once");
        w.sent(worker, 3);
        WorkQueue.Item taken = lastTaken("n1");
        assertEquals(in.id(), taken.messageId(), "the same message");
        assertEquals(1, taken.step());
        assertEquals(3, taken.attempts(), "handed out as attempt k + 1");
        assertEquals(3, taken.maxAttempts(), "its own bound, k + 1");
        assertEquals(0, taken.doubts(), "a restarted row is in no doubt");
        assertEquals(List.of(in.id(), in.id(), in.id()), w.sentIds(), "the restart sends under the same id");
        assertEquals(Optional.of(3), w.partner.sent().get(2).get(Envelope.ATTEMPT), "the restart runs as attempt 3");
        parkedIngress(in, 3);
        assertEquals(1, w.queue.deferCalls().size(), "its one attempt was its last: not deferred: " + w.queue.deferCalls());

        w.rt.clock().advance(Duration.ofDays(1));
        assertEquals(0, worker.poll().await(5, SECONDS), "a day later nothing is due");
        w.settled(worker);
        assertEquals(3, w.partner.sent().size(), "nothing sent after the second park");
        assertEquals(1, w.dlq().size(), "parked once again");
    }

    /**
     * P8: the row's bound also cuts the flow's: count 3 (bound 4), a final NACK parks attempt 1 at once (k = 1). Restarted, attempt 2
     * (maxAttempts 2) fails retryably — with retries left under the flow's count it would be deferred — and is parked again at once.
     */
    @Test
    void aRestartAfterAParkAtOnceRunsOnceNotTheFlowsCount() throws Exception {
        w.partner.failNext(1, () -> new NackException("refused for good", false));
        w.partner.failNext(10, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1m"), List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        w.sent(worker, 1);
        parkedIngress(in, 1);

        restart(in, 1);
        assertEquals(1, worker.poll().await(5, SECONDS), "the restarted row is due at once");
        w.sent(worker, 2);
        assertEquals(2, lastTaken("n1").maxAttempts(), "its bound k + 1 = 2, not the flow's 4");
        assertEquals(Optional.of(2), w.partner.sent().get(1).get(Envelope.ATTEMPT));
        assertEquals(List.of(), w.queue.deferCalls(), "the restart's failure is not deferred by the flow's schedule");
        parkedIngress(in, 2);

        w.rt.clock().advance(Duration.ofDays(1));
        assertEquals(0, worker.poll().await(5, SECONDS), "a day later nothing is due");
        w.settled(worker);
        assertEquals(2, w.partner.sent().size(), "nothing sent after the second park");
    }

    /**
     * P8 with P5: the restarted row's one hand-out goes to a node that dies (attempt 2 of maxAttempts 2); its takeover, attempt 3, is
     * past the row's bound — though within the flow's 4 — so it is parked unsent (attempt 3), never run a second time.
     */
    @Test
    void aRestartedRowTakenOverInDoubtIsParkedUnsent() throws Exception {
        w.partner.failNext(1, () -> new NackException("refused for good", false));
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1m"), List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        w.sent(worker, 1);
        parkedIngress(in, 1);

        restart(in, 1);
        List<WorkQueue.Item> lost = w.rows.claim("crashed", "orders", 1, LEASE).await();
        assertEquals(1, lost.size(), "fixture: the restart's hand-out to a node that is gone");
        assertEquals(List.of(2, 2), List.of(lost.get(0).attempts(), lost.get(0).maxAttempts()), "its one attempt: 2 of 2");
        w.rt.clock().advance(LEASE.plusSeconds(1));

        assertEquals(1, worker.poll().await(5, SECONDS), "the takeover claims it");
        w.settled(worker);
        WorkQueue.Item taken = lastTaken("n1");
        assertEquals(List.of(3, 1, 2), List.of(taken.attempts(), taken.doubts(), taken.maxAttempts()), "attempt 3, in doubt, bound 2");
        assertEquals(1, w.partner.sent().size(), "past the row's bound: not sent again");
        parkedIngress(in, 3);
    }

    /**
     * O7: a keyed item's park keeps its key on the DLQ row ({@code Parked.partitionKey()}); restarted under it, the row runs under that
     * key, once, as attempt 2.
     */
    @Test
    void aKeyedParkKeepsItsKeyAndItsRestartRunsUnderIt() throws Exception {
        w.partner.failNext(1, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        Map<String, Object> doc = flow(retries(0, "1m"), List.of(
                Map.of("type", "extractKey", "name", "key", "language", "header", "key", "customer", "xpath", "x-customer"), REWRITE, TO_PARTNER));
        doc.put("partitionBy", "customer");
        doc.put("onFailure", "park");
        FlowDefinition def = FlowDefinition.from(doc);
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.rt.ingress("orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml").with("x-customer", "c1"),
                Buffer.buffer("<order id=\"1\"/>"));
        assertEquals(new Ack.Accepted(in.id()), b.delivery(def, NO_FILES).apply(in).await(5, SECONDS));
        w.sent(worker, 1);
        List<Parked> all = w.dlq();
        assertEquals(1, all.size(), "parked at once (count 0): " + all);
        assertEquals(Optional.of("c1"), all.get(0).partitionKey(), "the park keeps the item's key");

        restart(in, 1, "c1");
        assertEquals(1, worker.poll().await(5, SECONDS), "the restarted row is due at once");
        w.sent(worker, 2);
        WorkQueue.Item taken = lastTaken("n1");
        assertEquals(List.of("c1", "2", "2"), List.of(String.valueOf(taken.partitionKey()), String.valueOf(taken.attempts()), String.valueOf(taken.maxAttempts())),
                "under its key, attempt 2 of its bound 2");
        assertEquals(0, w.rows.size(), "the restarted run succeeded: completed");
        assertEquals(List.of(), w.dlq(), "not parked again");
    }

    // --- FIX-175 (P2, P5): each attempt of a same-id rerun keeps its own rows

    /** Each row of {@code id}'s timeline as {@code "<attempt> <step> <name> <outcome>"}. */
    List<String> rowsOf(String id) {
        return w.store.timeline(id).await().stream().map(s -> s.attempt() + " " + s.step() + " " + s.stepName() + " " + s.outcome()).toList();
    }

    /**
     * A1 (core, the fake clock): the partner fails twice, then takes the message (count 2, 1m apart). The timeline holds the rows of
     * attempts 1, 2 and 3 — the ingress once, at attempt 1 (the intake records it, a rerun does not) — each with its attempt and its
     * own outcome, attempt 3's last step OK; each attempt's {@code rewrite} row references the body that attempt wrote (D-066).
     */
    @Test
    void aMessageRetriedTwiceKeepsTheRowsOfEachAttemptWithItsOwnOutcome() throws Exception {
        w.partner.failNext(2, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(2, "1m"), List.of(REWRITE, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));

        w.sent(worker, 1);
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(1)), 2);
        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(2)), 3);
        assertEquals(List.of(), w.dlq(), "fixture: the third attempt went through");
        assertEquals(0, w.rows.size(), "fixture: completed");

        assertEquals(List.of("1 0 ingress OK", "1 1 rewrite OK", "1 2 partner FAILED", "2 1 rewrite OK", "2 2 partner FAILED", "3 1 rewrite OK", "3 2 partner OK"),
                rowsOf(in.id()), "attempts 1, 2 and 3, by attempt then step, each with its own outcome");
        List<Optional<io.flottila.api.BlobKey>> rewritten = w.store.timeline(in.id()).await().stream().filter(s -> s.step() == 1).map(io.flottila.core.record.Snapshot::bodyKey).toList();
        assertEquals(List.of(Optional.of(io.flottila.api.BlobKey.body(in.id(), 1)), Optional.of(new io.flottila.api.BlobKey(in.id(), 1, "body~2")),
                Optional.of(new io.flottila.api.BlobKey(in.id(), 1, "body~3"))), rewritten, "each attempt's row references its own step body");
    }

    /**
     * A6 (P5): the worker's rerun starts from attempt 1's ingress row — what arrived — even when a later attempt left a step-0 row
     * of its own (another body, another content-type) beside it. Witness that the later row is there: the timeline holds both.
     */
    @Test
    void aRerunStartsFromTheFirstAttemptsIngressEvenBesideALaterAttemptsStepZeroRow() throws Exception {
        w.partner.failNext(1, FlowWorkerRetryTest::downstream);
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(1, "1m"), List.of(TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        w.sent(worker, 1);

        io.flottila.api.Body other = w.rt.blobs().write(new io.flottila.api.BlobKey(in.id(), 0, "body~2"), Buffer.buffer("<not-what-arrived/>"), 65_536).await();
        w.store.record(new io.flottila.core.record.Snapshot(in.id(), "orders", 0, io.flottila.core.record.Recorder.INGRESS, io.flottila.core.record.Snapshot.Outcome.OK,
                Optional.empty(), w.t0, 0L, io.flottila.core.record.Snapshot.CPU_UNKNOWN, Headers.empty().with(Envelope.H_CONTENT_TYPE, "text/plain"),
                io.flottila.api.Props.empty().with(Envelope.ATTEMPT, 2), io.flottila.api.Keys.empty(), other.hash(), other.size(), Optional.of(other.key()), Map.of())).await();
        assertEquals(List.of(1, 2), w.store.timeline(in.id()).await().stream().filter(s -> s.step() == 0).map(io.flottila.core.record.Snapshot::attempt).toList(),
                "fixture: a step-0 row of attempt 2 beside attempt 1's (P2)");

        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(1)), 2);
        Envelope rerun = w.partner.sent().get(1);
        assertEquals(in.id(), rerun.id());
        assertEquals(Optional.of(2), rerun.get(Envelope.ATTEMPT), "the rerun runs as attempt 2");
        assertEquals("<order id=\"1\"/>", rerun.body().whole().toString(), "on what arrived: attempt 1's ingress body");
        assertEquals(Optional.of("application/xml"), rerun.header(Envelope.H_CONTENT_TYPE), "with attempt 1's ingress headers");
        assertEquals(in.body().key(), rerun.body().key());
    }
}
