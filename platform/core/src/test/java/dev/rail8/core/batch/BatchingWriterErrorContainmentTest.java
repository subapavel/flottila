package dev.rail8.core.batch;

import dev.rail8.api.Timer;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.AsyncResult;
import io.vertx.core.Completable;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-146's rule to pin: no public method of {@link BatchingWriter} ever completes by throwing — an {@code Error} from the
 * timer or from the sink becomes the failure cause of the item or batch it belongs to and of nothing else, the slot it
 * took is released, and the writer stays usable. The throw sites of the class are the waiter's deadline schedule, the
 * window schedule (in {@link BatchingWriterWindowRefusalTest}), {@code sink.write}, and the two cancels — the armed
 * window's in {@code flush} and the entering waiter's deadline — which no catch covers today.
 *
 * <p>FIX-235 (D-146 amended): nothing thrown by code the writer was given — the sink, the timer and its handles, the
 * weight function; any {@code Throwable}, a checked exception thrown without being declared too — leaves a public method.
 * A sink that answers {@code null} fails that batch with a {@code NullPointerException} that names the fault, from every
 * path that dispatches it (a save, a flush, the window's task, an earlier batch's completion); a throwing weight function
 * fails that save with the thrown object itself, a negative weight with an {@code IllegalArgumentException}; a sink's
 * future that fails without a cause still fails its batch. A fault that fails an item is not logged: the failed future is
 * its report. The one throw left is the writer's own argument check, a null item.
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

    /** Every record of the writer's logger, at any level, while {@link #capturingEverything} runs. */
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();

    /** Runs {@code body} with the writer's logger open at every level and each record it publishes kept in {@link #logged}. */
    private void capturingEverything(Runnable body) {
        capturingEverything(logged, body);
    }

    /**
     * Runs {@code body} with the writer's logger open at every level and each record it publishes added to {@code into};
     * package-private: {@code BatchingWriterWeightTest} pins decision 7 (no log for a fault that fails an item) with it too.
     */
    static void capturingEverything(List<LogRecord> into, Runnable body) {
        Handler everything = new Handler() {
            @Override public void publish(LogRecord r) { into.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger log = Logger.getLogger(BatchingWriter.class.getName());
        Level was = log.getLevel();
        log.setLevel(Level.ALL);
        log.addHandler(everything);
        try {
            body.run();
        } finally {
            log.removeHandler(everything);
            log.setLevel(was);
        }
    }

    /**
     * Throws {@code t} itself — a checked exception too — without declaring it: the shape of a generic rethrow or of another
     * JVM language, which a {@code catch (RuntimeException | Error)} lets pass (FIX-235 decision 5).
     */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> void sneak(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * The kinds of throw D-146 names, one instance each, identity-checked: an unchecked exception, an Error, and (FIX-235
     * decision 5) a checked exception thrown without being declared.
     */
    static List<Throwable> cancelRefusals() {
        return List.of(new IllegalStateException("cancel refused"), new InternalError("cancel refused"), new IOException("cancel refused"));
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
                    sneak(refusal);                     // throws the refusal itself, a checked one too
                }
                return handle.cancel();
            };
        }
    }

    /**
     * Records every batch it is handed, then throws {@code boom} (itself, a checked one too) for the one holding {@code poison},
     * answers {@code null} for those {@code nullFor} accepts and {@link #failedWithoutACause()} for those {@code causelessFor}
     * accepts; the rest stay open until gated.
     */
    static final class GatedSink implements BatchSink<String> {
        final List<List<String>> batches = new ArrayList<>();
        final Deque<Promise<Void>> gates = new ArrayDeque<>();
        volatile String poison;
        volatile Throwable boom;
        volatile Predicate<List<String>> nullFor = batch -> false;
        volatile Predicate<List<String>> causelessFor = batch -> false;

        @Override public Future<Void> write(List<String> batch) {
            batches.add(List.copyOf(batch));
            if (poison != null && batch.contains(poison)) sneak(boom);
            if (nullFor.test(batch)) return null;
            if (causelessFor.test(batch)) return failedWithoutACause();
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
            assertFalse(waiter.isComplete(),
                    "D-146: a refused cancel fails no item - w entered the backlog and waits for its commit: " + waiter);
            assertEquals(1, w.backlog(), "fixture: w is queued behind q0's commit, not yet handed to the sink");
            assertEquals(2, inner.pending(), "fixture: the armed window and w's deadline, whose cancel was refused, are both pending");

            clock.advance(MAX_WAIT.plusMillis(1));        // past w's deadline while w is queued and not yet committed
            assertEquals(1, inner.pending(), "fixture: w's stale deadline fired; only the window armed anew is pending");
            assertFalse(waiter.isComplete(),
                    "D-146: the stale deadline of a refused cancel fails nobody - w is still queued for commit: " + waiter);
            assertEquals(1, w.backlog(), "w stays queued, the stale deadline did not take it out of the backlog");

            timer.refuseCancel = false;
            for (int i = 0; i < 10 && sink.open() > 0; i++) sink.gate().complete();
            assertTrue(busy.isComplete() && queued.isComplete() && waiter.isComplete(),
                    "the refused cancel left an item unresolved: busy=" + busy + ", q0=" + queued + ", w=" + waiter);
            assertTrue(busy.succeeded(), "busy committed: " + busy.cause());
            assertTrue(queued.succeeded(), "q0 committed, no item is failed by a refused cancel (D-146): " + queued.cause());
            assertTrue(waiter.succeeded(), "the entering waiter committed, no item is failed by a refused cancel (D-146): " + waiter.cause());
            assertEquals(List.of("busy", "q0", "w"), sink.batches.stream().flatMap(List::stream).toList(),
                    "every item reached the sink once, in arrival order");
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

    // ---- FIX-235 decision 5: a checked exception thrown without being declared, at the sites whose cases are single

    /** Beside {@link #anErrorFromTheSinkFailsOnlyItsBatchAndReleasesItsSlot}: the same outcome for a sneaked IOException. */
    @Test
    void aSneakedCheckedExceptionFromTheSinkFailsOnlyItsBatchAndReleasesItsSlot() {
        GatedSink sink = new GatedSink();
        sink.boom = new IOException("sink cannot write");
        sink.poison = "bad";
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(1, WINDOW, 2, 10, MAX_WAIT));

        Future<Void> ok1 = w.save("ok1");                   // slot one, left open
        Future<Void> bad = assertDoesNotThrow(() -> w.save("bad"),
                "a checked exception from sink.write is the batch's failure cause, never a throw at the saver");
        assertEquals(List.of(List.of("ok1"), List.of("bad")), sink.batches, "fixture: the sink was handed both batches");
        assertTrue(bad.failed(), "the batch the sink could not write is failed, not left pending forever: " + bad);
        assertSame(sink.boom, bad.cause(), "it fails with the sink's own exception as cause, got " + bad.cause());

        assertEquals(1, w.inFlight(), "the exception released the slot its batch took; only ok1's commit is still counted");
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
    }

    /** Beside {@link #anErrorFromAWaiterDeadlineScheduleFailsOnlyThatSaveWithoutThrowing}: the same outcome for a sneaked IOException. */
    @Test
    void aSneakedCheckedExceptionFromAWaiterDeadlineScheduleFailsOnlyThatSaveWithoutThrowing() {
        FakeSink sink = new FakeSink();
        sink.manual = true;
        IOException refusal = new IOException("timer cannot schedule");
        AtomicInteger deadlinesAsked = new AtomicInteger();
        Timer deadlineThrows = (delay, task) -> {
            if (delay.equals(MAX_WAIT)) {
                deadlinesAsked.incrementAndGet();
                sneak(refusal);
            }
            return inner.schedule(delay, task);
        };
        BatchingWriter<String> w = new BatchingWriter<>(sink, deadlineThrows, new BatchingWriter.Options(1, WINDOW, 1, 1, MAX_WAIT));

        Future<Void> busy = w.save("busy");                 // takes the only slot
        Future<Void> queued = w.save("q0");                 // fills the backlog of one
        assertEquals(1, w.backlog(), "fixture: the backlog is full, so the next waiting save needs a deadline");

        Future<Void> waiter = assertDoesNotThrow(() -> w.saveWaiting("w"),
                "a checked exception from the deadline schedule is the save's failure cause, never a throw at saveWaiting");
        assertEquals(1, deadlinesAsked.get(), "fixture: the waiter asked for its deadline and the timer threw");
        assertTrue(waiter.failed(), "the waiter is failed at once, never left in the line without a deadline: " + waiter);
        assertSame(refusal, waiter.cause(), "it fails with the timer's own exception as cause, got " + waiter.cause());
        assertEquals(0, w.waiting(), "nobody is left waiting");

        assertFalse(busy.isComplete(), "only that save failed: busy's commit is untouched");
        assertFalse(queued.isComplete(), "only that save failed: q0 is still queued");
        sink.gate().complete();
        sink.gate().complete();
        assertTrue(busy.succeeded() && queued.succeeded(), "busy and q0 committed: " + busy.cause() + ", " + queued.cause());
        assertEquals(List.of("busy", "q0"), sink.batches.stream().flatMap(List::stream).toList(),
                "the refused waiter never reaches the sink; the others do, in arrival order");
        assertEquals(0, w.inFlight(), "nothing is left counted in flight");
    }

    // ---- FIX-235 decision 1: a sink that answers null fails its batch, from every path that dispatches it

    /** {@code f} failed with the NullPointerException of a null answer, whose message names the fault; returns that cause. */
    private static NullPointerException assertFailedByANullAnswer(String item, Future<Void> f) {
        assertTrue(f.failed(), item + " is in the batch the sink answered null for: it is failed, not left in flight: " + f);
        NullPointerException npe = assertInstanceOf(NullPointerException.class, f.cause(),
                item + ": a null answer fails its batch with a NullPointerException");
        assertTrue(String.valueOf(npe.getMessage()).contains("answered null"), item + ": the cause names the fault: " + npe.getMessage());
        return npe;
    }

    @Test
    void aSinkThatAnswersNullFailsOnlyItsBatchWithACauseThatNamesIt() {
        GatedSink sink = new GatedSink();
        sink.nullFor = batch -> batch.contains("bad");
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(1, WINDOW, 1, 10, MAX_WAIT));

        capturingEverything(() -> {
            Future<Void> bad = assertDoesNotThrow(() -> w.save("bad"), "a sink that answers null is never thrown at the saver");
            assertEquals(List.of(List.of("bad")), sink.batches, "fixture: the idle writer handed bad to the sink at once");
            assertFailedByANullAnswer("bad", bad);
            assertEquals(0, w.inFlight(), "nothing is in flight afterwards: the null answer released its slot");
            assertEquals(0, w.backlog());
            assertEquals(List.of(), logged.stream().map(LogRecord::getMessage).toList(),
                    "decision 7: a fault that fails an item is not logged, the failed future is its report");
        });

        Future<Void> later = w.save("later");
        assertEquals(List.of("later"), sink.batches.getLast(), "a later save goes to the sink at once: the slot is free");
        sink.gate().complete();
        assertTrue(later.succeeded(), "and commits: " + later.cause());
        assertEquals(0, w.inFlight());
    }

    /**
     * One drain takes two batches (the designer's staging): batches of 10 bytes, three slots, busy in flight and s queued;
     * a save weighing 100 fills the queue past the byte limit, so the drain takes [s] (BIG does not fit beside it) and [BIG]
     * alone. The sink answers null for [s], the first of the two.
     */
    @Test
    void aSinkThatAnswersNullForOneBatchOfADrainStillHandsTheOthersToTheSink() {
        GatedSink sink = new GatedSink();
        sink.nullFor = batch -> batch.contains("s");
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(10, WINDOW, 3, 100, MAX_WAIT),
                item -> item.equals("BIG") ? 100L : 1L, 10);

        Future<Void> busy = w.save("busy");                 // an idle writer: slot one at once, left open
        Future<Void> s = w.save("s");                       // 1 item, 1 byte: waits for the window beside two free slots
        assertEquals(List.of(List.of("busy")), sink.batches, "fixture: s is queued behind busy's commit");
        assertEquals(1, w.backlog(), "fixture: s is queued");

        Future<Void> big = assertDoesNotThrow(() -> w.save("BIG"), "a null answer for one batch of the drain is never thrown at the saver");
        assertEquals(List.of(List.of("busy"), List.of("s"), List.of("BIG")), sink.batches,
                "the sink saw all three batches in the order taken: the null answer for [s] did not keep [BIG] from it");
        assertFailedByANullAnswer("s", s);
        assertEquals(2, w.inFlight(), "busy and BIG are in flight; s's slot was released");
        assertFalse(busy.isComplete(), "busy's commit is untouched: " + busy);
        assertFalse(big.isComplete(), "BIG's commit is open: " + big);

        sink.gate().complete();                             // busy
        sink.gate().complete();                             // BIG
        assertTrue(busy.succeeded(), "busy committed: " + busy.cause());
        assertTrue(big.succeeded(), "BIG committed: " + big.cause());
        assertEquals(0, w.inFlight(), "everything settled: nothing is left counted in flight");
        assertEquals(0, w.backlog());
    }

    @Test
    void aSinkThatAnswersNullFromACompletionListenerCompletesTheEarlierBatchAndStrandsNothing() {
        GatedSink sink = new GatedSink();
        sink.nullFor = batch -> batch.contains("bad");
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(1, WINDOW, 1, 10, MAX_WAIT));

        Future<Void> a = w.save("a");                       // takes the only slot
        Future<Void> bad = w.save("bad");                   // queued behind a
        Future<Void> c = w.save("c");                       // queued behind bad
        assertEquals(List.of(List.of("a")), sink.batches, "fixture: bad and c wait for a's commit");
        assertEquals(2, w.backlog(), "fixture: bad and c are queued");

        Promise<Void> gate = sink.gate();
        assertDoesNotThrow(() -> { gate.complete(); }, "a null answer met in a's completion is never thrown at the completer");
        assertTrue(a.succeeded(), "a's commit succeeded: a completes although the drain in its completion met a null answer: " + a);
        assertFailedByANullAnswer("bad", bad);
        assertEquals(List.of(List.of("a"), List.of("bad"), List.of("c")), sink.batches,
                "c is handed to the sink on the slot bad's null answer released, not stranded");
        assertEquals(1, w.inFlight(), "only c's commit is counted in flight");
        assertFalse(c.isComplete(), "c's commit is open: " + c);

        sink.gate().complete();
        assertTrue(c.succeeded(), "c committed: " + c.cause());
        assertEquals(0, w.inFlight(), "nothing is left counted in flight");
        assertEquals(0, w.backlog());
    }

    /** What hands the null-answered batch to the sink while a commit is in flight beside a free slot. */
    enum Dispatch { THE_WINDOW_ELAPSING, A_FLUSH }

    @ParameterizedTest
    @EnumSource(Dispatch.class)
    void aSinkThatAnswersNullOnTheWindowOrAFlushFailsOnlyThatBatch(Dispatch dispatch) {
        GatedSink sink = new GatedSink();
        sink.nullFor = batch -> batch.contains("bad");
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(10, WINDOW, 2, 10, MAX_WAIT));

        Future<Void> a = w.save("a");                       // an idle writer: slot one at once, left open
        List<String> names = List.of("bad", "bad2", "bad3");
        List<Future<Void>> bad = new ArrayList<>();
        for (String item : names) bad.add(w.save(item));   // a partial batch: waits for the window
        assertEquals(List.of(List.of("a")), sink.batches, "fixture: bad, bad2 and bad3 wait for the window beside a free slot");
        assertEquals(1, inner.pending(), "fixture: the window is armed");

        Future<Void> flush = null;
        switch (dispatch) {
            case THE_WINDOW_ELAPSING -> assertDoesNotThrow(() -> clock.advance(WINDOW),
                    "a null answer met by the window's task is never thrown at the clock's caller");
            case A_FLUSH -> flush = assertDoesNotThrow(w::flush, "a null answer met by a flush is never thrown at the flusher");
        }
        assertEquals(List.of(List.of("a"), names), sink.batches, "fixture: " + dispatch + " handed the three to the sink as one batch");
        Throwable cause = bad.getFirst().cause();
        for (int i = 0; i < names.size(); i++) {
            NullPointerException npe = assertFailedByANullAnswer(names.get(i), bad.get(i));
            assertSame(cause, npe, names.get(i) + ": the batch fails as a whole, with one cause");
        }
        assertTrue(cause.getMessage().contains("3"), "the cause names the batch's item count, 3: " + cause.getMessage());
        assertEquals(1, w.inFlight(), "the null answer released its slot; only a's commit is counted in flight");
        assertFalse(a.isComplete(), "only that batch failed: a's commit is untouched: " + a);
        if (dispatch == Dispatch.A_FLUSH) assertFalse(flush.isComplete(), "the flush still waits for a, in flight: " + flush);

        sink.gate().complete();
        assertTrue(a.succeeded(), "the other batch's item commits: " + a.cause());
        if (dispatch == Dispatch.A_FLUSH) {
            assertTrue(flush.failed(), "the flush reports the failure of what it drained (Future.join): " + flush);
            assertSame(cause, flush.cause(), "with the null-answered batch's own cause, got " + flush.cause());
        }
        assertEquals(0, w.inFlight(), "nothing is left counted in flight");
        assertEquals(0, w.backlog());

        Future<Void> later = w.save("later");
        assertEquals(List.of("later"), sink.batches.getLast(), "a later save goes to the sink at once: the slots are free");
        sink.gate().complete();
        assertTrue(later.succeeded(), "and commits: " + later.cause());
    }

    /**
     * Two wait behind a full backlog of one. busy's completion drains: it takes [bad] and w1 enters into the room [bad]
     * left; the sink answers null for [bad], and that answer's own drain takes [w1] and lets w2 enter. Each waiter enters
     * through a drain met by the null answer, and each commits.
     */
    @Test
    void aWaiterEntersThroughADrainWhoseBatchTheSinkAnswersNullFor() {
        GatedSink sink = new GatedSink();
        sink.nullFor = batch -> batch.contains("bad");
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(1, WINDOW, 1, 1, MAX_WAIT));

        Future<Void> busy = w.save("busy");                 // takes the only slot
        Future<Void> bad = w.save("bad");                   // fills the backlog of one; its window is armed
        Future<Void> w1 = w.saveWaiting("w1");              // waits for room, its deadline armed
        Future<Void> w2 = w.saveWaiting("w2");              // waits behind w1, its deadline armed
        assertEquals(2, w.waiting(), "fixture: w1 and w2 wait behind the full backlog");
        assertEquals(3, inner.pending(), "fixture: the window and two deadlines are armed");

        Promise<Void> gate = sink.gate();
        assertDoesNotThrow(() -> { gate.complete(); }, "a null answer met in busy's completion is never thrown at the completer");
        assertEquals(0, w.waiting(), "w1 entered through the drain that took [bad], w2 through the drain of bad's null answer");
        assertEquals(List.of(List.of("busy"), List.of("bad"), List.of("w1")), sink.batches,
                "the slot bad's null answer released took w1 at once");
        assertEquals(1, w.backlog(), "w2 is queued behind w1's commit");
        assertTrue(busy.succeeded(), "busy committed: " + busy);
        assertFailedByANullAnswer("bad", bad);

        sink.gate().complete();                             // w1; its completion hands w2 to the sink
        sink.gate().complete();                             // w2
        assertTrue(w1.succeeded(), "w1 entered and committed: " + w1.cause());
        assertTrue(w2.succeeded(), "w2 entered and committed: " + w2.cause());
        assertEquals(List.of("busy", "bad", "w1", "w2"), sink.flushed(), "every item reached the sink once, in arrival order");
        assertEquals(0, w.waiting(), "nobody is left waiting");
        assertEquals(0, w.backlog());
        assertEquals(0, w.inFlight(), "nothing is left counted in flight");

        assertEquals(1, inner.pending(), "only the window armed for bad is left on the timer");
        clock.advance(WINDOW);                              // it fires and finds nothing queued
        assertEquals(0, inner.pending(), "no deadline task stays pending: each waiter's was cancelled as it entered");
    }

    // ---- FIX-235 decisions 2, 4 and 5: the weight function and the item

    /** How the faulty save enters: a save on an idle writer (which would hand it on at once), a saveWaiting on a full backlog (which would wait). */
    enum Entry {
        SAVE_ON_AN_IDLE_WRITER {
            @Override void stage(BatchingWriter<String> w) {}
            @Override Future<Void> call(BatchingWriter<String> w, String item) { return w.save(item); }
        },
        SAVE_WAITING_ON_A_FULL_BACKLOG {
            @Override void stage(BatchingWriter<String> w) {
                w.save("busy");                             // takes the only slot
                w.save("q0");                               // fills the backlog of one
            }
            @Override Future<Void> call(BatchingWriter<String> w, String item) { return w.saveWaiting(item); }
        };

        abstract void stage(BatchingWriter<String> w);

        abstract Future<Void> call(BatchingWriter<String> w, String item);
    }

    /** Everything a save could leave behind: an item queued, a waiter, a task on the timer (a deadline), a batch handed to the sink. */
    record Seen(int backlog, int waiting, int pending, int handed) {}

    private Seen seen(BatchingWriter<String> w, GatedSink sink) {
        return new Seen(w.backlog(), w.waiting(), inner.pending(), sink.batches.size());
    }

    /** The table of decision 2 with decision 5: {an unchecked exception, an Error, a sneaked checked exception} × {@link Entry}. */
    static Stream<Arguments> weightFaults() {
        List<Arguments> table = new ArrayList<>();
        for (Entry entry : Entry.values()) {
            for (Throwable fault : List.of(new IllegalStateException("weight cannot be computed"), new InternalError("weight cannot be computed"),
                    new IOException("weight cannot be computed"))) {
                table.add(Arguments.of(fault, entry));
            }
        }
        return table.stream();
    }

    @ParameterizedTest
    @MethodSource("weightFaults")
    void aThrowingWeightFailsThatSaveWithItsOwnCauseAndQueuesNothing(Throwable fault, Entry entry) {
        GatedSink sink = new GatedSink();
        ToLongFunction<String> weight = item -> {
            if (item.equals("boom")) sneak(fault);
            return 1L;
        };
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(1, WINDOW, 1, 1, MAX_WAIT), weight, 10);
        entry.stage(w);
        Seen before = seen(w, sink);

        capturingEverything(() -> {
            Future<Void> boom = assertDoesNotThrow(() -> entry.call(w, "boom"),
                    "a throw of the weight function is that save's failure cause, never a throw at the caller");
            assertTrue(boom.failed(), "the save whose weight threw is failed at once: " + boom);
            assertSame(fault, boom.cause(), "with the very object the weight function threw, got " + boom.cause());
            assertEquals(before, seen(w, sink), "nothing queued, nobody waiting, no deadline scheduled, the sink not called");
            assertEquals(List.of(), logged.stream().map(LogRecord::getMessage).toList(),
                    "decision 7: a fault that fails an item is not logged, the failed future is its report");
        });

        Future<Void> ok = entry.call(w, "ok");
        assertNotEquals(before, seen(w, sink), "fixture: the same call with an item the function weighs is seen by the witnesses");
        for (int i = 0; i < 10 && sink.open() > 0; i++) sink.gate().complete();
        assertTrue(ok.succeeded(), "the writer is still usable: " + ok.cause());
        assertFalse(sink.flushed().contains("boom"), "the save whose weight threw never reached the sink: " + sink.flushed());
        assertEquals(0, w.inFlight());
        assertEquals(0, w.backlog());
        assertEquals(0, w.waiting());
    }

    /** Decision 4: the writer's own argument check, like the constructors' — there is no item whose future could carry it. */
    @Test
    void aNullItemIsThrownAtTheCallerAndQueuesNothing() {
        GatedSink sink = new GatedSink();
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(1, WINDOW, 1, 1, MAX_WAIT));

        assertThrows(NullPointerException.class, () -> w.save(null), "save(null) is thrown at the caller");
        assertEquals(new Seen(0, 0, 0, 0), seen(w, sink), "nothing queued: an idle writer would have handed it to the sink at once");

        Future<Void> busy = w.save("busy");                 // takes the only slot
        Future<Void> queued = w.save("q0");                 // fills the backlog of one
        Seen full = seen(w, sink);
        assertEquals(new Seen(1, 0, 1, 1), full, "fixture: busy in flight, q0 queued, its window armed");
        assertThrows(NullPointerException.class, () -> w.saveWaiting(null), "saveWaiting(null) is thrown at the caller");
        assertEquals(full, seen(w, sink), "nothing queued, nobody waiting, no deadline scheduled");

        sink.gate().complete();
        sink.gate().complete();
        assertTrue(busy.succeeded() && queued.succeeded(), "the others commit: " + busy.cause() + ", " + queued.cause());
        assertEquals(List.of("busy", "q0"), sink.flushed());
    }

    // ---- FIX-235 decision 6: a commit counts as succeeded only when its future says so

    /**
     * A {@code Future} that reports itself completed and failed with {@code cause() == null}. Vert.x's own futures never do
     * (decision 6: {@code FailedFuture} and {@code FutureImpl.tryFail} replace a null cause), but the interface is open. A
     * {@link Proxy}: the abstract {@code onComplete(Completable)} reports no result and no cause, as a completed future hands
     * its listener its state; the default {@code onComplete(Handler)} runs as Vert.x 5.1.10 wrote it (javap: it wraps the
     * handler in a Completable that hands the handler this future itself as the result). Any other abstract method is
     * outside what the double models and fails the case loudly.
     */
    @SuppressWarnings("unchecked")
    static Future<Void> failedWithoutACause() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "isComplete", "failed" -> true;
            case "succeeded" -> false;
            case "cause", "result" -> null;
            case "onComplete" -> {
                if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                ((Completable<Void>) args[0]).complete(null, null);
                yield proxy;
            }
            case "toString" -> "a Future failed without a cause";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> {
                if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                throw new AssertionError("the double models a failed completion only, not " + method);
            }
        };
        return (Future<Void>) Proxy.newProxyInstance(BatchingWriterErrorContainmentTest.class.getClassLoader(), new Class<?>[] {Future.class}, h);
    }

    @Test
    void aSinkFutureThatFailsWithoutACauseStillFailsItsBatch() {
        AtomicReference<AsyncResult<Void>> result = new AtomicReference<>();
        failedWithoutACause().onComplete(ar -> result.set(ar));
        assertNotNull(result.get(), "fixture: the double hands its listener a result at once");
        assertTrue(result.get().failed() && !result.get().succeeded(), "fixture: the result reports a failure");
        assertNull(result.get().cause(), "fixture: and no cause");

        GatedSink sink = new GatedSink();
        sink.causelessFor = batch -> batch.contains("bad");
        BatchingWriter<String> w = new BatchingWriter<>(sink, inner, new BatchingWriter.Options(1, WINDOW, 1, 10, MAX_WAIT));

        capturingEverything(() -> {
            Future<Void> bad = assertDoesNotThrow(() -> w.save("bad"));
            assertEquals(List.of(List.of("bad")), sink.batches, "fixture: the idle writer handed bad to the sink at once");
            assertTrue(bad.isComplete(), "the sink's future reported its completion: " + bad);
            assertTrue(bad.failed(), "a commit whose future failed is never reported as a success, cause or none: " + bad);
            assertEquals(0, w.inFlight(), "the slot is released");
            assertEquals(List.of(), logged.stream().map(LogRecord::getMessage).toList(),
                    "decision 7: a fault that fails an item is not logged, the failed future is its report");
        });

        Future<Void> later = w.save("later");
        assertEquals(List.of("later"), sink.batches.getLast(), "a later save goes to the sink at once: the slot is free");
        sink.gate().complete();
        assertTrue(later.succeeded(), "and commits: " + later.cause());
    }
}
