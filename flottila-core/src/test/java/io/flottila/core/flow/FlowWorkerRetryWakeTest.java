package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.api.NackException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.flottila.core.flow.FlowWorkerRetryTest.FAIL;
import static io.flottila.core.flow.FlowWorkerRetryTest.LEASE;
import static io.flottila.core.flow.FlowWorkerRetryTest.REWRITE;
import static io.flottila.core.flow.FlowWorkerRetryTest.downstream;
import static io.flottila.core.flow.FlowWorkerRetryTest.flow;
import static io.flottila.core.flow.FlowWorkerRetryTest.retries;
import static io.flottila.core.flow.FlowWorkerTest.NO_FILES;
import static io.flottila.core.flow.FlowWorkerTest.TO_PARTNER;
import static io.flottila.core.flow.FlowWorkerTest.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14a (implementer): the cases {@link FlowWorkerRetryTest} leaves open, on its {@link FlowWorkerRetryTest.World} — the
 * worker's reconciliation a week apart, so every retry at its due is the worker's own wake (P4). Several dues in any order each
 * run at their own time; a cause chain that loops without a Fault is retried, not final (P2); a defer whose row was settled
 * elsewhere meanwhile is stale (P3); a final failure while the fence is closed parks nothing (D-101 (2)).
 */
@Timeout(60)
class FlowWorkerRetryWakeTest {

    final FlowWorkerRetryTest.World w = new FlowWorkerRetryTest.World();

    @AfterEach
    void tearDown() {
        w.close();
    }

    /** At 1 ms before {@code at} the fail step ran {@code before} times and the partner saw {@code sent} sends; then the clock is at {@code at}. */
    void nothingBefore(Instant at, int before, int sent) {
        w.rt.clock().set(at.minusMillis(1));
        assertEquals(before, w.failRuns.get(), "no run before " + at);
        assertEquals(sent, w.partner.sent().size(), "no send before " + at);
        w.rt.clock().set(at);
    }

    void runs(FlowWorker worker, int n) {
        until(() -> w.failRuns.get() >= n, () -> n + " runs of the fail step: " + w.failRuns.get());
        w.settled(worker);
        assertEquals(n, w.failRuns.get(), "exactly " + n + " runs");
    }

