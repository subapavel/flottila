package io.flottila.core.batch;

import io.flottila.api.Timer;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-69 / FIX-71 (D-104): a timer that refuses the window schedule (throws) leaves no item pending forever and fails
 * none. Every save / saveWaiting future completes and succeeds: the batches a drain took when its window could not be
 * scheduled are committed, in the order taken, and what stayed queued rides their completions; no item reaches the sink
 * twice; flush() completes; the writer stays usable afterwards.
 * The refusing timer refuses only the window delay, never a waiter's deadline (maxWait differs from the window).
 */
class BatchingWriterWindowRefusalTest {

    static final Duration WINDOW = Duration.ofMillis(50);
    static final Duration MAX_WAIT = Duration.ofSeconds(7);

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final RefusingWindowTimer timer = new RefusingWindowTimer(new FakeTimer(clock), WINDOW);
    final FakeSink sink = new FakeSink();

    /**
     * A FakeTimer that, while {@code refuse} is set, throws {@link #refusal} (one instance, a RuntimeException or an Error)
     * on every schedule of the window delay. A task that throws on the timer's thread is recorded in {@code escaped} as a Vert.x timer would report it to
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
                if (refusal instanceof Error e) throw e;
                throw (RuntimeException) refusal;
            }
            return inner.schedule(delay, () -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
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

    /** Every future complete and succeeded; every item handed to the sink exactly once (D-104: a refused window fails nothing). */
    private void assertEverySettled(Map<String, Future<Void>> futures) {
        List<String> sunk = flushed();
        assertEquals(new HashSet<>(sunk).size(), sunk.size(), "no item reaches the sink twice: " + sunk);
        futures.forEach((item, f) -> {
            assertTrue(f.isComplete(), item + " never completed (left counted in flight or queued with no window): " + f + ", sink saw " + sunk);
            assertTrue(sunk.contains(item), item + " never reached the sink: a refused window commits, it never drops a batch: " + f);
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

    @Test
    void theBatchTakenWhenTheWindowIsRefusedIsCommittedAndEveryWaiterCompletes() {
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
        clock.advance(WINDOW);                                  // the window fires; a's commit still holds the only slot
        assertTrue(timer.refused.get() >= 1, "fixture: the window task asked for the next window and was refused");
        assertEquals(List.of("a"), flushed(), "fixture: the slot is busy, nothing more was handed to the sink");

        gateA.complete();                                       // the drain takes [b, c], tops up w1, w2, and is refused a window
        assertTrue(futures.get("a").succeeded(), "a was committed: it completes although the drain after it met a refusing timer: " + futures.get("a"));
        assertEquals(List.of("a", "b", "c"), flushed(), "the batch taken when the window was refused is handed to the sink, not dropped");
        for (String item : List.of("b", "c")) {
            assertFalse(futures.get(item).isComplete(), item + " is in flight until its commit completes, it is not failed at once: " + futures.get(item));
        }
        assertEquals(1, w.inFlight(), "the committed batch holds the slot");

        Future<Void> flush = assertDoesNotThrow(w::flush, "a refused window is never thrown at the flusher");
        completeEveryOpenCommit();
        assertTrue(flush.isComplete(), "flush completes once everything it saw completed: " + flush);
        assertTrue(clock.instant().isBefore(Instant.EPOCH.plus(MAX_WAIT)), "fixture: no waiter's deadline has fired, each one completed by the writer");
        assertEverySettled(futures);
        assertUsableAfterwards(w);
    }

    /**
     * a goes at once; b, c, d queue behind it, their window refused; a's completion takes [b, c] and is refused a window for
     * [d]: [b, c] are committed all the same, and [d] rides their completion onto the freed slot.
     */
    private BatchingWriter<String> refusedBatchThenCommitted(Map<String, Future<Void>> futures) {
        timer.refuse = true;
        BatchingWriter<String> w = writer(2, 1, 10);
        futures.put("a", w.save("a"));
        var gateA = sink.gate();
        for (String item : List.of("b", "c", "d")) {
            futures.put(item, assertDoesNotThrow(() -> w.save(item), "a refused window is never thrown at the saver of " + item));
        }
        gateA.complete();
        assertEquals(List.of("a", "b", "c"), flushed(), "the batch taken when the window was refused reaches the sink; d stays queued behind it");
        sink.gate().complete();                                 // [b, c] commits; its completion drains d onto the freed slot
        for (String item : List.of("b", "c")) {
            assertTrue(futures.get(item).succeeded(), item + " was in the batch taken when the window was refused: it is committed: " + futures.get(item));
        }
        assertEquals(List.of("a", "b", "c", "d"), flushed(), "d rides the completion of the refused batch");
        completeEveryOpenCommit();
        assertEverySettled(futures);
        return w;
    }

    @Test
    void aLaterFlushOnAWorkingTimerWaitsOnlyOnItsOwnItemOnceARefusedBatchIsCommitted() {
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        BatchingWriter<String> w = refusedBatchThenCommitted(futures);
        timer.refuse = false;

        Future<Void> e = w.save("e");
        Future<Void> flush = w.flush();
        assertFalse(flush.isComplete(), "flush waits for e, which is in flight: " + flush);
        sink.gate().complete();
        assertTrue(e.succeeded());
        assertTrue(flush.succeeded(), "nothing else is in flight: a later flush waits only on e and succeeds, got " + flush.cause());
        assertUsableAfterwards(w);
    }

    @Test
    void anErrorFromTheWindowScheduleIsCommittedAndTheWriterStaysUsable() {
        timer.refusal = new InternalError("timer cannot schedule");
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        BatchingWriter<String> w = refusedBatchThenCommitted(futures);
        assertTrue(timer.refused.get() >= 1, "fixture: the timer refused with the Error");
        assertTrue(futures.get("b").succeeded() && futures.get("c").succeeded(), "the batch taken when the Error was thrown is committed");
        timer.refuse = false;
        Future<Void> flush = w.flush();
        assertTrue(flush.succeeded(), "nothing is left in flight after an Error refusal: " + flush);
        assertUsableAfterwards(w);
    }

    // --- FIX-71 (D-104): a refused window commits, it never fails

    @ParameterizedTest
    @EnumSource(Mode.class)
    void aRefusedWindowCommitsTheTakenBatchAndEveryItemSucceeds(Mode mode) {
        timer.refuse = true;
        BatchingWriter<String> w = writer(2, 1, 10);
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        futures.put("a", mode.save(w, "a"));
        var gateA = sink.gate();
        for (String item : List.of("b", "c", "d")) futures.put(item, mode.save(w, item));
        assertTrue(timer.refused.get() >= 1, "fixture: the window for the partial batch was asked for and refused");

        gateA.complete();                                       // takes [b, c], is refused a window for [d]
        assertEquals(List.of("a", "b", "c"), flushed(), "the taken batch reaches the sink although its drain was refused a window");
        assertFalse(futures.get("b").isComplete(), "b is in flight, not failed: " + futures.get("b"));
        assertEquals(1, w.backlog(), "d stays queued behind the committed batch");
        sink.gate().complete();                                 // [b, c] committed; its completion hands d to the sink
        assertEquals(List.of("a", "b", "c", "d"), flushed(), "d rides the completion of the refused batch");
        sink.gate().complete();
        futures.forEach((item, f) -> assertTrue(f.succeeded(), item + " succeeds, nothing is failed by a timer: " + f));
        assertEverySettled(futures);
        assertUsableAfterwards(w);
    }

    @Test
    void aPermanentlyRefusedWindowDrainsTheWholeBacklogAndEveryWaiter() {
        timer.refuse = true;
        BatchingWriter<String> w = writer(2, 1, 4);
        Map<String, Future<Void>> futures = new LinkedHashMap<>();
        futures.put("a", w.save("a"));
        for (String item : List.of("b", "c", "d", "e")) futures.put(item, w.save(item));
        for (String item : List.of("w1", "w2", "w3")) futures.put(item, w.saveWaiting(item));
        assertEquals(4, w.backlog(), "fixture: the backlog is full behind a");
        assertEquals(3, w.waiting(), "fixture: three wait for room");

        completeEveryOpenCommit();                              // no clock tick: only commit completions move the writer
        assertEquals(0, w.backlog(), "the whole backlog was drained");
        assertEquals(0, w.waiting(), "every waiter was drained");
        assertEquals(0, w.inFlight());
        assertEquals(List.of("a", "b", "c", "d", "e", "w1", "w2", "w3"), flushed(), "arrival order, once each");
        futures.forEach((item, f) -> assertTrue(f.succeeded(), item + " succeeds, nothing fails with the timer's cause: " + f));
        assertEverySettled(futures);
        assertUsableAfterwards(w);
    }
}
