package io.flottila.sim;

import io.flottila.api.Step;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static io.flottila.sim.Check.DRAINED;
import static io.flottila.sim.Check.KEY_ORDER;
import static io.flottila.sim.Check.PREDICTED;
import static io.flottila.sim.SimRuns.firstFailing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness beyond the packet's cases (P2-10a): the hang guard and a failing driver, the printed forms read back, and broken
 * worlds — public seams only — that fail the sub-checks the packet's variants leave alone, each named by its failure's detail.
 */
class SimHarnessTest {

    /** A run interrupted while it waits names the run still RUNNING, with the stack it is stuck in. */
    @Test
    void aHangNamesTheRunItWaitsOnWithItsStack() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CompletableFuture<Void> never = new CompletableFuture<>();
        World stuck = new World() {
            @Override public String name() { return "stuck"; }

            @Override public Step transform(Step sim) {
                return e -> {
                    inside.countDown();
                    never.join();   // neither held by the partner nor on a Retry backoff: unclassified
                    return sim.apply(e);
                };
            }
        };
        FutureTask<Verdict> run = new FutureTask<>(() -> Simulation.run(new SimSeed(1), SimConfig.V1, 50, stuck));
        Thread caller = Thread.ofVirtual().start(run);
        try {
            inside.await();
            caller.interrupt();   // what the per-seed hang guard does
            ExecutionException thrown = assertThrows(ExecutionException.class, run::get);
            IllegalStateException hang = assertInstanceOf(IllegalStateException.class, thrown.getCause());
            assertTrue(hang.getMessage().startsWith("SIM HANG seed=0x1 config=V1 ticks=50 world=stuck: the driver waits on\nrun "), hang.getMessage());
            assertTrue(hang.getMessage().contains("SimHarnessTest"), () -> "the stuck frame is not in the report:\n" + hang.getMessage());
            assertInstanceOf(InterruptedException.class, hang.getCause());
        } finally {
            never.complete(null);
        }
    }

    /** What the driver throws is an error naming the run, with the driver's failure as its cause. */
    @Test
    void aFailingDriverIsAnErrorNamingTheRun() {
        IllegalStateException boom = new IllegalStateException("boom");
        World failing = new World() {
            @Override public String name() { return "failing"; }

            @Override public void onUnit(Unit unit) { throw boom; }
        };
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> Simulation.run(new SimSeed(2), SimConfig.V1, 10, failing));
        assertSame(boom, thrown.getCause());
        assertTrue(thrown.getMessage().startsWith("SIM ERROR seed=0x2 config=V1 ticks=10 world=failing: "), thrown.getMessage());
    }

    @Test
    void aPassingRunReportsItsComparisonsAndHowToReplayIt() {
        Verdict v = SimRuns.run(new SimSeed(3), SimConfig.V1, 100, World.CORRECT);
        List<String> lines = v.report().lines().toList();
        assertEquals(2, lines.size(), v.report());
        assertTrue(lines.get(0).startsWith("SIM PASS seed=0x3 config=V1 ticks=100 world=correct compared={PREDICTED="), lines.get(0));
        assertTrue(lines.get(1).endsWith("-Dsim.seed=0x3 -Dsim.config=V1 -Dsim.ticks=100 -Dsim.world=correct"), lines.get(1));
    }

    @Test
    void onlyTheNamesPrintedReadBack() {
        assertThrows(IllegalArgumentException.class, () -> SimSeed.parse("2a"), "a seed printed without 0x");
        assertThrows(IllegalArgumentException.class, () -> SimConfig.named("V2"), "a config never defined");
        assertThrows(IllegalArgumentException.class, () -> SimConfig.named(SimConfig.V1.withWeight(Action.POLL, 1).name()),
                "a config built in a test is not looked up by name");
    }

    /** DOUBLE_ADMISSION fails both admission bounds: a send admitted at the limit, and a flow holding more than it. */
    @Test
    void doubleAdmissionFailsTheAtLimitSendAndTheHeldRequests() {
        Verdict v = firstFailing(new DoubleAdmission());
        assertTrue(details(v, Check.ADMISSION_BOUNDS).stream().anyMatch(d -> d.contains(" came at the limit (")), v::report);
        assertTrue(details(v, Check.ADMISSION_BOUNDS).stream().anyMatch(d -> d.startsWith("sync holds ")), v::report);
    }

    /** REORDERING_FAST_PATH fails the order of first receipts, never the one-message-per-key rule. */
    @Test
    void reorderingFastPathFailsTheOrderOfFirstReceiptsOnly() {
        Verdict v = firstFailing(new ReorderingFastPath());
        assertTrue(details(v, KEY_ORDER).stream().allMatch(d -> d.contains(") reached the partner after send #")), v::report);
    }

    /** Items queued without their key: two messages of a key reach the partner at once, and out of order. */
    @Test
    void anUnkeyedQueueFailsKeyOrderWithTwoMessagesOfAKeyAtThePartner() {
        Verdict v = firstFailing(new Unkeyed());
        assertEquals(EnumSet.of(KEY_ORDER), v.failedChecks(), v::report);
        assertTrue(details(v, KEY_ORDER).stream().anyMatch(d -> d.contains(" reached the partner while ")), v::report);
    }

    /** A queue that always says it holds items: the drain runs to its bound and DRAINED fails, nothing else. */
    @Test
    void aQueueThatNeverDrainsFailsDrained() {
        Verdict v = SimRuns.run(new SimSeed(1), SimConfig.V1, 100, new NeverDrained());
        assertEquals(EnumSet.of(DRAINED), v.failedChecks(), v::report);
        assertTrue(details(v, DRAINED).getFirst().startsWith("after the drain: runs 0, held 0, wakes 0, items left true, unanswered []"), v::report);
    }

    /** A transform that hands its input on unchanged: every sync Reply differs from the model's, and only PREDICTED fails. */
    @Test
    void aSkippedTransformFailsPredicted() {
        Verdict v = firstFailing(new World() {
            @Override public String name() { return "skipped-transform"; }

            @Override public Step transform(Step sim) { return e -> e; }
        });
        assertEquals(EnumSet.of(PREDICTED), v.failedChecks(), v::report);
        assertTrue(details(v, PREDICTED).stream().allMatch(d -> d.startsWith("sync-")), v::report);
    }

    private static List<String> details(Verdict v, Check check) {
        return v.failures().stream().filter(f -> f.check() == check).map(Verdict.Failure::detail).toList();
    }

    /** The at-least-once items enqueued without their partition key: any worker claims any of them. */
    static final class Unkeyed implements World {
        @Override public String name() { return "unkeyed-queue"; }

        @Override public WorkQueue queue(WorkQueue sim, Clock clock) {
            return new Delegating(sim) {
                @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
                    return sim.enqueue(messageId, flow, step, null);
                }
            };
        }
    }

    /** The queue answering every {@code hasItems} with true. */
    static final class NeverDrained implements World {
        @Override public String name() { return "never-drained"; }

        @Override public WorkQueue queue(WorkQueue sim, Clock clock) {
            return new Delegating(sim) {
                @Override public Future<Boolean> hasItems(String flow) {
                    return Future.succeededFuture(true);
                }
            };
        }
    }

    /** Every call to the simulation's queue as it is. */
    static class Delegating implements WorkQueue {
        private final WorkQueue sim;

        Delegating(WorkQueue sim) { this.sim = sim; }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
            return sim.enqueue(messageId, flow, step, partitionKey);
        }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return sim.claim(workerId, flow, max, lease); }
        @Override public Future<Boolean> complete(long seq, int attempts) { return sim.complete(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            return sim.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return sim.renew(seq, attempts, lease); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return sim.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return sim.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return sim.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return sim.hasItems(flow); }
    }
}
