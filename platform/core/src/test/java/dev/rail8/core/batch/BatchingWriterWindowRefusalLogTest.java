package dev.rail8.core.batch;

import dev.rail8.core.batch.BatchingWriterErrorContainmentTest.RefusingCancelTimer;
import dev.rail8.core.batch.BatchingWriterWindowRefusalTest.RefusingWindowTimer;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-146's observability: a refused window is counted and never silent. {@code windowsRefused()} rises once per refusal —
 * the writer's count equals the number of refusals the timer actually threw — and the WARNING is logged on the first
 * refusal of an episode and on recovery only, two lines per episode, so a permanently refusing timer cannot flood the
 * log and silence stays impossible. The phases below assert the lines as they appear, so a line logged once per refusal
 * is caught at the phase it appears in and not only by a total.
 *
 * <p>FIX-195: a refused cancel follows the same shape (D-146) — counted by {@code cancelsRefused()} on every refusal, one
 * WARNING with its cause on the first refusal of an episode, one on recovery, nothing in between — with one episode per
 * cancel site: the entering waiter's deadline (cancelled once per message under sustained backpressure) and the armed
 * window cancelled by {@code flush}. A refusing site never masks the other's first line, nor the window's.
 */
class BatchingWriterWindowRefusalLogTest {

    static final Duration WINDOW = Duration.ofMillis(50);
    static final Duration MAX_WAIT = Duration.ofSeconds(7);

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final RefusingWindowTimer timer = new RefusingWindowTimer(new FakeTimer(clock), WINDOW);
    final FakeSink sink = new FakeSink();
    final List<LogRecord> warnings = new CopyOnWriteArrayList<>();

    private final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { if (r.getLevel() == Level.WARNING) warnings.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };

    @Test
    void aRefusedWindowIsCountedAndNotSilent() {
        sink.manual = true;
        BatchingWriter<String> w = new BatchingWriter<>(sink, timer, new BatchingWriter.Options(2, WINDOW, 1, 10, MAX_WAIT));
        Logger log = Logger.getLogger(BatchingWriter.class.getName());
        log.addHandler(capture);
        try {
            // --- episode one: three refusals in a row
            timer.refuse = true;
            Future<Void> a = w.save("a");
            var gateA = sink.gate();
            for (String item : List.of("b", "c", "d")) w.save(item);
            assertTrue(timer.refused.get() >= 3, "fixture: the three saves behind a's commit each asked for a window and were refused");
            assertEquals(timer.refused.get(), w.windowsRefused(), "the counter rises once per refusal, not once per episode");
            assertEquals(1, warnings.size(), "one WARNING on the first refusal of the episode, not one per refusal: "
                    + warnings.stream().map(LogRecord::getMessage).toList());
            assertSame(timer.refusal, warnings.get(0).getThrown(), "the refusal is logged with its cause, never swallowed");

            // --- recovery: the drain on a's completion arms its window on a working timer again
            timer.refuse = false;
            int refusedSoFar = timer.refused.get();
            gateA.complete();
            assertTrue(a.succeeded(), "fixture: a committed");
            assertEquals(refusedSoFar, timer.refused.get(), "fixture: the timer served the window that followed the refusals");
            assertEquals(refusedSoFar, w.windowsRefused(), "a recovery does not count as a refusal");
            assertEquals(2, warnings.size(), "a second WARNING on the recovery: two lines per episode, silence is impossible: "
                    + warnings.stream().map(LogRecord::getMessage).toList());

            // --- episode two: the same timer refuses again
            timer.refuse = true;
            for (String item : List.of("e", "f")) w.save(item);
            clock.advance(WINDOW);                       // the armed window fires and the next one is refused
            assertTrue(timer.refused.get() > refusedSoFar, "fixture: the window task was refused its next window");
            assertEquals(timer.refused.get(), w.windowsRefused(), "every refusal of the second episode is counted too");
            assertEquals(3, warnings.size(), "one WARNING on the first refusal of the second episode: "
                    + warnings.stream().map(LogRecord::getMessage).toList());
            assertSame(timer.refusal, warnings.get(2).getThrown(), "the second episode's first refusal carries its cause too");

            int refusedInEpisodeTwo = timer.refused.get();
            sink.gate().complete();                      // the drain that follows is refused a window as well
            assertTrue(timer.refused.get() > refusedInEpisodeTwo, "fixture: a second refusal inside the same episode");
            assertEquals(timer.refused.get(), w.windowsRefused(), "the counter always rises");
            assertEquals(3, warnings.size(), "a refusal inside an episode already reported adds no line: "
                    + warnings.stream().map(LogRecord::getMessage).toList());

            // --- recovery two
            timer.refuse = false;
            w.save("g");
            assertEquals(4, warnings.size(), "the second episode's recovery is reported as the first one's was: "
                    + warnings.stream().map(LogRecord::getMessage).toList());
            assertEquals(timer.refused.get(), w.windowsRefused(), "and nothing more was counted");
            assertEquals(List.of(), timer.escaped, "no window task threw on the timer's thread");
        } finally {
            log.removeHandler(capture);
        }
    }

