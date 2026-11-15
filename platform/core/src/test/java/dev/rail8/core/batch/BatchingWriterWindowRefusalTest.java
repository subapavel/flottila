package dev.rail8.core.batch;

import dev.rail8.api.Timer;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-146: a timer that refuses the window schedule (throws) fails no item. The batches the drain just took are
 * committed, never failed; they reach the sink in the order taken and whatever stayed queued rides their completions,
 * so nothing is left queued with no commit in flight and no window. Every save / saveWaiting future completes, no item
 * reaches the sink twice, flush() completes, and the writer stays usable afterwards. A sink that completes inline may
 * re-enter the drain, so a younger batch can reach the sink before the outer loop's next one — a documented
 * non-guarantee, not asserted here.
 * The refusing timer refuses only the window delay, never a waiter's deadline (maxWait differs from the window).
 */
class BatchingWriterWindowRefusalTest {

    static final Duration WINDOW = Duration.ofMillis(50);
    static final Duration MAX_WAIT = Duration.ofSeconds(7);

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final RefusingWindowTimer timer = new RefusingWindowTimer(new FakeTimer(clock), WINDOW);
    final FakeSink sink = new FakeSink();

    /**
     * A FakeTimer that, while {@code refuse} is set, throws {@link #refusal} (one instance, a RuntimeException, an Error or —
     * FIX-235 decision 5 — a checked exception thrown without being declared) on every schedule of the window delay. A task
     * that throws on the timer's thread is recorded in {@code escaped} as a Vert.x timer would report it to
     * its context, not thrown at the test that advanced the clock; every case asserts {@code escaped} empty at its end.
     */
    static final class RefusingWindowTimer implements Timer {
        final FakeTimer inner;
        final Duration window;
        volatile Throwable refusal = new RejectedExecutionException("timer shut down");
        final AtomicInteger refused = new AtomicInteger();
        final List<Throwable> escaped = new CopyOnWriteArrayList<>();
        volatile boolean refuse;

