package io.rail8.core.batch;

import io.rail8.api.Timer;
import io.rail8.testkit.FakeClock;
import io.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-146's rule to pin: no public method of {@link BatchingWriter} ever completes by throwing — an {@code Error} from the
 * timer or from the sink becomes the failure cause of the item or batch it belongs to and of nothing else, the slot it
 * took is released, and the writer stays usable. The throw sites of the class are the waiter's deadline schedule, the
 * window schedule (in {@link BatchingWriterWindowRefusalTest}), {@code sink.write}, and the two cancels — the armed
 * window's in {@code flush} and the entering waiter's deadline — which no catch covers today.
 */
class BatchingWriterErrorContainmentTest {

    static final Duration WINDOW = Duration.ofMillis(50);
    static final Duration MAX_WAIT = Duration.ofSeconds(7);

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final FakeTimer inner = new FakeTimer(clock);
    final List<LogRecord> warnings = new CopyOnWriteArrayList<>();

    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { if (r.getLevel() == Level.WARNING) warnings.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    /** The two kinds of throw D-146 names: an unchecked exception and an Error, one instance each, identity-checked. */
    static List<Throwable> cancelRefusals() {
        return List.of(new IllegalStateException("cancel refused"), new InternalError("cancel refused"));
    }

    /**
     * A timer whose handles refuse to be cancelled: {@code cancel()} throws {@code refusal} while {@code refuseCancel} is set
     * and {@code refuses} accepts the delay the handle was scheduled with — the window's and a waiter's deadline's differ, so
     * one cancel site can refuse while the other is served. {@code cancelsRefused} counts the throws actually made.
     */
    static final class RefusingCancelTimer implements Timer {
        final Timer inner;
        final Throwable refusal;
        final AtomicInteger cancelsAttempted = new AtomicInteger();
        final AtomicInteger cancelsRefused = new AtomicInteger();
        volatile boolean refuseCancel = true;
        volatile Predicate<Duration> refuses = delay -> true;

        RefusingCancelTimer(Timer inner, Throwable refusal) {
            this.inner = inner;
            this.refusal = refusal;
        }

        @Override public Cancellable schedule(Duration delay, Runnable task) {
            Cancellable handle = inner.schedule(delay, task);
            return () -> {
                cancelsAttempted.incrementAndGet();
                if (refuseCancel && refuses.test(delay)) {
                    cancelsRefused.incrementAndGet();
                    if (refusal instanceof Error e) throw e;
                    throw (RuntimeException) refusal;
                }
                return handle.cancel();
            };
        }
    }

    /** Records every batch it is handed, then throws {@code boom} for the one holding {@code poison}; the rest stay open until gated. */
    static final class GatedSink implements BatchSink<String> {
        final List<List<String>> batches = new ArrayList<>();
        final Deque<Promise<Void>> gates = new ArrayDeque<>();
        volatile String poison;
        volatile Error boom;

        @Override public Future<Void> write(List<String> batch) {
            batches.add(List.copyOf(batch));
            if (poison != null && batch.contains(poison)) throw boom;
            Promise<Void> p = Promise.promise();
            synchronized (gates) { gates.add(p); }
            return p.future();
        }

        Promise<Void> gate() {
            Promise<Void> g;
            synchronized (gates) { g = gates.poll(); }
            assertNotNull(g, "expected a write in flight");
            return g;
        }

        int open() { synchronized (gates) { return gates.size(); } }

        List<String> flushed() { return batches.stream().flatMap(List::stream).toList(); }
    }

    @Test
    void anErrorFromAWaiterDeadlineScheduleFailsOnlyThatSaveWithoutThrowing() {
        FakeSink sink = new FakeSink();
        sink.manual = true;
        Error refusal = new InternalError("timer cannot schedule");
        AtomicInteger deadlinesAsked = new AtomicInteger();
        Timer deadlineThrows = (delay, task) -> {
            if (delay.equals(MAX_WAIT)) {
                deadlinesAsked.incrementAndGet();
                throw refusal;
            }
            return inner.schedule(delay, task);
        };
        BatchingWriter<String> w = new BatchingWriter<>(sink, deadlineThrows, new BatchingWriter.Options(1, WINDOW, 1, 1, MAX_WAIT));

        Future<Void> busy = w.save("busy");                 // takes the only slot
        Future<Void> queued = w.save("q0");                 // fills the backlog of one
        assertEquals(1, w.backlog(), "fixture: the backlog is full, so the next waiting save needs a deadline");

        Future<Void> waiter = assertDoesNotThrow(() -> w.saveWaiting("w"),
                "an Error from the deadline schedule is the save's failure cause, never a throw at saveWaiting");
        assertEquals(1, deadlinesAsked.get(), "fixture: the waiter asked for its deadline and the timer threw the Error");
        assertTrue(waiter.failed(), "the waiter is failed at once, never left in the line without a deadline: " + waiter);
        assertSame(refusal, waiter.cause(), "it fails with the timer's own Error as cause, got " + waiter.cause());
        assertEquals(0, w.waiting(), "nobody is left waiting");

        assertFalse(busy.isComplete(), "only that save failed: busy's commit is untouched");
        assertFalse(queued.isComplete(), "only that save failed: q0 is still queued");
        sink.gate().complete();
        assertTrue(busy.succeeded(), "busy committed: " + busy.cause());
        sink.gate().complete();
        assertTrue(queued.succeeded(), "q0 rode busy's completion and committed: " + queued.cause());
        assertEquals(List.of("busy", "q0"), sink.batches.stream().flatMap(List::stream).toList(),
                "the refused waiter never reaches the sink; the others do, in arrival order");

        assertEquals(0, w.inFlight(), "nothing is left counted in flight");
        Future<Void> later = w.save("later");
        sink.gate().complete();
        assertTrue(later.succeeded(), "the writer is still usable after the Error: " + later.cause());
    }

    @Test
    void anErrorFromTheSinkFailsOnlyItsBatchAndReleasesItsSlot() {
        GatedSink sink = new GatedSink();
        sink.boom = new InternalError("sink cannot write");
        sink.poison = "bad";
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(1, WINDOW, 2, 10, MAX_WAIT));

        Future<Void> ok1 = w.save("ok1");                   // slot one, left open
        Future<Void> bad = assertDoesNotThrow(() -> w.save("bad"),
                "an Error from sink.write is the batch's failure cause, never a throw at the saver");
        assertTrue(bad.failed(), "the batch the sink could not write is failed, not left pending forever: " + bad);
        assertSame(sink.boom, bad.cause(), "it fails with the sink's own Error as cause, got " + bad.cause());
        assertEquals(List.of(List.of("ok1"), List.of("bad")), sink.batches, "fixture: the sink was handed both batches");

        assertEquals(1, w.inFlight(), "the Error released the slot its batch took; only ok1's commit is still counted");
        assertFalse(ok1.isComplete(), "only its own batch failed: ok1's commit is untouched");
        sink.gate().complete();
        assertTrue(ok1.succeeded(), "ok1 committed: " + ok1.cause());
        assertEquals(0, w.inFlight(), "no commit is left counted in flight");
        assertEquals(0, w.backlog());

        sink.poison = null;
        Future<Void> later = w.save("later");
        assertEquals(List.of("later"), sink.batches.getLast(), "the writer is still usable: a later save reaches the sink");
        sink.gate().complete();
        assertTrue(later.succeeded(), "and commits: " + later.cause());
        assertEquals(List.of("ok1", "bad", "later"), sink.flushed(), "the failed batch was handed to the sink once, never retried");
    }

