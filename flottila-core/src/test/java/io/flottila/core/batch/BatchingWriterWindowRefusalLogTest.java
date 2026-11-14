package io.flottila.core.batch;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-71 (D-104) observability: a refused window is never silent (F-13) and never a flood. {@code windowsRefused()} rises
 * once per refusal; the WARNING is logged on the first refusal of an episode and on recovery, not once per refusal.
 */
class BatchingWriterWindowRefusalLogTest {

    static final Duration WINDOW = BatchingWriterWindowRefusalTest.WINDOW;

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final BatchingWriterWindowRefusalTest.RefusingWindowTimer timer =
            new BatchingWriterWindowRefusalTest.RefusingWindowTimer(new FakeTimer(clock), WINDOW);
    final FakeSink sink = new FakeSink();

    private BatchingWriter<String> writer(int batchSize, int concurrency, int maxBacklog) {
        sink.manual = true;
        return new BatchingWriter<>(sink, timer, new BatchingWriter.Options(batchSize, WINDOW, concurrency, maxBacklog, BatchingWriterWindowRefusalTest.MAX_WAIT));
    }

    private void completeEveryOpenCommit() {
        for (int i = 0; sink.open() > 0; i++) {
            assertTrue(i < 50, "fixture: commits keep coming after 50 completions");
            sink.gate().complete();
        }
    }

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

    private long warnings() {
        return logged.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()
                && r.getLoggerName() != null && r.getLoggerName().startsWith("io.flottila.core.batch")).count();
    }

    /**
     * An episode is the run of refusals between two window schedules the timer accepted: observed through the log, the
     * first refusal logs one WARNING, further refusals only raise the counter, the first accepted schedule after them logs
     * the recovery WARNING, and a refusal after that opens a new episode with its own WARNING.
     */
    @Test
    void aRefusedWindowIsCountedAndNotSilent() {
        BatchingWriter<String> w = writer(2, 1, 10);
        assertEquals(0, w.windowsRefused(), "fixture: nothing refused yet");
        assertEquals(0, warnings(), "fixture: nothing logged yet");

        timer.refuse = true;
        w.save("a");
        var gateA = sink.gate();
        w.save("b");
        assertEquals(1, timer.refused.get(), "fixture: one refusal");
        assertEquals(1, w.windowsRefused(), "the counter rose by the one refusal");
        assertEquals(1, warnings(), "the first refusal of the episode is logged");
        w.save("c");
        w.save("d");
        assertEquals(3, timer.refused.get(), "fixture: three refusals so far");
        assertEquals(3, w.windowsRefused(), "the counter rises once per refusal");
        assertEquals(1, warnings(), "refusals two and three are counted, not logged again");

        gateA.complete();                                       // takes [b, c], refused again
        assertEquals(4, w.windowsRefused());
        assertEquals(timer.refused.get(), w.windowsRefused(), "the counter equals the refusals the timer threw");
        sink.gate().complete();                                 // [d] goes on the freed slot, nothing to arm
        sink.gate().complete();
        assertEquals(1, warnings(), "a drain that arms nothing is neither a refusal nor a recovery");

        timer.refuse = false;
        w.save("p");
        var gateP = sink.gate();
        assertEquals(1, warnings(), "fixture: an idle save asks for no window, so the timer has not recovered yet");
        w.save("q");                                            // the window is asked for and accepted
        assertEquals(1, timer.inner.pending(), "fixture: the window was armed");
        assertEquals(2, warnings(), "the first accepted window after the refusals logs the recovery");
        assertEquals(4, w.windowsRefused(), "recovery does not touch the counter");
        gateP.complete();                                       // takes [q]; q's window stays armed
        sink.gate().complete();
        clock.advance(WINDOW);                                  // q's window fires on an empty queue and arms nothing
        assertEquals(0, timer.inner.pending(), "fixture: no window is armed");
        w.save("x");                                            // idle: x goes alone
        var gateX = sink.gate();
        w.save("y");                                            // a second window is asked for after the recovery
        assertEquals(1, timer.inner.pending(), "fixture: the timer accepted a second window");
        assertEquals(2, warnings(), "a further accepted window logs nothing: only the first one after refusals is a recovery");
        assertEquals(4, w.windowsRefused(), "an accepted window does not touch the counter");
        gateX.complete();                                       // takes [y]
        sink.gate().complete();
        clock.advance(WINDOW);                                  // y's window fires on an empty queue
        assertEquals(0, timer.inner.pending(), "fixture: idle again, no window armed");

        timer.refuse = true;                                    // a new episode
        w.save("r");
        var gateR = sink.gate();
        w.save("s");
        assertEquals(5, w.windowsRefused());
        assertEquals(3, warnings(), "a refusal after a recovery is a new episode and is logged");
        w.save("t");
        assertEquals(6, w.windowsRefused());
        assertEquals(3, warnings(), "and only its first refusal");
        gateR.complete();
        completeEveryOpenCommit();
        assertEquals(List.of(), timer.escaped);
    }
}
