package io.flottila.core.batch;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six cases of proto-m1's BatchingWriterTest, same intent, on Vert.x Future with the fake timer
 * instead of Thread.sleep; plus the options validation the prototype did not have. FIX-66 (F-13, D-071): {@code saveWaiting}
 * waits FIFO for room instead of failing, at most {@code maxWait} on the fake timer; {@code save} still fails at once.
 */
class BatchingWriterTest {

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final FakeTimer timer = new FakeTimer(clock);
    final FakeSink sink = new FakeSink();

    private BatchingWriter<String> writer(int batchSize, long windowMs, int concurrency, int maxBacklog) {
        return new BatchingWriter<>(sink, timer, new BatchingWriter.Options(batchSize, Duration.ofMillis(windowMs), concurrency, maxBacklog));
    }

    @Test
    void idleFlushIsImmediate() {
        BatchingWriter<String> w = writer(100, 5_000, 4, 6_400);
        assertTrue(w.save("a").succeeded(), "completes inline: the sink answered and the clock never moved");
        assertEquals(1, sink.batches.size());
        assertEquals(1, sink.batches.get(0).size());
        assertEquals(0, timer.pending(), "nothing left to wait for");
    }

    @Test
    void fullBatchFlushesWhileACommitIsInFlight() {
        sink.manual = true;
        BatchingWriter<String> w = writer(3, 60_000, 4, 192);
        Future<Void> first = w.save("a");
        var gateA = sink.gate();
        w.save("b"); w.save("c");
        Future<Void> lastOfBatch = w.save("d");       // third queued → full batch
        assertEquals(2, sink.batches.size(), "a full batch must flush despite the busy slot");
        assertEquals(3, sink.batches.get(1).size());
        assertFalse(first.isComplete());
        assertFalse(lastOfBatch.isComplete());
        assertEquals(2, w.inFlight(), "both batches are open");
        gateA.complete();
        assertTrue(first.succeeded());
        assertEquals(1, w.inFlight(), "the second batch is still open");
    }

    @Test
    void theWindowTimerFlushesAPartialBatch() {
        sink.manual = true;
        BatchingWriter<String> w = writer(100, 50, 4, 6_400);
        w.save("a");                                  // immediate: batch A in flight
        sink.gate();
        w.save("b"); w.save("c");                     // partial, below batchSize
        assertEquals(1, sink.batches.size(), "a partial batch waits for the window");
        assertEquals(2, w.backlog());
        clock.advance(Duration.ofMillis(49));
        assertEquals(1, sink.batches.size(), "not yet");
        clock.advance(Duration.ofMillis(1));
        assertEquals(2, sink.batches.size(), "the window flushes the partial batch");
        assertEquals(2, sink.batches.get(1).size());
        assertEquals(0, w.backlog());
    }

    @Test
    void concurrentCommitsCompletePerBatch() {
        sink.manual = true;
        BatchingWriter<String> w = writer(1, 0, 2, 64);
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        var gateB = sink.gate();
        Future<Void> c = w.save("c");                 // both slots busy → queued
        assertEquals(2, sink.batches.size());
        assertEquals(2, w.inFlight());
        gateB.complete();
        assertTrue(b.succeeded());
        assertFalse(a.isComplete(), "completing batch B must not complete batch A");
        assertEquals(3, sink.batches.size(), "c flushed when B's slot freed");
        sink.gate().complete();
        gateA.complete();
        assertTrue(a.succeeded());
        assertTrue(c.succeeded());
    }

    @Test
    void overflowFailsFastWithOverloaded() {
        sink.manual = true;
        BatchingWriter<String> w = writer(1, 0, 1, 64);
        w.save("busy"); sink.gate();                  // the only slot is busy
        for (int i = 0; i < 64; i++) w.save("q" + i); // fill the backlog
        assertEquals(64, w.backlog());
        Future<Void> failed = w.save("one too many");
        assertTrue(failed.failed(), "an over-capacity save fails immediately, it never waits");
        OverloadedException e = assertInstanceOf(OverloadedException.class, failed.cause());
        assertEquals(64L, e.capacity());
        assertTrue(e.getMessage().contains("64"));
        assertEquals(1, sink.batches.size(), "the rejected item never reaches the sink");
    }

