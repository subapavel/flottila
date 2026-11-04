package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static io.flottila.runtime.journal.JournalFixture.untilParked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The segment sweeper's schedule and lifecycle (CORE-4d), with a rows double at the store boundary: a failed scheduled pass is a
 * WARNING and the next one is still scheduled; a close stops the schedule, waits for a pass in flight and refuses a later one.
 * What a pass removes is SegmentSweepIT's.
 */
class SegmentSweeperTest {

    private static final SweepConfig CONFIG = new SweepConfig(Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofHours(1));

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);          // the journal's
    private final FakeTimer sweeps = new FakeTimer(clock);         // the sweeper's
    private Journal journal;
    private JournalRef sealed;   // the segment sweeper(rows) sealed past the grace

    @AfterEach
    void closeJournal() throws Exception {
        if (journal != null) journal.close();
    }

    /** Rows whose sweep answers {@code answer}'s next future, counting the passes that asked. */
    private static final class Rows implements SegmentRows {
        final AtomicInteger asked = new AtomicInteger();
        volatile Future<Set<Long>> answer = Future.succeededFuture(Set.of());

        @Override public Future<Void> insert(String node, long segment) { return Future.succeededFuture(); }

        @Override public Future<Set<Long>> sweep(String node, Set<Long> candidates, Instant keptSince) {
            asked.incrementAndGet();
            return answer;
        }
    }

    /** A sweeper over a journal holding one segment sealed past the grace: a candidate, so every pass asks the rows. */
    private SegmentSweeper sweeper(Rows rows) throws Exception {
        journal = Journal.open(settings(tmp).segmentRecords(1).config(), clock, timer, keys(KEY), JournalFiles.real(), rows);
        sealed = sealOne(journal);
        clock.advance(CONFIG.grace());
        return new SegmentSweeper(journal, rows, List::of, CONFIG, clock, sweeps);
    }

    /** One record appended and its segment sealed by age (the fixture's 1 s); its ref. */
    private JournalRef sealOne(Journal j) throws Exception {
        Journal.Appended a = j.append(JournalFixture.record("r-1", 100), JournalFixture.live());
        JournalFixture.await(a.forced());
        JournalRef ref = a.ref();
        clock.advance(Duration.ofSeconds(1));
        until(() -> sealed(j, ref.segment()), () -> "segment " + ref.segment() + " sealed");
        return ref;
    }

    /** No candidate (nothing sealed past the grace): the pass asks no rows, so it opens no transaction. */
    @Test
    void aPassWithNoCandidateAsksNoRows() throws Exception {
        Rows rows = new Rows();
        journal = Journal.open(settings(tmp).segmentRecords(1).config(), clock, timer, keys(KEY), JournalFiles.real(), rows);
        sealOne(journal);   // sealed, but within the grace
        SegmentSweeper sweeper = new SegmentSweeper(journal, rows, List::of, CONFIG, clock, sweeps);
        try {
            assertEquals(Set.of(), JournalFixture.await(sweeper.sweep()), "nothing swept");
            assertEquals(0, rows.asked.get(), "and no rows asked");
            clock.advance(CONFIG.grace());
            JournalFixture.await(sweeper.sweep());
            assertEquals(1, rows.asked.get(), "witness: past the grace it is a candidate and the rows are asked");
        } finally {
            sweeper.close();
        }
    }

    @Test
    void aFailedScheduledPassIsAWarningAndTheNextOneIsScheduled() throws Exception {
        Rows rows = new Rows();
        rows.answer = Future.failedFuture(new IllegalStateException("Postgres is down (test)"));
        SegmentSweeper sweeper = sweeper(rows);
        try (LogCapture logs = new LogCapture()) {
            sweeper.start();
            assertEquals(1, sweeps.pending(), "start schedules the first pass");
            clock.advance(CONFIG.interval());
            until(() -> rows.asked.get() == 1 && sweeps.pending() == 1, () -> "the pass ran and scheduled the next: " + sweeps.pending());
            until(() -> logs.count(Level.WARNING) == 1, () -> "the failed pass is one WARNING: " + logs.lines());
            assertTrue(logs.records().stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getThrown() instanceof IllegalStateException),
                    "it carries the pass's failure: " + logs.lines());

            rows.answer = Future.succeededFuture(Set.of());
            clock.advance(CONFIG.interval());
            until(() -> rows.asked.get() == 2 && sweeps.pending() == 1, () -> "the next pass ran an interval later: " + rows.asked);
            assertEquals(1, logs.count(Level.WARNING), "a pass that did not fail logs no WARNING: " + logs.lines());
        } finally {
            sweeper.close();
        }
    }

    @Test
    void aCloseStopsTheScheduleAndRefusesALaterPass() throws Exception {
        Rows rows = new Rows();
        SegmentSweeper sweeper = sweeper(rows);
        sweeper.start();
        sweeper.close();
        assertEquals(0, sweeps.pending(), "the scheduled pass is cancelled");
        clock.advance(CONFIG.interval());
        assertEquals(0, rows.asked.get(), "no pass ran");

        Throwable refused = JournalFixture.failureOf(sweeper.sweep());
        assertInstanceOf(IllegalStateException.class, refused, "a pass after the close is refused");
        assertEquals(0, rows.asked.get(), "and asks no rows");
        sweeper.close();   // a second close finds nothing to do
    }

    @Test
    void aCloseWaitsForTheScheduledPassInFlightWhichSchedulesNoNextOne() throws Exception {
        Rows rows = new Rows();
        Promise<Set<Long>> held = Promise.promise();
        rows.answer = held.future();
        SegmentSweeper sweeper = sweeper(rows);
        sweeper.start();
        clock.advance(CONFIG.interval());
        until(() -> rows.asked.get() == 1, () -> "the scheduled pass asks the rows");

        Outcome<Boolean> closing = onVirtual(() -> { sweeper.close(); return true; });
        untilParked(closing.thread(), "the close waits for the pass in flight");
        assertFalse(closing.done(), "the close has not returned while the pass runs");

        held.complete(Set.of());
        assertTrue(closing.join(), "the close returns once the pass ended");
        assertEquals(0, sweeps.pending(), "the pass that ended after the close scheduled no next one");
    }

    /** Rows that know no sweep (the interface's default, a test double's) delete no row: a sealed segment past the grace stays. */
    @Test
    void rowsThatKnowNoSweepRemoveNothing() throws Exception {
        SegmentRows rows = (node, segment) -> Future.succeededFuture();
        journal = Journal.open(settings(tmp).segmentRecords(1).config(), clock, timer, keys(KEY), JournalFiles.real(), rows);
        Journal j = journal;
        JournalRef ref = sealOne(j);
        clock.advance(CONFIG.grace());
        SegmentSweeper sweeper = new SegmentSweeper(j, rows, List::of, CONFIG, clock, sweeps);
        try {
            assertEquals(Set.of(), JournalFixture.await(sweeper.sweep()), "no row deleted");
            assertTrue(j.holds(ref.segment()), "the segment stays");
            assertEquals(100, j.stream(ref).readAllBytes().length, "and reads");
        } finally {
            sweeper.close();
        }
    }

    /**
     * An unlink that failed is tried again by every next pass until it succeeds — one that fails again stays pending —, and once
     * unlinked it is pending no more: a later pass finds nothing to retry and logs no WARNING. The failing unlink: the segment's file moved aside, a non-empty directory in its place.
     */
    @Test
    void aFailedUnlinkIsRetriedUntilItSucceedsAndThenNoMore() throws Exception {
        Rows rows = new Rows();
        SegmentSweeper sweeper = sweeper(rows);
        long n = sealed.segment();
        rows.answer = Future.succeededFuture(Set.of(n));
        Path file = tmp.resolve("segments").resolve(n + ".seg");
        Path aside = tmp.resolve("aside.seg");
        java.nio.file.Files.move(file, aside);
        java.nio.file.Files.createDirectories(file);
        java.nio.file.Files.writeString(file.resolve("keep"), "x");
        try (LogCapture logs = new LogCapture()) {
            assertEquals(Set.of(n), JournalFixture.await(sweeper.sweep()), "the pass deletes the row");
            assertEquals(1, logs.count(Level.WARNING), "its unlink failed: a WARNING: " + logs.lines());
            rows.answer = Future.succeededFuture(Set.of());
            JournalFixture.await(sweeper.sweep());
            assertEquals(2, logs.count(Level.WARNING), "the next pass tries it again and fails again: " + logs.lines());

            java.nio.file.Files.delete(file.resolve("keep"));
            java.nio.file.Files.delete(file);
            java.nio.file.Files.move(aside, file);
            JournalFixture.await(sweeper.sweep());
            assertFalse(java.nio.file.Files.exists(file), "still pending, the pass after the fix unlinks it");
            JournalFixture.await(sweeper.sweep());
            assertEquals(2, logs.count(Level.WARNING), "and the one after finds nothing to retry: " + logs.lines());
        } finally {
            sweeper.close();
        }
    }

    /** A swept segment gives back the bytes of its file: a persisted spill's, and a segment's found at a restart. */
    @Test
    void aSweptPersistedSpillOrRecoveredSegmentGivesBackItsFilesBytes() throws Exception {
        Rows rows = new Rows();
        journal = Journal.open(settings(tmp).segmentRecords(1).config(), clock, timer, keys(KEY), JournalFiles.real(), rows);
        JournalRef found = sealOne(journal);
        journal.close();
        journal = Journal.open(settings(tmp).segmentRecords(1).config(), clock, timer, keys(KEY), JournalFiles.real(), rows);
        Journal j = journal;
        until(() -> j.holds(found.segment() + 2), () -> "the restart's pre-open counted");
        Journal.Spill spill = j.spill(io.flottila.api.BlobKey.body("sp-1", 1), KEY, new GeneratedInput(7, 10_000), 1L << 20,
                io.flottila.api.Hasher.sha256());
        JournalRef persisted = io.flottila.runtime.node.OnVirtualThread.call(spill::persist);
        Path segments = tmp.resolve("segments");
        long foundBytes = java.nio.file.Files.size(segments.resolve(found.segment() + ".seg"));
        long spillBytes = java.nio.file.Files.size(segments.resolve(persisted.segment() + ".seg"));
        clock.advance(CONFIG.grace());
        SegmentSweeper sweeper = new SegmentSweeper(j, rows, List::of, CONFIG, clock, sweeps);
        try {
            long before = j.bytes();
            rows.answer = Future.succeededFuture(Set.of(found.segment()));
            JournalFixture.await(sweeper.sweep());
            assertEquals(before - foundBytes, j.bytes(), "the segment found at the restart gives back its file's bytes");
            rows.answer = Future.succeededFuture(Set.of(persisted.segment()));
            JournalFixture.await(sweeper.sweep());
            assertEquals(before - foundBytes - spillBytes, j.bytes(), "the persisted spill gives back its file's bytes");
        } finally {
            sweeper.close();
        }
    }

    private static boolean sealed(Journal j, long n) {
        try {
            return io.flottila.runtime.node.OnVirtualThread.call(() -> j.index(n)).isPresent();
        } catch (Exception e) {
            throw new AssertionError("index of " + n, e);
        }
    }
}