    // ---- FIX-195: refused cancels, episode-throttled per site (D-146)

    final IllegalStateException cancelRefusal = new IllegalStateException("timer refuses the cancel");

    /**
     * One slot, batches of one, a backlog of one: {@code busy} is in flight and {@code q0} queued behind it with the window
     * armed, so {@code flush} has a window to cancel and every {@code saveWaiting} waits until a commit frees the slot.
     */
    private BatchingWriter<String> oneSlotWriter(RefusingCancelTimer cancels) {
        sink.manual = true;
        BatchingWriter<String> w = new BatchingWriter<>(sink, cancels, new BatchingWriter.Options(1, WINDOW, 1, 1, MAX_WAIT));
        w.save("busy");
        w.save("q0");
        assertEquals(1, w.backlog(), "fixture: the backlog of one is full behind the commit in flight");
        assertEquals(0, cancels.cancelsAttempted.get(), "fixture: no cancel yet");
        return w;
    }

    /** {@code item} waits behind the full backlog, then the commit in flight completes and its deadline is cancelled as it enters. */
    private void waiterEnters(BatchingWriter<String> w, RefusingCancelTimer cancels, String item) {
        int before = cancels.cancelsAttempted.get();
        Future<Void> waiting = w.saveWaiting(item);
        assertEquals(1, w.waiting(), "fixture: " + item + " waits behind the full backlog");
        sink.gate().complete();
        assertEquals(0, w.waiting(), "fixture: " + item + " entered the backlog as the commit freed the slot");
        assertFalse(waiting.isComplete(), "fixture: " + item + " is queued, not failed: " + waiting);
        assertEquals(before + 1, cancels.cancelsAttempted.get(), "fixture: exactly one cancel, the entering waiter's deadline");
    }

    /** {@code flush} cancels the armed window (the drain after it arms a new one: the slot stays taken, the queue non-empty). */
    private void flushCancels(BatchingWriter<String> w, RefusingCancelTimer cancels) {
        int before = cancels.cancelsAttempted.get();
        w.flush();
        assertEquals(before + 1, cancels.cancelsAttempted.get(), "fixture: flush cancelled the armed window, once");
    }

    private void captured(Runnable body) {
        Logger log = Logger.getLogger(BatchingWriter.class.getName());
        log.addHandler(capture);
        try {
            body.run();
        } finally {
            log.removeHandler(capture);
        }
    }

    private List<String> lines() {
        return warnings.stream().map(r -> r.getMessage() + " [" + r.getThrown() + "]").toList();
    }

    /** Each line names the decision it follows: D-146, never D-104, D-144 or D-145. */
    private void assertCitesD146(LogRecord r) {
        assertTrue(r.getMessage().contains("D-146"), "the line cites D-146: " + r.getMessage());
        for (String other : List.of("D-104", "D-144", "D-145")) {
            assertFalse(r.getMessage().contains(other), "the line cites " + other + ", a different decision: " + r.getMessage());
        }
    }

    @Test
    void aCancelRefusedAtTheWaiterDeadlineSiteLogsOnceForTheWholeEpisode() {
        RefusingCancelTimer cancels = new RefusingCancelTimer(timer, cancelRefusal);
        captured(() -> {
            BatchingWriter<String> w = oneSlotWriter(cancels);
            for (int n = 1; n <= 4; n++) {
                waiterEnters(w, cancels, "w" + n);
                assertEquals(n, cancels.cancelsRefused.get(), "fixture: every entering waiter's cancel so far was refused");
                assertEquals(n, w.cancelsRefused(), "the counter rises on every refused cancel, not once per episode");
                assertEquals(1, warnings.size(), "one WARNING for the whole episode, at its first refused cancel, not one per "
                        + "message (after refusal " + n + "): " + lines());
            }
            assertSame(cancelRefusal, warnings.get(0).getThrown(), "the first refusal is logged with its cause");
            assertCitesD146(warnings.get(0));
            assertEquals(0, w.windowsRefused(), "a refused cancel is not counted as a refused window");
        });
    }

