package io.flottila.runtime.journal;

import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4d review r1 (e): an unlink that fails ({@code IOException}) on one segment is caught for that segment: the pass goes on
 * and unlinks the others, the failed one stays pending and is unlinked by the next pass, and its bytes count in
 * {@link Journal#bytes()} until then. The failing unlink, root-proof: the segment's file is moved aside and a non-empty
 * directory put in its place ({@code DirectoryNotEmptyException}); restored before the next pass. Rows: the
 * {@link LargeBodyCrashTest.KeptRows} double (no pins).
 */
@Timeout(60)
class SegmentSweepUnlinkTest {

    @TempDir Path tmp;
    final List<AutoCloseable> closing = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (int i = closing.size() - 1; i >= 0; i--) closing.get(i).close();
    }

    /** A journal over the {@link LargeBodyCrashTest.KeptRows} double (no pins) with three sealed segments of one record each. */
    record Three(Journal journal, FakeClock clock, LargeBodyCrashTest.KeptRows rows, Path segments, List<Long> sealed) { }

    Three threeSealed() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("journal"));
        FakeClock clock = FakeClock.at(JournalFixture.START);
        LargeBodyCrashTest.KeptRows rows = new LargeBodyCrashTest.KeptRows();
        JournalConfig config = settings(dir).recordMax(4096).config();
        Journal journal = OnVirtualThread.call(() -> Journal.open(config, clock, new FakeTimer(clock), keys(KEY), JournalFiles.real(), rows));
        closing.add(journal);
        List<Long> sealed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = "u-" + i;
            JournalRef ref = OnVirtualThread.call(() -> {
                Journal.Appended a = journal.append(new JournalRecord(id, 1, "body", KEY, new byte[2_000]), Promise.promise().future());
                a.forced().await();
                return a.ref();
            });
            clock.advance(Duration.ofSeconds(1));   // SEGMENT_AGE: rolled and sealed
            long n = ref.segment();
            SegmentSweepIT.until(() -> {
                try {
                    return OnVirtualThread.call(() -> journal.index(n)).isPresent();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, () -> "segment " + n + " sealed");
            sealed.add(n);
        }
        return new Three(journal, clock, rows, dir.resolve("segments"), sealed);
    }

    @Test
    void aFailedUnlinkSparesTheOthersCountsItsBytesAndIsRetriedNextPass() throws Exception {
        Three t = threeSealed();
        Journal journal = t.journal();
        FakeClock clock = t.clock();
        LargeBodyCrashTest.KeptRows rows = t.rows();
        List<Long> sealed = t.sealed();
        Path segments = t.segments();
        long failing = sealed.get(1);
        Path file = segments.resolve(failing + ".seg");
        long failingBytes = Files.size(file);
        long before = journal.bytes();
        long othersBytes = Files.size(segments.resolve(sealed.get(0) + ".seg")) + Files.size(segments.resolve(sealed.get(2) + ".seg"));
        Path aside = tmp.resolve("aside.seg");
        Files.move(file, aside);
        Files.createDirectories(file);
        Files.writeString(file.resolve("keep"), "x");   // a non-empty directory: its unlink fails
        clock.advance(SegmentSweepIT.GRACE);
        SegmentSweeper sweeper = new SegmentSweeper(journal, rows, List::of, SegmentSweepIT.CONFIG, clock, new SegmentSweepIT.StillTimer());
        closing.add(sweeper);

        Future<Set<Long>> first = JournalWorld.settled(sweeper.sweep());
        assertTrue(first.succeeded(), "a failed unlink fails no pass: " + (first.failed() ? JournalWorld.causes(first.cause()) : ""));
        assertFalse(Files.exists(segments.resolve(sealed.get(0) + ".seg")), "the others are unlinked: " + sealed.get(0));
        assertFalse(Files.exists(segments.resolve(sealed.get(2) + ".seg")), "the others are unlinked: " + sealed.get(2));
        assertEquals(before - othersBytes, journal.bytes(), "the failed one's " + failingBytes + " bytes still count");

        Files.delete(file.resolve("keep"));
        Files.delete(file);
        Files.move(aside, file, StandardCopyOption.ATOMIC_MOVE);
        JournalWorld.settled(sweeper.sweep());
        assertFalse(Files.exists(file), "the next pass unlinks the failed one");
        assertEquals(before - othersBytes - failingBytes, journal.bytes(), "and its bytes go with it");
    }

    /**
     * CORE-4d review r2: the file of a segment due for a sweep is already gone (NoSuchFileException at its unlink) — it counts as
     * unlinked: the pass succeeds, the bytes it had no longer count, and it is not pending: a file put at its path after the pass
     * is not touched by the next pass, whose bytes stay as they were. The other segments of the pass go as always.
     */
    @Test
    void aSegmentFileAlreadyGoneCountsAsUnlinkedAndIsNotPending() throws Exception {
        Three t = threeSealed();
        Journal journal = t.journal();
        long gone = t.sealed().get(1);
        Path file = t.segments().resolve(gone + ".seg");
        long goneBytes = Files.size(file);
        long othersBytes = Files.size(t.segments().resolve(t.sealed().get(0) + ".seg")) + Files.size(t.segments().resolve(t.sealed().get(2) + ".seg"));
        long before = journal.bytes();
        Files.delete(file);   // gone before the pass: its unlink meets NoSuchFileException
        t.clock().advance(SegmentSweepIT.GRACE);
        SegmentSweeper sweeper = new SegmentSweeper(journal, t.rows(), List::of, SegmentSweepIT.CONFIG, t.clock(), new SegmentSweepIT.StillTimer());
        closing.add(sweeper);

        Future<Set<Long>> first = JournalWorld.settled(sweeper.sweep());
        assertTrue(first.succeeded(), "a file already gone fails no pass: " + (first.failed() ? JournalWorld.causes(first.cause()) : ""));
        assertFalse(Files.exists(t.segments().resolve(t.sealed().get(0) + ".seg")), "fixture: the others are unlinked");
        assertFalse(Files.exists(t.segments().resolve(t.sealed().get(2) + ".seg")), "fixture: the others are unlinked");
        assertEquals(before - othersBytes - goneBytes, journal.bytes(), "its " + goneBytes + " bytes no longer count");

        Files.write(file, new byte[123]);   // a file at its path after the pass: a pending unlink would take it
        long after = journal.bytes();
        Future<Set<Long>> second = JournalWorld.settled(sweeper.sweep());
        assertTrue(second.succeeded(), "fixture: the next pass succeeds: " + (second.failed() ? JournalWorld.causes(second.cause()) : ""));
        assertTrue(Files.exists(file), "not pending: the next pass does not unlink at its path again");
        assertEquals(after, journal.bytes(), "and counts nothing off for it");
    }
}
