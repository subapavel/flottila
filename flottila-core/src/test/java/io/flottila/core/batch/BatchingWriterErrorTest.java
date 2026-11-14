package io.flottila.core.batch;

import io.flottila.api.Timer;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-71 (a) and (c): no public method of {@link BatchingWriter} ever completes by throwing; an {@link Error} from the
 * timer (a waiter's deadline) or from the sink becomes the failure cause of the item it belongs to and of nothing else.
 * (c): one batch after another reaches the sink in the order taken. A window that flush dropped never fires.
 */
class BatchingWriterErrorTest {

    static final Duration WINDOW = Duration.ofMillis(50);
    static final Duration MAX_WAIT = Duration.ofSeconds(7);

    final FakeClock clock = FakeClock.at(Instant.EPOCH);

    /** A FakeTimer that, while {@code refuse} is set, throws {@code refusal} on every schedule of {@link #MAX_WAIT} (a waiter's deadline). */
    static final class DeadlineRefusingTimer implements Timer {
        final FakeTimer inner;
        final Error refusal = new InternalError("timer cannot schedule the deadline");
        volatile boolean refuse;

        DeadlineRefusingTimer(FakeTimer inner) { this.inner = inner; }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            if (refuse && delay.equals(MAX_WAIT)) throw refusal;
            return inner.schedule(delay, task);
        }
    }

    /** A manual sink: every write stays open until the test completes its gate; the writes whose index is in {@code throwOn} throw an Error. */
    static final class ScriptedSink implements BatchSink<String> {
        final List<List<String>> batches = new CopyOnWriteArrayList<>();
        final Deque<Promise<Void>> gates = new ArrayDeque<>();
        final Set<Integer> throwOn;
        final Error error = new InternalError("sink cannot write");

        ScriptedSink(Set<Integer> throwOn) { this.throwOn = throwOn; }

        @Override public Future<Void> write(List<String> batch) {
            int index = batches.size();
            batches.add(List.copyOf(batch));
            if (throwOn.contains(index)) throw error;
            Promise<Void> p = Promise.promise();
            synchronized (gates) { gates.add(p); }
            return p.future();
        }

        Promise<Void> gate() {
            synchronized (gates) { return gates.poll(); }
        }
    }

    // --- (a) waitLocked: an Error from the deadline's schedule

    @Test
    void anErrorFromAWaiterDeadlineScheduleFailsOnlyThatSaveWithoutThrowing() {
        DeadlineRefusingTimer timer = new DeadlineRefusingTimer(new FakeTimer(clock));
        FakeSink sink = new FakeSink();
        sink.manual = true;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(2, WINDOW, 1, 2, MAX_WAIT));
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        Future<Void> c = w.save("c");
        assertEquals(2, w.backlog(), "fixture: the backlog is full behind a's commit");

        timer.refuse = true;
        Future<Void> refused = assertDoesNotThrow(() -> w.saveWaiting("w"), "an Error from the deadline's schedule is never thrown at the saver");
        assertTrue(refused.failed(), "the save whose deadline could not be scheduled fails: " + refused);
        assertSame(timer.refusal, refused.cause(), "it fails with the Error itself as cause");
        assertEquals(0, w.waiting(), "it was not left waiting");

        timer.refuse = false;
        Future<Void> later = w.saveWaiting("later");
        assertEquals(1, w.waiting(), "the writer still lets a save wait once the timer works");
        gateA.complete();
        sink.gate().complete();
        sink.gate().complete();
        assertTrue(a.succeeded() && b.succeeded() && c.succeeded(), "the saves already queued were not touched by the refusal");
        assertTrue(later.succeeded(), "the later waiter commits: " + later);
        assertEquals(List.of("a", "b", "c", "later"), sink.batches.stream().flatMap(List::stream).toList(), "the refused item never reached the sink");
        assertEquals(0, w.inFlight());
    }

    // --- (a) commit: an Error from sink.write

    @Test
    void anErrorFromTheSinkFailsOnlyItsBatchAndReleasesItsSlot() {
        ScriptedSink sink = new ScriptedSink(Set.of(0));
        BatchingWriter<String> w = new BatchingWriter<>(sink, new FakeTimer(clock), new BatchingWriter.Options(2, WINDOW, 1, 10, MAX_WAIT));

        Future<Void> a = assertDoesNotThrow(() -> w.save("a"), "an Error from the sink is never thrown at the saver");
        assertTrue(a.failed(), "the item whose batch the sink threw on fails: " + a);
        assertSame(sink.error, a.cause(), "with the Error itself as cause");
        assertEquals(0, w.inFlight(), "the batch's slot is released");
        assertEquals(0, w.backlog());

        Future<Void> b = w.save("b");
        assertEquals(List.of(List.of("a"), List.of("b")), sink.batches, "the writer still hands a later save to the sink at once");
        sink.gate().complete();
        assertTrue(b.succeeded(), "and commits it: " + b);
        assertEquals(0, w.inFlight());
    }

    @Test
    void anErrorFromTheSinkInsideACompletionFailsOnlyThatBatchAndTheRestStillCommits() {
        ScriptedSink sink = new ScriptedSink(Set.of(1));       // the second write throws: the batch [b, c]
        BatchingWriter<String> w = new BatchingWriter<>(sink, new FakeTimer(clock), new BatchingWriter.Options(2, WINDOW, 1, 10, MAX_WAIT));
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        Future<Void> c = w.save("c");
        Future<Void> d = w.save("d");

        assertDoesNotThrow(() -> gateA.complete(), "an Error from a drain run by a completion does not escape the completing thread");
        assertTrue(a.succeeded(), "a was committed before the drain that met the Error: " + a);
        for (Future<Void> f : List.of(b, c)) {
            assertTrue(f.failed(), "the batch the sink threw on fails: " + f);
            assertSame(sink.error, f.cause());
        }
        assertEquals(List.of(List.of("a"), List.of("b", "c"), List.of("d")), sink.batches, "d rides the failed batch's completion onto the freed slot");
        sink.gate().complete();
        assertTrue(d.succeeded(), "d is not failed by its neighbour's Error: " + d);
        assertEquals(0, w.inFlight());
        assertEquals(0, w.backlog());
    }

    // --- (c) dispatch order

    @Test
    void theConcatenationOfTheBatchesInHandOffOrderIsArrivalOrderAcrossQueueAndWaiters() {
        // Known: the loop inside one drain is dead code, a freed slot is refilled one batch at a time, so the two batches
        // here come from two drains. This pins global FIFO (queue then waiters) and batchSize, not the intra-drain order.
        FakeSink sink = new FakeSink();
        sink.manual = true;
        BatchingWriter<String> w = new BatchingWriter<>(sink, new FakeTimer(clock), new BatchingWriter.Options(2, WINDOW, 2, 6, MAX_WAIT));
        List<String> arrival = new ArrayList<>();
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 12; i++) {                          // the backlog (6) fills, the rest waits: queue first, then waiters, FIFO
            String item = "i" + i;
            arrival.add(item);
            futures.add(w.saveWaiting(item));
        }
        assertEquals(List.of(List.of("i0"), List.of("i1", "i2")), sink.batches, "fixture: both slots are busy");
        assertTrue(w.waiting() > 0, "fixture: waiters exist, so the order spans queue and waiters");

        for (int i = 0; sink.open() > 0; i++) {
            assertTrue(i < 50, "fixture: commits keep coming");
            sink.gate().complete();
        }
        assertEquals(arrival, sink.batches.stream().flatMap(List::stream).toList(), "the concatenation of the batches, in hand-off order, is arrival order");
        for (List<String> b : sink.batches) assertTrue(b.size() <= 2);
        futures.forEach(f -> assertTrue(f.succeeded(), "" + f));
    }

    // --- (a) a fourth throw site: Cancellable.cancel()

    enum Throws {
        RUNTIME { @Override Throwable make() { return new IllegalStateException("cancel refused"); } },
        ERROR { @Override Throwable make() { return new InternalError("cancel refused"); } };

        abstract Throwable make();
    }

    /** A FakeTimer whose handles throw from cancel() while {@code armed}; the task is then NOT cancelled and still fires. */
    static final class CancelThrowingTimer implements Timer {
        final FakeTimer inner;
        volatile Throwable cancelThrows;

        CancelThrowingTimer(FakeTimer inner) { this.inner = inner; }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            Cancellable real = inner.schedule(delay, task);
            return () -> {
                Throwable t = cancelThrows;
                if (t instanceof Error e) throw e;
                if (t != null) throw (RuntimeException) t;
                return real.cancel();
            };
        }
    }

    /**
     * a goes; b, c fill the backlog; w waits with a deadline. a's completion takes [b, c] and moves w in, which cancels w's
     * deadline: that throws. Nothing is stranded, the slot is released, w commits, and the deadline that was never
     * cancelled and fires later finds w gone from the line ({@code expire}: {@code waiters.remove(w)} is false) and fails nothing.
     */
    @ParameterizedTest
    @EnumSource(Throws.class)
    void aCancelThatThrowsWhenAWaiterEntersTheBacklogStrandsNothingAndFailsNoSucceededItem(Throws kind) {
        CancelThrowingTimer timer = new CancelThrowingTimer(new FakeTimer(clock));
        FakeSink sink = new FakeSink();
        sink.manual = true;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(2, WINDOW, 1, 2, MAX_WAIT));
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        Future<Void> c = w.save("c");
        Future<Void> waiter = w.saveWaiting("w");
        assertEquals(1, w.waiting(), "fixture: w waits for room");

        timer.cancelThrows = kind.make();
        assertDoesNotThrow(() -> gateA.complete(), "a throwing cancel does not escape the completion that drained");
        assertTrue(a.succeeded(), "a was committed: its completion is not lost to the drain's throw: " + a);
        timer.cancelThrows = null;
        for (int i = 0; sink.open() > 0; i++) {
            assertTrue(i < 10, "fixture: commits keep coming");
            sink.gate().complete();
        }
        assertEquals(List.of("a", "b", "c", "w"), sink.batches.stream().flatMap(List::stream).toList(), "every item reached the sink once, in order");
        for (Future<Void> f : List.of(b, c, waiter)) assertTrue(f.succeeded(), "stranded or failed: " + f);
        assertEquals(0, w.inFlight(), "no commit slot is leaked");
        assertEquals(0, w.backlog());
        assertEquals(0, w.waiting());

        clock.advance(MAX_WAIT);                                // the deadline whose cancel failed fires for real
        assertTrue(waiter.succeeded(), "a late deadline never fails an item that already succeeded: " + waiter);
        Future<Void> later = w.save("later");
        sink.gate().complete();
        assertTrue(later.succeeded(), "the writer stays usable: " + later);
    }

    /** flush cancels the armed window: a cancel that throws neither escapes flush nor strands anything. */
    @ParameterizedTest
    @EnumSource(Throws.class)
    void aCancelThatThrowsWhileFlushDropsTheArmedWindowDoesNotEscapeAndFlushCompletes(Throws kind) {
        CancelThrowingTimer timer = new CancelThrowingTimer(new FakeTimer(clock));
        FakeSink sink = new FakeSink();
        sink.manual = true;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(2, WINDOW, 2, 10, MAX_WAIT));
        Future<Void> a = w.save("a");
        Future<Void> b = w.save("b");
        assertEquals(1, timer.inner.pending(), "fixture: the window for b is armed");

        timer.cancelThrows = kind.make();
        Future<Void> flush = assertDoesNotThrow(w::flush, "a throwing cancel is never thrown at the flusher");
        timer.cancelThrows = null;
        assertEquals(List.of(List.of("a"), List.of("b")), sink.batches, "flush handed b to the sink");
        for (int i = 0; sink.open() > 0; i++) {
            assertTrue(i < 10, "fixture: commits keep coming");
            sink.gate().complete();
        }
        assertTrue(a.succeeded() && b.succeeded(), "nothing stranded: " + a + " " + b);
        assertTrue(flush.succeeded(), "flush completes: " + flush);
        assertEquals(0, w.inFlight(), "no commit slot is leaked");
        clock.advance(WINDOW);                                  // the window whose cancel failed fires on an empty queue
        Future<Void> later = w.save("later");
        sink.gate().complete();
        assertTrue(later.succeeded(), "the writer stays usable: " + later);
    }

    /**
     * flush drops the armed window by cancelling it: the dropped window never fires. Worked example (reviewer, FIX-71 r5),
     * window 50 ms, batch 2, two commits in flight: at t0 a goes, b arms W1 (due t50), flush hands b over; at t10 c goes,
     * d arms W2 (due t60). At t50 only a dropped-but-live W1 could fire, and it would hand the partial batch [d] over ten
     * milliseconds before its own window: d must reach the sink at t60, not at t50.
     */
    @Test
    void aWindowThatFlushDroppedNeverFiresSoANewerPartialBatchWaitsForItsOwnWindow() {
        FakeTimer timer = new FakeTimer(clock);
        FakeSink sink = new FakeSink();
        sink.manual = true;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(2, WINDOW, 2, 10, MAX_WAIT));
        Future<Void> a = w.save("a");                           // t0: idle, a goes alone
        Future<Void> b = w.save("b");                           // a in flight: b waits, W1 armed for t50
        assertEquals(1, timer.pending(), "fixture: W1 is armed for b");
        Future<Void> flush = w.flush();                         // b goes on the second slot
        assertEquals(List.of(List.of("a"), List.of("b")), sink.batches, "fixture: flush handed b to the sink");
        sink.gate().complete();
        sink.gate().complete();
        assertTrue(a.succeeded() && b.succeeded() && flush.succeeded(), "fixture: a, b and the flush completed: " + flush);

        clock.advance(Duration.ofMillis(10));                   // t10
        Future<Void> c = w.save("c");                           // idle again: c goes alone
        Future<Void> d = w.save("d");                           // c in flight: d waits, W2 armed for t60
        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")), sink.batches, "fixture: d waits for its window");

        clock.advance(Duration.ofMillis(40));                   // t50: W1's due time
        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")), sink.batches,
                "at t50 d has not reached the sink: the window flush dropped did not fire and drain d before its own window");
        assertFalse(d.isComplete(), "d still waits at t50: " + d);

        clock.advance(Duration.ofMillis(10));                   // t60: W2's due time
        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c"), List.of("d")), sink.batches,
                "at t60 d's own window hands it to the sink");
        sink.gate().complete();
        sink.gate().complete();
        assertTrue(c.succeeded() && d.succeeded(), "c and d commit: " + c + " " + d);
        assertEquals(0, w.inFlight(), "no commit slot is leaked");
        assertEquals(0, timer.pending(), "no window is left scheduled");
    }

    // --- a throwing cancel() is logged once per writer, never per throw (no flood, no silence)

    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger flottila = Logger.getLogger("io.flottila");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @BeforeEach
    void listen() {
        capture.setLevel(Level.ALL);
        flottila.addHandler(capture);
    }

    @AfterEach
    void unlisten() {
        flottila.removeHandler(capture);
    }

    private long batchWarnings() {
        return logged.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()
                && r.getLoggerName() != null && r.getLoggerName().startsWith("io.flottila.core.batch")).count();
    }

    /**
     * Two waiters enter the backlog in one drain while every cancel() throws: two throwing cancels for the same writer, one
     * WARNING. The window timer never refuses here (only cancel does), so no window episode contributes to the count.
     */
    @Test
    void aCancelThatThrowsTwiceForTheSameWriterIsLoggedOnceAndStrandsNothing() {
        CancelThrowingTimer timer = new CancelThrowingTimer(new FakeTimer(clock));
        FakeSink sink = new FakeSink();
        sink.manual = true;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(2, WINDOW, 1, 2, MAX_WAIT));
        Future<Void> a = w.save("a");
        var gateA = sink.gate();
        Future<Void> b = w.save("b");
        Future<Void> c = w.save("c");
        Future<Void> w1 = w.saveWaiting("w1");
        Future<Void> w2 = w.saveWaiting("w2");
        assertEquals(2, w.waiting(), "fixture: both wait for room");
        assertEquals(0, batchWarnings(), "fixture: nothing logged before a cancel throws");

        timer.cancelThrows = new IllegalStateException("cancel refused");
        gateA.complete();                                       // takes [b, c] and moves w1 and w2 in: two throwing cancels
        assertEquals(0, w.waiting(), "fixture: both waiters entered, so both deadlines were cancelled (and threw)");
        assertEquals(1, batchWarnings(), "two throwing cancels for one writer log exactly one WARNING, not two and not none");

        for (int i = 0; sink.open() > 0; i++) {
            assertTrue(i < 10, "fixture: commits keep coming");
            sink.gate().complete();
        }
        assertEquals(1, batchWarnings(), "no further WARNING from the rest of the run");
        assertEquals(List.of("a", "b", "c", "w1", "w2"), sink.batches.stream().flatMap(List::stream).toList());
        for (Future<Void> f : List.of(a, b, c, w1, w2)) assertTrue(f.succeeded(), "stranded or failed: " + f);
        assertEquals(0, w.inFlight(), "no commit slot is leaked");
        timer.cancelThrows = null;
        Future<Void> later = w.save("later");
        sink.gate().complete();
        assertTrue(later.succeeded(), "the writer stays usable: " + later);
    }
}