    @Test
    void aServedCancelClosesTheWaiterDeadlineEpisodeWithASecondLineAndTheNextRefusalOpensANewOne() {
        RefusingCancelTimer cancels = new RefusingCancelTimer(timer, cancelRefusal);
        captured(() -> {
            BatchingWriter<String> w = oneSlotWriter(cancels);
            waiterEnters(w, cancels, "w1");                  // refused: the episode opens
            assertEquals(1, warnings.size(), "the first refusal opens the episode with one line: " + lines());

            cancels.refuseCancel = false;
            waiterEnters(w, cancels, "w2");                  // served: the episode closes
            assertEquals(2, warnings.size(), "a cancel served again at that site closes the episode with a second line: " + lines());
            assertNull(warnings.get(1).getThrown(), "the recovery line carries no cause, nothing was refused: " + lines());
            assertCitesD146(warnings.get(1));
            waiterEnters(w, cancels, "w3");                  // served outside an episode
            assertEquals(2, warnings.size(), "a served cancel outside an episode adds no line: " + lines());

            cancels.refuseCancel = true;
            waiterEnters(w, cancels, "w4");                  // refused: a new episode
            assertEquals(List.of("first refusal", "recovery", "first refusal"),
                    warnings.stream().map(r -> r.getThrown() == cancelRefusal ? "first refusal"
                            : r.getThrown() == null ? "recovery" : "other: " + r.getThrown()).toList(),
                    "the lines in order: first refusal, recovery, the next episode's first refusal: " + lines());
            assertEquals(2, w.cancelsRefused(), "two refusals counted; a served cancel is not one");
        });
    }

    @Test
    void aCancelRefusedAtTheArmedWindowSiteLogsOnceForTheEpisodeAndOnceOnRecovery() {
        RefusingCancelTimer cancels = new RefusingCancelTimer(timer, cancelRefusal);
        captured(() -> {
            BatchingWriter<String> w = oneSlotWriter(cancels);
            for (int n = 1; n <= 3; n++) {
                flushCancels(w, cancels);
                assertEquals(n, w.cancelsRefused(), "the counter rises on every refused cancel of the armed window");
                assertEquals(1, warnings.size(), "one WARNING for the episode, not one per flush (after refusal " + n + "): "
                        + lines());
            }
            assertSame(cancelRefusal, warnings.get(0).getThrown(), "the first refusal is logged with its cause");
            assertCitesD146(warnings.get(0));

            cancels.refuseCancel = false;
            flushCancels(w, cancels);
            assertEquals(2, warnings.size(), "a cancel served again at that site closes the episode with a second line: " + lines());
            assertNull(warnings.get(1).getThrown(), "the recovery line carries no cause: " + lines());
            assertCitesD146(warnings.get(1));
            flushCancels(w, cancels);
            assertEquals(2, warnings.size(), "a served cancel outside an episode adds no line: " + lines());

            cancels.refuseCancel = true;
            flushCancels(w, cancels);
            assertEquals(3, warnings.size(), "the next refusal opens a new episode with its own first line: " + lines());
            assertSame(cancelRefusal, warnings.get(2).getThrown(), "with its cause");
            assertEquals(4, w.cancelsRefused(), "four refusals counted; the two served cancels are not");
        });
    }

    @Test
    void aRefusingArmedWindowCancelDoesNotMaskTheFirstRefusalAtTheWaiterDeadlineSite() {
        RefusingCancelTimer cancels = new RefusingCancelTimer(timer, cancelRefusal);
        captured(() -> {
            BatchingWriter<String> w = oneSlotWriter(cancels);
            flushCancels(w, cancels);
            flushCancels(w, cancels);
            assertEquals(1, warnings.size(), "fixture: the armed-window site is in a refusing episode, reported once: " + lines());

            waiterEnters(w, cancels, "w1");
            assertEquals(2, warnings.size(), "the waiter-deadline site's first refusal logs its own line: a refusing flush "
                    + "cancel is a different episode and must not mask it: " + lines());
            assertSame(cancelRefusal, warnings.get(1).getThrown(), "with its cause");
            assertCitesD146(warnings.get(1));

            waiterEnters(w, cancels, "w2");
            assertEquals(2, warnings.size(), "inside its own episode the waiter-deadline site adds no further line: " + lines());
            assertEquals(4, w.cancelsRefused(), "every refusal at either site counted");
        });
    }