        RefusingWindowTimer(FakeTimer inner, Duration window) {
            this.inner = inner;
            this.window = window;
        }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            if (refuse && delay.equals(window)) {
                refused.incrementAndGet();
                BatchingWriterErrorContainmentTest.sneak(refusal);   // throws the refusal itself, a checked one too
            }
            return inner.schedule(delay, () -> {
                try {
                    task.run();
                } catch (Throwable e) {   // an Error too: recorded, so the case sees an assertion and not a raw throw
                    escaped.add(e);
                }
            });
        }
    }

    /** save and saveWaiting both enter through the same path while the backlog has room: each gets the enqueue case. */
    enum Mode {
        SAVE { @Override Future<Void> save(BatchingWriter<String> w, String item) { return w.save(item); } },
        SAVE_WAITING { @Override Future<Void> save(BatchingWriter<String> w, String item) { return w.saveWaiting(item); } };

        abstract Future<Void> save(BatchingWriter<String> w, String item);
    }

    private BatchingWriter<String> writer(int batchSize, int concurrency, int maxBacklog) {
        sink.manual = true;
        return new BatchingWriter<>(sink, timer, new BatchingWriter.Options(batchSize, WINDOW, concurrency, maxBacklog, MAX_WAIT));
    }

    private List<String> flushed() {
        return sink.batches.stream().flatMap(List::stream).toList();
    }

    private void completeEveryOpenCommit() {
        for (int i = 0; sink.open() > 0; i++) {
            assertTrue(i < 50, "fixture: commits keep coming after 50 completions");
            sink.gate().complete();
        }
    }

    /** D-146: every future complete, every item in the sink exactly once, none failed — a refusal fails no item. */
    private void assertEverySettled(Map<String, Future<Void>> futures) {
        List<String> sunk = flushed();
        assertEquals(new HashSet<>(sunk).size(), sunk.size(), "no item reaches the sink twice: " + sunk);
        futures.forEach((item, f) -> {
            assertTrue(f.isComplete(), item + " never completed (left counted in flight or queued with no window): " + f + ", sink saw " + sunk);
            assertTrue(sunk.contains(item), item + " never reached the sink: a refused window commits what the drain took and what "
                    + "stayed queued rides its completion (D-146), it fails nobody; the sink saw " + sunk);
            assertTrue(f.succeeded(), item + " was handed to the sink and its commit succeeded, so it succeeds: " + f.cause());
        });
        assertEquals(List.of(), timer.escaped, "no window task threw on the timer's thread");
    }

    /** The refusal over, nothing is left counted: a later save on a working timer is handed to the sink and commits. */
    private void assertUsableAfterwards(BatchingWriter<String> w) {
        timer.refuse = false;
        assertEquals(0, w.inFlight(), "no commit is left counted in flight");
        assertEquals(0, w.backlog(), "nothing is left in the backlog");
        assertEquals(0, w.waiting(), "nobody is left waiting");
        int before = sink.batches.size();
        Future<Void> later = w.save("later");
        assertEquals(before + 1, sink.batches.size(), "an idle writer hands a later save to the sink at once");
        assertEquals(List.of("later"), sink.batches.getLast());
        sink.gate().complete();
        assertTrue(later.succeeded(), "the writer still commits after the refusal: " + later);
        assertEquals(0, w.inFlight());
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void aSaveWhoseWindowTheTimerRefusesGetsAFutureAndEveryItemCompletes(Mode mode) {
        timer.refuse = true;
        BatchingWriter<String> w = writer(2, 1, 10);
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        futures.put("a", mode.save(w, "a"));
        assertEquals(List.of("a"), flushed(), "fixture: an idle writer hands a to the sink at once");
        assertEquals(0, timer.refused.get(), "fixture: nothing waited, no window was asked for");
        var gateA = sink.gate();

        for (String item : List.of("b", "c", "d")) {
            futures.put(item, assertDoesNotThrow(() -> mode.save(w, item), "a refused window is never thrown at the saver of " + item));
        }
        assertTrue(timer.refused.get() >= 1, "fixture: the window for the partial batch was asked for and refused");

        gateA.complete();
        assertTrue(futures.get("a").succeeded(), "a was committed: it completes although the drain after it met a refusing timer: " + futures.get("a"));
        completeEveryOpenCommit();
        assertEverySettled(futures);
        assertUsableAfterwards(w);
    }

    @Test
    void aFlushWhoseWindowTheTimerRefusesCompletesAndSoDoesEveryItem() {
        BatchingWriter<String> w = writer(2, 1, 10);
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        futures.put("a", w.save("a"));
        var gateA = sink.gate();
        for (String item : List.of("b", "c", "d", "e")) futures.put(item, w.save(item));
        assertEquals(1, timer.inner.pending(), "fixture: the window for b..e is armed on a working timer");
        assertEquals(List.of("a"), flushed(), "fixture: b..e wait behind a's commit");

        timer.refuse = true;
        Future<Void> flush = assertDoesNotThrow(w::flush, "a refused window is never thrown at the flusher");
        assertTrue(timer.refused.get() >= 1, "fixture: flush gave up the armed window and was refused a new one");

        gateA.complete();
        assertTrue(futures.get("a").succeeded(), "a was committed: it completes although the drain after it met a refusing timer: " + futures.get("a"));
        completeEveryOpenCommit();
        assertTrue(flush.isComplete(), "flush completes once everything it saw completed: " + flush);
        assertEverySettled(futures);
        assertUsableAfterwards(w);
    }

    /**
     * a goes to the sink at once; b, c, d queue behind it with every window refused; a's completion drains: the drain takes
     * [b, c] and is refused the window for [d]. Returns with [b, c] handed to the sink (D-146) and d queued behind it.
     */
    private BatchingWriter<String> aRefusedWindowOnTheDrainAfterA(Map<String, Future<Void>> futures) {
        timer.refuse = true;
        BatchingWriter<String> w = writer(2, 1, 10);
        futures.put("a", w.save("a"));
        var gateA = sink.gate();
        for (String item : List.of("b", "c", "d")) {
            futures.put(item, assertDoesNotThrow(() -> w.save(item), "a refused window is never thrown at the saver of " + item));
        }
        int refusedBefore = timer.refused.get();
        assertTrue(refusedBefore >= 1, "fixture: the window for the partial batch was asked for and refused");
        gateA.complete();
        assertTrue(timer.refused.get() > refusedBefore, "fixture: the drain on a's completion asked for a window of its own and was refused");
        return w;
    }

    @Test
    void aRefusedWindowCommitsTheTakenBatchAndEveryItemSucceeds() {
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        BatchingWriter<String> w = aRefusedWindowOnTheDrainAfterA(futures);

        assertEquals(List.of(List.of("a"), List.of("b", "c")), sink.batches,
                "D-146: the drain whose window was refused hands the batch it took to the sink, oldest first");
        for (String item : List.of("b", "c")) {
            Future<Void> f = futures.get(item);
            assertFalse(f.failed(), item + " was in the batch taken when the window was refused: it is committed, not failed: " + f.cause());
        }
        assertEquals(1, w.backlog(), "d stayed queued behind the committed batch");
        assertEquals(1, w.inFlight(), "and it rides that commit: nothing is queued with no commit in flight and no window");

        sink.gate().complete();   // [b, c] commits; its completion drains d
        assertEquals(List.of("a", "b", "c", "d"), flushed(), "d rides the committed batch's completion; the sink saw arrival order");
        completeEveryOpenCommit();
        assertEverySettled(futures);
        assertUsableAfterwards(w);
    }

    @Test
    void aPermanentlyRefusedWindowDrainsTheWholeBacklogAndEveryWaiter() {
        BatchingWriter<String> w = writer(2, 1, 4);
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        futures.put("a", w.save("a"));
        var gateA = sink.gate();
        for (String item : List.of("b", "c", "d", "e")) futures.put(item, w.save(item));
        assertEquals(4, w.backlog(), "fixture: the backlog is full behind a's commit");
        for (String item : List.of("w1", "w2", "w3")) futures.put(item, w.saveWaiting(item));
        assertEquals(3, w.waiting(), "fixture: three wait for room");
        assertEquals(4, timer.inner.pending(), "fixture: the window and three deadlines are armed");

        timer.refuse = true;
        clock.advance(WINDOW);                                  // the window fires; every window asked for after it is refused
        assertTrue(timer.refused.get() >= 1, "fixture: the window task asked for the next window and was refused");
        assertEquals(List.of("a"), flushed(), "fixture: the slot is busy, nothing more was handed to the sink");

        assertEquals(3, w.waiting(), "fixture: flush is called while the three still wait for room");
        Future<Void> flush = assertDoesNotThrow(w::flush, "a refused window is never thrown at the flusher, waiters present");
        gateA.complete();
        completeEveryOpenCommit();                              // every commit the permanently refused writer still issues
        assertTrue(flush.isComplete(), "flush completes once everything it saw, the waiters included, completed: " + flush);

        assertEquals(0, w.backlog(), "a permanently refused window leaves nothing queued: the backlog rides the commits in flight");
        assertEquals(0, w.waiting(), "every waiter entered the backlog and was handed on");
        assertEquals(0, w.inFlight());
        futures.forEach((item, f) -> assertNotSame(timer.refusal, f.cause(),
                item + " was failed by the window refusal; D-146: a refusal fails no item"));
        assertEquals(List.of("a", "b", "c", "d", "e", "w1", "w2", "w3"), flushed(),
                "every item reached the sink once, the queue before the waiters, in arrival order");
        assertTrue(clock.instant().isBefore(Instant.EPOCH.plus(MAX_WAIT)), "fixture: no waiter's deadline has fired, each one entered");
        assertEverySettled(futures);
        assertUsableAfterwards(w);
    }

    @Test
    void aLaterFlushOnAWorkingTimerSucceedsOnceARefusedWindowsBatchHasCommitted() {
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        BatchingWriter<String> w = aRefusedWindowOnTheDrainAfterA(futures);
        completeEveryOpenCommit();
        assertEverySettled(futures);
        timer.refuse = false;

        Future<Void> e = w.save("e");
        Future<Void> flush = w.flush();
        assertFalse(flush.isComplete(), "flush waits for e, which is in flight: " + flush);
        sink.gate().complete();
        assertTrue(e.succeeded());
        assertTrue(flush.succeeded(), "the refused window left nothing counted in flight: a later flush waits only on e, got " + flush.cause());
        assertUsableAfterwards(w);
    }

    @Test
    void anErrorFromTheWindowScheduleIsCommittedAndTheWriterStaysUsable() {
        timer.refusal = new InternalError("timer cannot schedule");
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        BatchingWriter<String> w = aRefusedWindowOnTheDrainAfterA(futures);

        assertEquals(List.of(List.of("a"), List.of("b", "c")), sink.batches,
                "an Error from the window schedule commits the taken batch, as a RuntimeException does: " + sink.batches);
        for (String item : List.of("b", "c")) {
            Future<Void> f = futures.get(item);
            assertFalse(f.failed(), item + " was taken when the window threw an Error: it is committed, not failed: " + f.cause());
        }
        completeEveryOpenCommit();
        assertEverySettled(futures);

        timer.refuse = false;
        Future<Void> flush = w.flush();
        assertTrue(flush.succeeded(), "nothing is left in flight after an Error refusal: " + flush);
        assertUsableAfterwards(w);
    }

    /**
     * FIX-235 decision 5, beside {@link #anErrorFromTheWindowScheduleIsCommittedAndTheWriterStaysUsable}: a checked exception
     * thrown without being declared is a refusal like the others — no item fails, the refusal is counted.
     */
    @Test
    void aSneakedCheckedExceptionFromTheWindowScheduleIsCommittedAndTheWriterStaysUsable() {
        timer.refusal = new IOException("timer cannot schedule");
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        BatchingWriter<String> w = aRefusedWindowOnTheDrainAfterA(futures);

        assertEquals(List.of(List.of("a"), List.of("b", "c")), sink.batches,
                "a checked exception from the window schedule commits the taken batch, as a RuntimeException does: " + sink.batches);
        for (String item : List.of("b", "c")) {
            Future<Void> f = futures.get(item);
            assertFalse(f.failed(), item + " was taken when the window threw a checked exception: it is committed, not failed: " + f.cause());
        }
        assertEquals(timer.refused.get(), w.windowsRefused(), "every refusal the timer threw is counted, a checked one too");
        completeEveryOpenCommit();
        assertEverySettled(futures);

        timer.refuse = false;
        Future<Void> flush = w.flush();
        assertTrue(flush.succeeded(), "nothing is left in flight after a checked refusal: " + flush);
        assertUsableAfterwards(w);
    }
}
