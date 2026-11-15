package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.JournalRowRetryTest.OutageRows;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static io.flottila.runtime.journal.JournalFixture.BOUND;
import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.failureOf;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static io.flottila.runtime.journal.JournalFixture.untilParked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second way a close meets a Postgres outage (CORE-4c3 item 8; the implementer's, beside JournalCloseAndDirForceTest):
 * the next segment's row insert is in flight when the close begins and fails after it, while a record waits for that
 * segment. The record fails retryable, and no fail-stop ERROR is logged — the writer stopped because the journal closed.
 */
class JournalQuietCloseTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    @Test
    void aCloseWhileTheRetriedInsertOfAWaitedSegmentIsInFlightLogsNoFailStopError() throws Exception {
        OutageRows rows = new OutageRows(Integer.MAX_VALUE);
        Journal j = Journal.open(settings(Files.createDirectories(tmp.resolve("waited"))).segmentAge(Duration.ofHours(1)).segmentRecords(1).config(),
                clock, timer, keys(KEY), JournalFiles.real(), rows);
        journals.add(j);
        until(() -> timer.pending() > 0 && timer.nextDelay().compareTo(Duration.ofSeconds(5)) <= 0, () -> "a retry of the row is scheduled: " + timer.pending());
        Duration first = timer.nextDelay();
        await(j.append(record("v-1", 100), live()).forced());
        Journal.Appended waiting = j.append(record("v-2", 100), live());   // needs the segment whose row is retried
        Promise<Void> insert = rows.holdNext();
        clock.advance(first);
        assertTrue(rows.heldReached.await(BOUND.toSeconds(), TimeUnit.SECONDS), "fixture: the retried insert is in flight");

        try (LogCapture logs = new LogCapture()) {
            journals.remove(j);
            Outcome<Void> close = onVirtual(() -> {
                j.close();
                return null;
            });
            untilParked(close.thread(), "the close waits for the writer, which waits for the pre-open in flight");
            insert.fail(new IllegalStateException("Postgres is still away (test)"));
            close.join();

            assertInstanceOf(JournalFailedException.class, failureOf(waiting.forced()), "the record that needed the segment fails retryable");
            assertEquals(0, logs.count(Level.SEVERE), "the insert failed after the close began: no fail-stop ERROR: " + logs.lines());
        }
    }
}