    @Test
    void commitFailureFailsOnlyItsOwnBatch() {
        sink.manual = true;
        BatchingWriter<String> w = writer(1, 0, 2, 64);
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        var gateB = sink.gate();
        gateA.fail(new RuntimeException("db down"));
        assertTrue(a.failed());
        assertEquals("db down", a.cause().getMessage());
        assertFalse(b.isComplete(), "the failure of batch A must not touch batch B");
        gateB.complete();
        assertTrue(b.succeeded());
        assertEquals(0, w.inFlight());
    }

    @Test
    void aFailedBatchFreesItsSlotForTheQueue() {
        sink.manual = true;
        BatchingWriter<String> w = writer(1, 0, 1, 64);
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        assertEquals(1, sink.batches.size());
        gateA.fail(new RuntimeException("db down"));
        assertTrue(a.failed());
        assertEquals(2, sink.batches.size(), "b is committed after a's slot is freed");
        sink.gate().complete();
        assertTrue(b.succeeded());
    }

    @Test
    void optionsAreFiniteAndPositive() {
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(0, Duration.ZERO, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(1, Duration.ofMillis(-1), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(1, Duration.ZERO, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(1, Duration.ZERO, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(10, Duration.ZERO, 1, 9),
                "a backlog smaller than one batch can never fill a batch");
        BatchingWriter.Options defaults = BatchingWriter.Options.defaults();
        assertEquals(100, defaults.batchSize());
        assertEquals(Duration.ofMillis(10), defaults.window());
        assertEquals(4, defaults.commitConcurrency());
        assertEquals(6_400, defaults.maxBacklog(), "the prototype's batchSize × 64");
    }

    @Test
    void savesAreRejectedAfterClose() {
        BatchingWriter<String> w = writer(10, 10, 1, 100);
        assertTrue(w.save("a").succeeded());
        w.close();
        Future<Void> late = w.save("late");
        assertTrue(late.failed());
        assertInstanceOf(IllegalStateException.class, late.cause());
        assertEquals(1, sink.batches.size());
    }

    @Test
    void closeStillFlushesWhatWasQueued() {
        sink.manual = true;
        BatchingWriter<String> w = writer(10, 50, 2, 100);
        w.save("a"); var gateA = sink.gate();
        Future<Void> b = w.save("b");
        w.close();
        assertEquals(1, w.backlog(), "b waits for the window while a is in flight");
        clock.advance(Duration.ofMillis(50));          // the window still fires after close: b uses the second slot
        assertEquals(2, sink.batches.size(), "b was flushed by the window, not by a's completion");
        assertEquals(0, w.backlog());
        sink.gate().complete();
        assertTrue(b.succeeded(), "nothing accepted before close is lost");
        gateA.complete();
    }

    @Test
    void theSlotIsReleasedBeforeCallersAreCompleted() {
        sink.manual = true;
        BatchingWriter<String> w = writer(2, 0, 1, 10);
        Future<Void> a = w.save("a");                  // immediate: batch 1 = [a]
        var gateA = sink.gate();
        a.onSuccess(v -> {
            // a completion listener saving again: with the slot already free, its save is handed to the sink at once
            w.save("c");
            assertEquals(2, sink.batches.size(), "c committed inline from a's completion listener");
        });
        gateA.complete();
        assertTrue(a.succeeded());
        assertEquals(2, sink.batches.size());
        assertEquals(1, w.inFlight(), "c holds the only slot");
        sink.gate().complete();
        assertEquals(0, w.inFlight());
    }

    @Test
    void aThrowingCompletionListenerDoesNotSkipTheRestOfTheBatch() {
        sink.manual = true;
        BatchingWriter<String> w = writer(2, 0, 1, 10);
        Future<Void> first = w.save("first");          // immediate: batch 1 = [first]
        var gate1 = sink.gate();
        Future<Void> a = w.save("a");
        Future<Void> b = w.save("b");                  // batch 2 = [a, b], queued behind the busy slot
        a.onSuccess(v -> { throw new IllegalStateException("listener bug"); });
        gate1.complete();
        assertTrue(first.succeeded());
        assertEquals(2, sink.batches.size());
        sink.gate().complete();                        // a's listener throws (contained by Vert.x); b must still complete
        assertTrue(a.succeeded());
        assertTrue(b.succeeded(), "the throwing listener of a did not skip b");
        assertEquals(0, w.inFlight());
        w.save("c");
        assertEquals(3, sink.batches.size(), "the writer still works after the listener failure");
        sink.gate().complete();
    }

    // --- FIX-66 (F-13, D-071): saveWaiting — a full backlog makes the item wait, FIFO, at most maxWait

    /** Not the 10 s default: a writer that ignores the option fails these cases. */
    static final Duration MAX_WAIT = Duration.ofSeconds(7);

    /** One commit at a time, one batch of one, a backlog of {@code maxBacklog}, no window timer: the only timers are the waiters' deadlines. */
    private BatchingWriter<String> waitingWriter(int maxBacklog) {
        sink.manual = true;
        return new BatchingWriter<>(sink, timer, new BatchingWriter.Options(1, Duration.ZERO, 1, maxBacklog, MAX_WAIT));
    }

    /** {@code w} with its only slot busy ("busy" in flight) and its backlog full ("q0".."q(n-1)"): the next saveWaiting waits. */
    private static void fill(BatchingWriter<String> w, int maxBacklog) {
        assertFalse(w.save("busy").isComplete(), "fixture: busy holds the only slot");
        for (int i = 0; i < maxBacklog; i++) assertFalse(w.save("q" + i).isComplete(), "fixture: q" + i + " queued");
        assertEquals(maxBacklog, w.backlog(), "fixture: the backlog is full");
        assertEquals(1, w.inFlight());
    }

    private List<String> flushed() {
        return sink.batches.stream().flatMap(List::stream).toList();
    }

    @Test
    void aWaitingSaveEntersWhenACommitFreesRoomAndCommitsOnce() {
        BatchingWriter<String> w = waitingWriter(1);
        Future<Void> idle = w.saveWaiting("idle");
        assertEquals(0, w.waiting(), "with room a waiting save enters at once");
        assertEquals(List.of("idle"), flushed(), "and an idle writer hands it to the sink at once");
        assertEquals(0, timer.pending(), "an item that never waited arms no deadline");
        sink.gate().complete();
        assertTrue(idle.succeeded());
        fill(w, 1);

        Future<Void> waiter = w.saveWaiting("w");
        assertFalse(waiter.isComplete(), "a full backlog makes the item wait, it is not refused: " + waiter);
        assertEquals(1, w.waiting());
        assertEquals(1, w.backlog(), "a waiter is not in the backlog");
        assertEquals(1, timer.pending(), "one deadline for the one waiter");

        sink.gate().complete();                        // busy committed: q0 takes the slot, the backlog has room
        assertEquals(List.of("idle", "busy", "q0"), flushed());
        assertEquals(0, w.waiting(), "the waiter entered the moment the commit freed room, not at a later commit");
        assertEquals(1, w.backlog(), "it is the backlog now");
        assertEquals(0, timer.pending(), "its deadline was cancelled when it entered");
        assertFalse(waiter.isComplete(), "entered, not committed yet");

        sink.gate().complete();                        // q0 committed: w takes the slot
        assertEquals(List.of("idle", "busy", "q0", "w"), flushed());
        assertFalse(waiter.isComplete(), "handed to the sink, its commit still open");
        sink.gate().complete();
        assertTrue(waiter.succeeded(), "completes when the batch containing it is committed");
        clock.advance(MAX_WAIT.multipliedBy(2));
        assertEquals(List.of("idle", "busy", "q0", "w"), flushed(), "handed to the sink once");
        assertEquals(0, w.backlog());
        assertEquals(0, w.inFlight());
    }

    @Test
    void saveStillFailsAtOnceAndNeverOvertakesAWaiter() {
        BatchingWriter<String> w = waitingWriter(1);
        fill(w, 1);
        Future<Void> waiter = w.saveWaiting("w");
        assertEquals(1, w.waiting(), "fixture: w waits");

        Future<Void> early = w.save("early");
        assertTrue(early.isComplete(), "save never waits");
        assertInstanceOf(OverloadedException.class, early.cause(), "a full backlog still fails save at once: " + early.cause());

        sink.gate().complete();                        // busy committed: room for one — the waiter's, not a later save's
        assertEquals(0, w.waiting(), "fixture: w entered");
        assertEquals(1, w.backlog(), "the backlog is full again with the waiter in it");
        Future<Void> late = w.save("late");
        assertTrue(late.isComplete(), "save never waits");
        assertInstanceOf(OverloadedException.class, late.cause(), "the room the commit freed went to the waiter, not to a save that came after it: " + late.cause());

        sink.gate().complete();
        sink.gate().complete();
        assertTrue(waiter.succeeded());
        assertEquals(List.of("busy", "q0", "w"), flushed(), "the refused saves never reach the sink; the waiter is not overtaken");
    }

    @Test
    void waitersEnterInArrivalOrder() {
        BatchingWriter<String> w = waitingWriter(2);
        fill(w, 2);
        List<Future<Void>> waiters = List.of(w.saveWaiting("w1"), w.saveWaiting("w2"), w.saveWaiting("w3"), w.saveWaiting("w4"));
        assertEquals(4, w.waiting());
        assertEquals(4, timer.pending(), "one deadline per waiter");

        for (int expected = 3; expected >= 0; expected--) {
            sink.gate().complete();
            assertEquals(expected, w.waiting(), "each freed slot takes exactly one waiter");
            assertEquals(2, w.backlog(), "and the backlog stays full while anyone waits");
        }
        while (sink.open() > 0) sink.gate().complete();
        assertEquals(List.of("busy", "q0", "q1", "w1", "w2", "w3", "w4"), flushed(), "waiters enter in arrival order, behind what was queued");
        assertTrue(waiters.stream().allMatch(Future::succeeded), waiters.toString());
        assertEquals(0, timer.pending());
    }

    @Test
    void aWaiterStillWaitingAtMaxWaitFailsOverloadedAndNeverReachesTheSink() {
        BatchingWriter<String> w = waitingWriter(1);
        fill(w, 1);
        Future<Void> waiter = w.saveWaiting("w");
        assertEquals(MAX_WAIT, timer.nextDelay(), "its deadline is maxWait from the call");

        clock.advance(MAX_WAIT.minusMillis(1));
        assertFalse(waiter.isComplete(), "one millisecond before maxWait it still waits: " + waiter);
        assertEquals(1, w.waiting());

        clock.advance(Duration.ofMillis(1));
        assertTrue(waiter.failed(), "still waiting at maxWait: it fails");
        assertInstanceOf(OverloadedException.class, waiter.cause(), "as a full backlog does: " + waiter.cause());
        assertEquals(0, w.waiting(), "and it no longer waits");

        sink.gate().complete();                        // room frees after the deadline
        assertEquals(0, w.backlog(), "the room went to nobody: q0 is in flight and the failed waiter did not enter");
        while (sink.open() > 0) sink.gate().complete();
        assertEquals(List.of("busy", "q0"), flushed(), "a waiter failed at its deadline is never handed to the sink");
        assertEquals(0, w.backlog());
    }

    @Test
    void aWaiterThatEnteredBeforeMaxWaitIsNotFailedByItsTimer() {
        BatchingWriter<String> w = waitingWriter(1);
        fill(w, 1);
        Future<Void> waiter = w.saveWaiting("w");
        clock.advance(MAX_WAIT.minusMillis(1));

        sink.gate().complete();                        // the waiter enters one millisecond before its deadline
        assertEquals(0, w.waiting(), "fixture: w entered");
        assertEquals(0, timer.pending(), "its deadline was cancelled on entry");
        clock.advance(MAX_WAIT);                       // well past where the deadline was
        assertFalse(waiter.isComplete(), "an entered item is not failed by its old deadline: " + waiter);

        sink.gate().complete();
        assertEquals(List.of("busy", "q0", "w"), flushed());
        sink.gate().complete();
        assertTrue(waiter.succeeded(), "it commits like any queued item: " + waiter);
    }

    @Test
    void closeRefusesNewWaitersYetFlushCompletesOnlyAfterTheWaitingCommitted() {
        BatchingWriter<String> w = waitingWriter(1);
        fill(w, 1);
        Future<Void> waiter = w.saveWaiting("w");
        w.close();

        Future<Void> refused = w.saveWaiting("after-close");
        assertTrue(refused.isComplete(), "refused at once");
        assertInstanceOf(IllegalStateException.class, refused.cause(), "a closed writer refuses new waiters: " + refused.cause());
        assertFalse(waiter.isComplete(), "close does not fail a waiter that was already waiting: " + waiter);
        assertEquals(1, w.waiting());

        Future<Void> flushed = w.flush();
        assertFalse(flushed.isComplete());
        sink.gate().complete();                        // busy committed; q0 in flight, w enters
        assertEquals(0, w.waiting(), "a waiter of a closed writer still enters when room frees");
        sink.gate().complete();                        // q0 committed: everything flush saw in flight or queued is done
        assertFalse(flushed.isComplete(), "the flush also waits for the waiter: " + flushed);
        assertEquals(List.of("busy", "q0", "w"), flushed());
        sink.gate().complete();
        assertTrue(waiter.succeeded());
        assertTrue(flushed.succeeded(), "once the waiter committed the flush completes: " + flushed);
        assertEquals(List.of("busy", "q0", "w"), flushed(), "the refused item never reached the sink");
    }

    @Test
    void aTimerThatRefusesToScheduleFailsTheWaitingSaveAtOnce() {
        sink.manual = true;
        AtomicInteger asked = new AtomicInteger();
        io.flottila.api.Timer refusing = (delay, task) -> {
            asked.incrementAndGet();
            throw new RejectedExecutionException("timer shut down");
        };
        BatchingWriter<String> w = new BatchingWriter<>(sink, refusing, new BatchingWriter.Options(1, Duration.ZERO, 1, 1, MAX_WAIT));
        fill(w, 1);

        Future<Void> waiter = assertDoesNotThrow(() -> w.saveWaiting("w"), "a refusing timer is a failed future, not a throw");
        assertEquals(1, asked.get(), "the waiter asked for its deadline");
        assertTrue(waiter.isComplete(), "a waiter without a deadline is failed at once, never left waiting unbounded: " + waiter);
        assertTrue(waiter.failed());
        assertEquals(0, w.waiting());

        while (sink.open() > 0) sink.gate().complete();
        assertEquals(List.of("busy", "q0"), flushed(), "the refused item never reaches the sink");
    }

    /**
     * A deadline that fires as its waiter enters (on another thread: the entry's cancel comes too late and answers false) runs
     * after the item is already queued: it leaves the item alone — not failed, not removed, handed to the sink once.
     */
    @Test
    void aDeadlineThatFiresAfterItsWaiterEnteredDoesNotFailIt() {
        sink.manual = true;
        List<Runnable> deadlines = new java.util.ArrayList<>();
        AtomicInteger cancels = new AtomicInteger();
        io.flottila.api.Timer late = (delay, task) -> {   // keeps the task for the test to run; every cancel is too late
            deadlines.add(task);
            return () -> { cancels.incrementAndGet(); return false; };
        };
        BatchingWriter<String> w = new BatchingWriter<>(sink, late, new BatchingWriter.Options(1, Duration.ZERO, 1, 1, MAX_WAIT));
        fill(w, 1);
        Future<Void> waiter = w.saveWaiting("w");
        assertEquals(1, deadlines.size(), "fixture: the waiter's one deadline");

        sink.gate().complete();                        // busy committed: q0 takes the slot, w enters
        assertEquals(0, w.waiting(), "fixture: w entered");
        assertEquals(1, cancels.get(), "fixture: its entry asked to cancel the deadline, too late");
        deadlines.getFirst().run();                    // the deadline runs anyway, w queued behind q0
        assertFalse(waiter.isComplete(), "an entered item is not failed by its late deadline: " + waiter);
        assertEquals(0, w.waiting(), "the late deadline puts nothing back in line");
        assertEquals(1, w.backlog(), "w stays queued");

        sink.gate().complete();                        // q0 committed: w goes
        sink.gate().complete();
        assertTrue(waiter.succeeded(), "it lands: " + waiter);
        assertEquals(List.of("busy", "q0", "w"), flushed(), "handed to the sink once");
        assertEquals(0, sink.open());
    }

    @Test
    void optionsRefuseAZeroOrNegativeMaxWaitAndTheFourArgFormIs10s() {
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(1, Duration.ZERO, 1, 1, Duration.ZERO), "a zero maxWait");
        assertThrows(IllegalArgumentException.class, () -> new BatchingWriter.Options(1, Duration.ZERO, 1, 1, Duration.ofMillis(-1)), "a negative maxWait");
        assertThrows(NullPointerException.class, () -> new BatchingWriter.Options(1, Duration.ZERO, 1, 1, null));
        assertEquals(Duration.ofNanos(1), new BatchingWriter.Options(1, Duration.ZERO, 1, 1, Duration.ofNanos(1)).maxWait(), "any positive maxWait");
        assertEquals(Duration.ofSeconds(10), new BatchingWriter.Options(1, Duration.ZERO, 1, 1).maxWait(), "the four-argument form waits 10 s");
        assertEquals(Duration.ofSeconds(10), BatchingWriter.Options.defaults().maxWait(), "the defaults wait 10 s (the default stop deadline)");
        assertEquals(6_400, BatchingWriter.Options.defaults().maxBacklog(), "the backlog stays 6400");
    }
}
