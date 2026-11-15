package io.flottila.runtime.journal;

import io.flottila.api.Health;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flottila.runtime.journal.JournalFixture.BOUND;
import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.appendFailure;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.failureOf;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static io.flottila.runtime.journal.JournalFixture.untilParked;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pre-open whose segment row insert fails (CORE-3 item 3, CORE-4c1 r1, architect; the implementer's): no file exists, so it
 * is no fail-stop — the insert is tried again on the injected timer after 100 ms, the wait doubling up to 5 s, the journal's
 * health DEGRADED meanwhile and UP once the row is in, never failed; the record that needs the segment waits for it while
 * further appends fail {@link JournalBusyException} at the append wait once the pending bound is full; a close does not wait
 * for Postgres, nor tries again after an insert in flight at its start failed.
 */
class JournalRowRetryTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();

    /** Rows whose inserts of segment 2 and later fail while failures are left (Postgres away); the open's segment 1 goes in. */
    static final class OutageRows implements SegmentRows {
        private final AtomicInteger failuresLeft;
        final AtomicInteger tries = new AtomicInteger();
        private volatile Promise<Void> held;
        final CountDownLatch heldReached = new CountDownLatch(1);

        OutageRows(int failures) { failuresLeft = new AtomicInteger(failures); }

        /** Postgres is back: every later insert goes in. */
        void up() { failuresLeft.set(0); }

        /** The next try of segment 2 or later answers when the case completes the promise answered here. */
        Promise<Void> holdNext() {
            held = Promise.promise();
            return held;
        }

        @Override public Future<Void> insert(String node, long segment) {
            if (segment >= 2) {
                tries.incrementAndGet();
                Promise<Void> h = held;
                if (h != null) {
                    held = null;
                    heldReached.countDown();
                    return h.future();
                }
                if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) return Future.failedFuture(new IllegalStateException("Postgres is away (test)"));
            }
            return Future.succeededFuture();
        }
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    private Settings in(String name) throws IOException {
        return settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1)).segmentRecords(1);
    }

    private Journal open(Settings s, SegmentRows rows) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real(), rows);
        journals.add(j);
        return j;
    }

    /** The delay of the retry once it is scheduled: the one task due within 5 s (a segment's age timer is an hour away). */
    private Duration awaitRetry() {
        until(() -> timer.pending() > 0 && timer.nextDelay().compareTo(Duration.ofSeconds(5)) <= 0, () -> "a retry is scheduled: " + timer.pending());
        return timer.nextDelay();
    }

    @Test
    void aFailedPreOpenRowIsTriedAgainWithABoundedBackoffAndTheJournalGoesOnNeverFailed() throws Exception {
        OutageRows rows = new OutageRows(8);
        Journal j = open(in("retried"), rows);
        List<Duration> delays = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            Duration d = awaitRetry();
            assertEquals(Health.Status.DEGRADED, j.health().status(), "DEGRADED while the row is retried: " + j.health());
            assertFalse(j.isFailed(), "a failed row insert fails no journal");
            delays.add(d);
            clock.advance(d);
        }
        until(() -> j.health().status() == Health.Status.UP, () -> "UP once the row is in: " + j.health());

        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400), Duration.ofMillis(800), Duration.ofMillis(1600),
                Duration.ofMillis(3200), Duration.ofSeconds(5), Duration.ofSeconds(5)), delays, "100 ms, doubling, at most 5 s");
        assertEquals(9, rows.tries.get(), "eight failed tries and the one that went in");
        assertFalse(j.isFailed());
        Journal.Appended one = j.append(record("r-1", 100), live());
        Journal.Appended two = j.append(record("r-2", 100), live());
        await(two.forced());
        assertEquals(one.ref().segment() + 1, two.ref().segment(), "fixture: the second record is the retried segment's");
        assertArrayEquals(body("r-2", 100), j.read(two.ref()), "the segment whose row was retried takes records");
    }

    @Test
    void appendsDuringAnOutageFailBusyAtTheAppendWaitAndTheWaitingRecordIsWrittenOnceTheRowIsIn() throws Exception {
        OutageRows rows = new OutageRows(Integer.MAX_VALUE);
        Journal j = open(in("outage").recordMax(4096).pendingBytes(4096).appendWait(Duration.ofSeconds(2)), rows);
        awaitRetry();
        await(j.append(record("o-1", 100), live()).forced());   // segment 1: its row went in at the open
        Journal.Appended waiting = j.append(record("o-2", 100), live());   // segment 2: its row is retried

        Outcome<Throwable> third = onVirtual(() -> appendFailure(j, record("o-3", 4000)));   // with o-2 above the 4096 pending bytes
        untilParked(third.thread(), "the third append waits for room under the pending bound");
        clock.advance(Duration.ofSeconds(2));

        assertInstanceOf(JournalBusyException.class, third.join(), "refused retryable at the append wait");
        assertFalse(waiting.forced().isComplete(), "the record of segment 2 waits for its segment, neither written nor failed");
        assertEquals(Health.Status.DEGRADED, j.health().status());
        assertFalse(j.isFailed());

        rows.up();
        until(() -> {
            if (!waiting.forced().isComplete() && timer.pending() > 0 && timer.nextDelay().compareTo(Duration.ofSeconds(5)) <= 0) clock.advance(timer.nextDelay());
            return waiting.forced().isComplete();
        }, () -> "the waiting record is written once the row is in");

        await(waiting.forced());
        assertArrayEquals(body("o-2", 100), j.read(waiting.ref()));
        until(() -> j.health().status() == Health.Status.UP, () -> "UP again: " + j.health());
        await(j.append(record("o-4", 100), live()).forced());
    }

    @Test
    void aCloseDuringAnOutageDoesNotWaitForTheRowAndTheWaitingRecordFailsRetryable() throws Exception {
        OutageRows rows = new OutageRows(Integer.MAX_VALUE);
        Settings s = in("closing");
        Journal j = open(s, rows);
        awaitRetry();
        await(j.append(record("c-1", 100), live()).forced());
        Journal.Appended waiting = j.append(record("c-2", 100), live());

        journals.remove(j);
        Outcome<Void> close = onVirtual(() -> {
            j.close();
            return null;
        });
        close.join();   // bounded: no retry is waited for

        assertInstanceOf(JournalFailedException.class, failureOf(waiting.forced()), "the record that needed the segment fails retryable");
        assertEquals(Health.Status.DOWN, j.health().status(), "failed while its row was retried: DOWN before DEGRADED: " + j.health());
        rows.up();
        Journal back = open(s, rows);   // the LOCK was given back
        await(back.append(record("c-3", 100), live()).forced());
    }

    @Test
    void aCloseWhileARetriedInsertIsInFlightStopsTryingOnceItFails() throws Exception {
        OutageRows rows = new OutageRows(Integer.MAX_VALUE);
        Settings s = in("in-flight");
        Journal j = open(s, rows);
        Duration first = awaitRetry();   // the open's own try failed: the hold is for the retry
        Promise<Void> insert = rows.holdNext();
        clock.advance(first);
        assertTrue(rows.heldReached.await(BOUND.toSeconds(), TimeUnit.SECONDS), "the retried insert is in flight");

        journals.remove(j);
        Outcome<Void> close = onVirtual(() -> {
            j.close();
            return null;
        });
        untilParked(close.thread(), "the close waits for the pre-open in flight");
        insert.fail(new IllegalStateException("Postgres is still away (test)"));
        close.join();   // bounded: the failed try schedules no other once the close began

        assertEquals(0, timer.pending(), "no retry is left scheduled");
        rows.up();
        Journal back = open(s, rows);   // the LOCK was given back
        await(back.append(record("f-1", 100), live()).forced());
    }
}
