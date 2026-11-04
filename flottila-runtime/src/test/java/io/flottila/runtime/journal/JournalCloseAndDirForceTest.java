package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.runtime.journal.JournalRowRetryTest.OutageRows;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two CORE-4c3 carries (packet item 8; CORE-3 "Added in CORE-4c1" r1 and r2):
 * <ul>
 *   <li>A close during a Postgres outage — the next segment's row retried on the timer, or its retried insert in flight —
 *       logs no fail-stop ERROR: the waiting record still fails retryable, but the journal stopped because it was closed,
 *       not because a write or force failed. Witness of the capture: a real fail-stop (a failed force) is one ERROR in it.</li>
 *   <li>After a fail-stop caused by a directory force, a later force of that directory that succeeds proves nothing for what
 *       was written before (fsyncgate): the persist that made it re-checks the journal's failure and fails too, its segment
 *       never readable as persisted. Witness: a persist without a failure in between is persisted.</li>
 * </ul>
 */
class JournalCloseAndDirForceTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();
    private final List<HeldFiles> seams = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        for (HeldFiles f : seams) f.releaseAll();
        for (Journal j : journals) j.close();
    }

    private Settings in(String name) throws IOException {
        return settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1)).segmentRecords(1);
    }

    private Journal open(Settings s, JournalFiles files, SegmentRows rows) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), files, rows);
        journals.add(j);
        return j;
    }

    private Duration awaitRetry() {
        until(() -> timer.pending() > 0 && timer.nextDelay().compareTo(Duration.ofSeconds(5)) <= 0, () -> "a retry of the row is scheduled: " + timer.pending());
        return timer.nextDelay();
    }

    private static long errors(LogCapture logs) {
        return logs.count(Level.SEVERE);
    }

    /** A journal whose writer's force of {@code marker}'s record fails: the fail-stop ERROR a real failure logs. */
    private void aRealFailStop(String name) throws Exception {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        Journal j = open(in(name), files, (node, segment) -> io.vertx.core.Future.succeededFuture());
        HeldFiles.Hold force = files.holdForceAfter(name + "-1");
        Journal.Appended a = j.append(record(name + "-1", 100), live());
        force.awaitReached();
        force.fail(new IOException("EIO on the force (test)"));
        assertInstanceOf(JournalFailedException.class, failureOf(a.forced()), "fixture: the failed force fails the journal");
        until(j::isFailed, () -> "the journal failed");
    }

    @Test
    void aCloseDuringAPostgresOutageLogsNoFailStopError() throws Exception {
        OutageRows rows = new OutageRows(Integer.MAX_VALUE);
        Journal j = open(in("outage"), JournalFiles.real(), rows);
        awaitRetry();
        await(j.append(record("q-1", 100), live()).forced());
        Journal.Appended waiting = j.append(record("q-2", 100), live());   // needs the segment whose row is retried

        try (LogCapture logs = new LogCapture()) {
            journals.remove(j);
            Outcome<Void> close = onVirtual(() -> {
                j.close();
                return null;
            });
            close.join();

            assertInstanceOf(JournalFailedException.class, failureOf(waiting.forced()), "the record that needed the segment still fails retryable");
            assertEquals(0, errors(logs), "a close during an outage is no fail-stop: no ERROR: " + logs.lines());

            aRealFailStop("real");
            assertEquals(1, errors(logs), "witness: a failed force is one ERROR in the same capture: " + logs.lines());
        }
    }

    @Test
    void aCloseWhileARetriedInsertIsInFlightLogsNoFailStopError() throws Exception {
        OutageRows rows = new OutageRows(Integer.MAX_VALUE);
        Journal j = open(in("in-flight"), JournalFiles.real(), rows);
        Duration first = awaitRetry();
        Promise<Void> insert = rows.holdNext();
        clock.advance(first);
        assertTrue(rows.heldReached.await(BOUND.toSeconds(), TimeUnit.SECONDS), "fixture: the retried insert is in flight");

        try (LogCapture logs = new LogCapture()) {
            journals.remove(j);
            Outcome<Void> close = onVirtual(() -> {
                j.close();
                return null;
            });
            untilParked(close.thread(), "the close waits for the pre-open in flight");
            insert.fail(new IllegalStateException("Postgres is still away (test)"));
            close.join();

            assertEquals(0, errors(logs), "the insert that failed after the close began is no fail-stop: no ERROR: " + logs.lines());

            aRealFailStop("real-2");
            assertEquals(1, errors(logs), "witness: a failed force is one ERROR in the same capture: " + logs.lines());
        }
    }

    /**
     * Spills A and B persist at once: A's directory force (after its move into {@code segments/}) is held, then B's force of the
     * same directory, entered after A's. A's force fails — the journal fails — and only then is B's released and succeeds. B's
     * persist must fail as the journal failed: its force came after a failed force of that directory.
     */
    @Test
    void aDirForceAfterAFailedOneFailsItsPersistToo() throws Exception {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        Settings s = in("dir-force").recordMax(4096);
        Journal j = open(s, files, (node, segment) -> io.vertx.core.Future.succeededFuture());
        Path segments = s.dir.resolve("segments");

        Journal.Spill witness = j.spill(BlobKey.body("sp-w", 0), KEY, new GeneratedInput(1, 10_000), 1L << 20, Hasher.sha256());
        JournalRef persisted = io.flottila.runtime.node.OnVirtualThread.call(witness::persist);
        assertEquals(10_000, j.stream(persisted).readAllBytes().length, "witness: a persist with no failure in between is persisted and reads");
        // the open's forces of segments/ (segment 1's creation, segment 2's pre-open) and the witness's are over: the holds
        // below can only meet A's and B's
        until(() -> files.forcesOn(segments).size() >= 3 && files.forcesOn(segments).stream().allMatch(HeldFiles.Op::returnedOk),
                () -> "the forces of segments/ so far: " + files.forcesOn(segments));

        Journal.Spill a = j.spill(BlobKey.body("sp-a", 0), KEY, new GeneratedInput(2, 10_000), 1L << 20, Hasher.sha256());
        Journal.Spill b = j.spill(BlobKey.body("sp-b", 0), KEY, new GeneratedInput(3, 10_000), 1L << 20, Hasher.sha256());
        HeldFiles.Hold aForce = files.holdForceOn(segments, files.now());
        Outcome<JournalRef> pa = onVirtual(a::persist);
        aForce.awaitReached();
        HeldFiles.Hold bForce = files.holdForceOn(segments, files.now());
        Outcome<JournalRef> pb = onVirtual(b::persist);
        bForce.awaitReached();

        aForce.fail(new IOException("EIO on the directory force (test)"));
        assertInstanceOf(IOException.class, pa.failure(), "A's persist fails with its directory force");
        until(j::isFailed, () -> "fixture: the failed directory force failed the journal");
        bForce.release();

        Throwable bFailed = pb.failure();
        assertInstanceOf(JournalFailedException.class, bFailed, "B's directory force came after a failed one: B is not persisted: " + bFailed);
        assertThrows(IOException.class, () -> j.stream(b.ref()), "and its record is not readable as a persisted segment's");
    }
}