    /**
     * The first cancel site: {@code flush} cancels the armed window under the lock. A timer that refuses the cancel —
     * unchecked or an Error — must not throw it at the flusher; it is logged with its cause and every item still settles.
     */
    @ParameterizedTest
    @MethodSource("cancelRefusals")
    void aCancelRefusedByTheTimerNeverEscapesAPublicMethod(Throwable refusal) {
        FakeSink sink = new FakeSink();
        sink.manual = true;
        RefusingCancelTimer timer = new RefusingCancelTimer(inner, refusal);
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(2, WINDOW, 1, 10, MAX_WAIT));
        Logger log = Logger.getLogger(BatchingWriter.class.getName());
        log.addHandler(capture);
        try {
            Future<Void> a = w.save("a");                 // takes the only slot
            Future<Void> b = w.save("b");                 // queues behind it and arms the window
            assertEquals(1, inner.pending(), "fixture: the window for b is armed, so flush has a handle to cancel");

            Future<Void> flush = assertDoesNotThrow(w::flush, "a cancel the timer refuses is never thrown at the flusher");
            assertTrue(timer.cancelsAttempted.get() >= 1, "fixture: flush did try to cancel the armed window and the timer refused");

            timer.refuseCancel = false;
            for (int i = 0; i < 10 && sink.open() > 0; i++) sink.gate().complete();
            assertTrue(a.isComplete() && b.isComplete(), "no item is left unsettled by the refused cancel: a=" + a + ", b=" + b);
            assertTrue(a.succeeded(), "a committed: " + a.cause());
            assertTrue(b.succeeded(), "b committed: " + b.cause());
            assertTrue(flush.isComplete(), "flush completes once everything it saw committed: " + flush);
            assertEquals(0, w.inFlight(), "no commit is left counted in flight");
            assertEquals(0, w.backlog());

            Future<Void> later = w.save("later");
            assertEquals(List.of("later"), sink.batches.getLast(), "the writer is still usable after the refused cancel");
            sink.gate().complete();
            assertTrue(later.succeeded(), "and still commits: " + later.cause());
            assertTrue(warnings.stream().anyMatch(r -> r.getThrown() == refusal),
                    "the refused cancel is logged with its cause, never swallowed; WARNINGs seen: "
                            + warnings.stream().map(LogRecord::getMessage).toList());
        } finally {
            log.removeHandler(capture);
        }
    }

    /**
     * The second cancel site: the drain cancels an entering waiter's deadline, and it is reachable only from a commit
     * completion (while a waiter exists the backlog is full, so every free slot has already taken a batch and save,
     * saveWaiting and flush all return before that loop). A refused cancel there must not be carried out of the writer
     * and must leave no item unsettled — the merge's {@code Future{unresolved}}.
     */
    @ParameterizedTest
    @MethodSource("cancelRefusals")
    void aCancelRefusedWhenAWaiterEntersLeavesNoItemUnsettled(Throwable refusal) {
        FakeSink sink = new FakeSink();
        sink.manual = true;
        RefusingCancelTimer timer = new RefusingCancelTimer(inner, refusal);
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(1, WINDOW, 1, 1, MAX_WAIT));
        Logger log = Logger.getLogger(BatchingWriter.class.getName());
        log.addHandler(capture);
        try {
            Future<Void> busy = w.save("busy");           // takes the only slot
            Future<Void> queued = w.save("q0");           // fills the backlog of one
            Future<Void> waiter = w.saveWaiting("w");     // waits, its deadline armed on the refusing timer
            assertEquals(1, w.waiting(), "fixture: w waits for room behind a full backlog");

            Promise<Void> gate = sink.gate();
            assertDoesNotThrow(() -> { gate.complete(); }, "the cancel refused as the waiter enters is never carried out of the writer");
            assertTrue(timer.cancelsAttempted.get() >= 1, "fixture: the entering waiter's deadline was cancelled and the timer refused");

            timer.refuseCancel = false;
            for (int i = 0; i < 10 && sink.open() > 0; i++) sink.gate().complete();
            assertTrue(busy.isComplete() && queued.isComplete() && waiter.isComplete(),
                    "the refused cancel left an item unresolved: busy=" + busy + ", q0=" + queued + ", w=" + waiter);
            assertTrue(busy.succeeded(), "busy committed: " + busy.cause());
            assertTrue(queued.isComplete(), "q0 is settled, not left unresolved by the refused cancel: " + queued);
            assertTrue(waiter.isComplete(), "the entering waiter is settled, not left unresolved by the refused cancel: " + waiter);
            assertEquals(0, w.waiting(), "nobody is left waiting");
            assertEquals(0, w.backlog());
            assertEquals(0, w.inFlight(), "no commit is left counted in flight");

            Future<Void> later = w.save("later");
            assertEquals(List.of("later"), sink.batches.getLast(), "the writer is still usable after the refused cancel");
            sink.gate().complete();
            assertTrue(later.succeeded(), "and still commits: " + later.cause());
            assertTrue(warnings.stream().anyMatch(r -> r.getThrown() == refusal),
                    "the refused cancel is logged with its cause, never swallowed; WARNINGs seen: "
                            + warnings.stream().map(LogRecord::getMessage).toList());
        } finally {
            log.removeHandler(capture);
        }
    }
}