    @Test
    void aRefusingWaiterDeadlineCancelDoesNotMaskTheFirstRefusalAtTheArmedWindowSiteNorCloseItsEpisode() {
        RefusingCancelTimer cancels = new RefusingCancelTimer(timer, cancelRefusal);
        captured(() -> {
            BatchingWriter<String> w = oneSlotWriter(cancels);
            waiterEnters(w, cancels, "w1");
            assertEquals(1, warnings.size(), "fixture: the waiter-deadline site is in a refusing episode, reported once: " + lines());

            flushCancels(w, cancels);
            assertEquals(2, warnings.size(), "the armed-window site's first refusal logs its own line, unmasked by the "
                    + "waiter-deadline episode: " + lines());
            assertSame(cancelRefusal, warnings.get(1).getThrown(), "with its cause");
            flushCancels(w, cancels);
            assertEquals(2, warnings.size(), "inside its own episode the armed-window site adds no further line: " + lines());

            cancels.refuses = delay -> delay.equals(WINDOW);  // the deadlines are served again, the window still refused
            waiterEnters(w, cancels, "w2");
            assertEquals(3, warnings.size(), "the waiter-deadline site recovers with its own line: " + lines());
            assertNull(warnings.get(2).getThrown(), "the recovery line carries no cause: " + lines());
            flushCancels(w, cancels);
            assertEquals(3, warnings.size(), "the waiter-deadline recovery did not close the armed-window episode: its next "
                    + "refusal adds no line: " + lines());
            assertEquals(4, w.cancelsRefused(), "w1, three flushes refused; the served deadline is not counted");
        });
    }

    @Test
    void aRefusedWindowEpisodeDoesNotMaskTheFirstRefusedCancel() {
        timer.refuse = true;                                 // the window schedule is refused from q0's save on
        RefusingCancelTimer cancels = new RefusingCancelTimer(timer, cancelRefusal);
        captured(() -> {
            BatchingWriter<String> w = oneSlotWriter(cancels);
            assertEquals(1, w.windowsRefused(), "fixture: q0's window was refused");
            assertEquals(1, warnings.size(), "fixture: the window episode is open, reported once: " + lines());

            waiterEnters(w, cancels, "w1");
            assertEquals(2, warnings.size(), "a refused cancel is its own episode: the open window episode must not mask "
                    + "its first line: " + lines());
            assertSame(cancelRefusal, warnings.get(1).getThrown(), "the cancel's line carries the cancel's cause");
            assertEquals(1, w.cancelsRefused(), "the cancel refusal is counted as a cancel");
            assertEquals(timer.refused.get(), w.windowsRefused(), "and the window refusals as windows only");
        });
    }

    @Test
    void theCancelCounterRisesOnEveryRefusalAtEitherSiteAndNeverOnAServedCancel() {
        RefusingCancelTimer cancels = new RefusingCancelTimer(timer, cancelRefusal);
        captured(() -> {
            BatchingWriter<String> w = oneSlotWriter(cancels);
            assertEquals(0, w.cancelsRefused(), "nothing refused yet");
            flushCancels(w, cancels);
            assertEquals(1, w.cancelsRefused(), "a refused armed-window cancel is counted");
            waiterEnters(w, cancels, "w1");
            assertEquals(2, w.cancelsRefused(), "a refused waiter-deadline cancel is counted");
            flushCancels(w, cancels);
            assertEquals(3, w.cancelsRefused(), "counted inside an open episode too, where no line is logged");
            waiterEnters(w, cancels, "w2");
            assertEquals(4, w.cancelsRefused(), "counted inside an open episode too, where no line is logged");

            cancels.refuseCancel = false;
            flushCancels(w, cancels);
            waiterEnters(w, cancels, "w3");
            assertEquals(4, w.cancelsRefused(), "a served cancel at either site is not counted");
            assertEquals(cancels.cancelsRefused.get(), w.cancelsRefused(), "the count equals the refusals the timer threw");
            assertEquals(0, w.windowsRefused(), "no cancel refusal is counted as a refused window");
        });
    }
}