    /**
     * Retries 3 × 1m, multiplier 10, capped at 1h. A fails at 0 (due 1m); B fails at 30s (due 1m30s, after the armed wake); A's
     * retry at 1m fails (due 11m) while B's due is still ahead; B's retry at 1m30s passes; C fails at 2m (due 3m, before A's 11m)
     * and its retry passes at 3m; A's last retry passes at 11m. Each runs at its due, never before, with no poll of the test.
     */
    @Test
    void eachDueRunsAtItsOwnTimeWhateverTheOrderTheyCameIn() throws Exception {
        Map<String, Object> schedule = new HashMap<>(retries(3, "1m"));
        schedule.put("multiplier", 10);
        schedule.put("maxInterval", "1h");
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(schedule, List.of(REWRITE, FAIL, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Delivery delivery = b.delivery(def, NO_FILES);

        w.failures.add(FlowWorkerRetryTest::downstream);
        Envelope a = w.accept(delivery);
        runs(worker, 1);
        w.rt.clock().set(w.t0.plusSeconds(30));
        w.failures.add(FlowWorkerRetryTest::downstream);
        Envelope bee = w.accept(delivery);
        runs(worker, 2);
        assertEquals(List.of(Duration.ofMinutes(1), Duration.ofMinutes(1)), w.queue.deferCalls().stream().map(RecordingWorkQueue.DeferCall::delay).toList());

        w.failures.add(FlowWorkerRetryTest::downstream);
        nothingBefore(w.t0.plus(Duration.ofMinutes(1)), 2, 0);
        runs(worker, 3);   // A's retry, failing again
        assertEquals(Duration.ofMinutes(10), w.queue.deferCalls().get(2).delay(), "A's second wait");
        nothingBefore(w.t0.plus(Duration.ofSeconds(90)), 3, 0);
        w.sent(worker, 1);   // B's retry, through
        w.rt.clock().set(w.t0.plus(Duration.ofMinutes(2)));
        w.failures.add(FlowWorkerRetryTest::downstream);
        Envelope c = w.accept(delivery);
        runs(worker, 5);
        nothingBefore(w.t0.plus(Duration.ofMinutes(3)), 5, 1);
        w.sent(worker, 2);   // C's retry, through, before A's later due
        nothingBefore(w.t0.plus(Duration.ofMinutes(11)), 6, 2);
        w.sent(worker, 3);   // A's last retry, through

        assertEquals(List.of(bee.id(), c.id(), a.id()), w.sentIds(), "each at its due");
        assertEquals(Optional.of(3), w.partner.sent().get(2).get(Envelope.ATTEMPT), "A ran as attempt 3");
        assertEquals(List.of(), w.dlq(), "nothing parked");
        assertEquals(0, w.rows.size(), "every row completed");
    }

    /** A cause chain that loops back on itself without a Fault is no final failure: it is deferred and its retry runs (P2). */
    @Test
    void aCauseChainThatLoopsWithoutAFaultIsRetried() throws Exception {
        w.failures.add(() -> {
            IllegalStateException outer = new IllegalStateException("outer");
            IllegalArgumentException inner = new IllegalArgumentException("inner", outer);
            outer.initCause(inner);   // outer → inner → outer
            return outer;
        });
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(1, "1m"), List.of(REWRITE, FAIL, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        runs(worker, 1);
        assertEquals(1, w.queue.deferCalls().size(), "deferred: " + w.queue.deferCalls());
        assertEquals(List.of(), w.dlq(), "not parked");

        w.retryAt(worker, "n1", w.t0.plus(Duration.ofMinutes(1)), 1);
        assertEquals(List.of(in.id()), w.sentIds(), "its retry went through");
        assertEquals(0, w.rows.size());
    }

    /**
     * The row was settled elsewhere while the run failed: the defer, fenced by the hand-out's token, matches nothing — stale,
     * counted, nothing parked — and no wake runs it at the interval (P3).
     */
    @Test
    void aDeferOfARowSettledMeanwhileIsStale() throws Exception {
        AtomicBoolean completedElsewhere = new AtomicBoolean();
        w.failures.add(() -> {
            completedElsewhere.set(w.rows.complete(1, 1).await());   // the queue's first row, its first hand-out
            return downstream();
        });
        FlowBuilder b = w.builder();
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1m"), List.of(REWRITE, FAIL, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        w.accept(b.delivery(def, NO_FILES));
        runs(worker, 1);
        assertTrue(completedElsewhere.get(), "fixture: the row was completed by another hand during the run");

        assertEquals(1, w.queue.deferCalls().size(), "the defer was asked: " + w.queue.deferCalls());
        assertEquals(Boolean.FALSE, w.queue.deferCalls().get(0).answer(), "and matched nothing");
        assertEquals(1, worker.staleSettles(), "counted stale");
        assertEquals(List.of(), w.dlq(), "nothing parked");
        w.rt.clock().set(w.t0.plus(Duration.ofMinutes(1)));
        w.settled(worker);
        assertEquals(1, w.failRuns.get(), "nothing runs at the interval");
        assertEquals(0, w.rows.size());
    }

    /** A final failure while the node's fence is closed parks nothing (a park is a settle); reopened, the lease end reruns it. */
    @Test
    void aFinalFailureWhileTheFenceIsClosedParksNothing() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        w.failures.add(() -> {
            open.set(false);   // the fence closes during the failing run
            return new NackException("refused for good", false);
        });
        FlowBuilder b = w.builder(open::get);
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1h"), List.of(REWRITE, FAIL, TO_PARTNER)));
        FlowWorker worker = w.started(b, def, "n1");
        Envelope in = w.accept(b.delivery(def, NO_FILES));
        runs(worker, 1);
        assertEquals(List.of(), w.dlq(), "no park from a closed fence");
        assertEquals(List.of(), w.queue.deferCalls(), "nor a defer");
        assertEquals(1, w.rows.size(), "left unsettled");

        open.set(true);
        w.rt.clock().advance(LEASE);
        w.sent(worker, 1);
        assertEquals(List.of(in.id()), w.sentIds(), "its lease end ran it again");
        assertEquals(List.of(), w.dlq());
    }

    /**
     * Review r2 I4: a run that left its item to its lease logged why, so a settle under a fence still closed adds no WARNING of
     * its own — one WARNING per item. Here a final failure while the fence closes and stays closed through the settle.
     */
    @Test
    void aRunLeftToItsLeaseUnderAFenceStillClosedAtTheSettleLogsOneWarning() throws Exception {
        AtomicBoolean open = new AtomicBoolean(true);
        w.failures.add(() -> {
            open.set(false);   // closes during the run and stays closed through its settle
            return new NackException("refused for good", false);
        });
        FlowBuilder b = w.builder(open::get);
        FlowDefinition def = FlowDefinition.from(flow(retries(3, "1h"), List.of(REWRITE, FAIL, TO_PARTNER)));
        FlowWorkerTest.Logs logs = FlowWorkerTest.Logs.attach();
        try {
            FlowWorker worker = w.started(b, def, "n1");
            Envelope in = w.accept(b.delivery(def, NO_FILES));
            runs(worker, 1);
            List<String> warnings = logs.at(java.util.logging.Level.WARNING, in.id());
            assertEquals(1, warnings.size(), "one WARNING for the item: " + logs.all());
            assertTrue(warnings.get(0).contains("is not parked: the node's fence is closed"), warnings.get(0));
            assertEquals(List.of(), w.dlq(), "not parked");
            assertEquals(1, w.rows.size(), "left unsettled");
        } finally {
            logs.detach();
        }
    }
}
