package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-3 r1 red tests of CORE-4b carried into CORE-4d: a large body's persist inserts its segment row, then moves its spill file
 * into {@code segments/}, then its kept ref is committed by the store. A crash between the row and the move leaves a row whose
 * file is not there (the start clears {@code spill/}): the start drops it. A crash between the move and the ref leaves a sealed
 * segment nothing references: the sweeper sweeps it once the grace after the start passed. Crashes are kill -9 images
 * ({@link JournalFixture#crashImage}); the rows are a double at the {@link SegmentRows} boundary ({@link KeptRows}, no pins:
 * Postgres's pins are {@link SegmentSweepIT}'s).
 *
 * <p>The API assumed (CORE-4d, test-author): {@code SegmentRows.sweep(String node, Set<Long> candidates, Instant keptSince)}
 * answering {@code Future<Set<Long>>} — of {@code candidates}, the rows no pin holds (a kept ref pins while created after
 * {@code keptSince} = clock − KEEP_MAX_AGE), each locked, re-checked and deleted; the sweeper unlinks their files — and the
 * {@link SegmentSweeper} of {@link SegmentSweepIT}.
 */
@Timeout(60)
class LargeBodyCrashTest {

    static final long LARGE = 10_000;   // above RECORD_MAX 4096: a spill

    @TempDir Path tmp;
    final List<Journal> journals = new CopyOnWriteArrayList<>();
    final List<SegmentSweeper> sweepers = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (SegmentSweeper s : sweepers) s.close();
        for (Journal j : journals) j.close();
    }

    /** Segment rows kept in memory: inserted, listed, dropped at start, swept (no pins); every drop and sweep recorded. */
    static final class KeptRows implements SegmentRows {
        final Set<Long> rows = ConcurrentHashMap.newKeySet();
        final List<Long> dropped = new CopyOnWriteArrayList<>();
        final List<Long> swept = new CopyOnWriteArrayList<>();
        final AtomicReference<Long> crashAt = new AtomicReference<>();
        volatile Runnable onCrashInsert = () -> { };
        volatile java.util.UUID journal;

        KeptRows() { }

        /** The rows a restart finds: {@code rows} and the journal id registered before the crash. */
        KeptRows(Set<Long> rows, java.util.UUID journal) {
            this.rows.addAll(rows);
            this.journal = journal;
        }

        @Override public Future<java.util.UUID> journalOf(String node) { return Future.succeededFuture(journal); }

        @Override public Future<Void> register(String node, java.util.UUID id) {
            journal = id;
            return Future.succeededFuture();
        }

        @Override public Future<Void> insert(String node, long segment) {
            rows.add(segment);
            Long at = crashAt.get();
            if (at != null && at == segment && crashAt.compareAndSet(at, null)) onCrashInsert.run();
            return Future.succeededFuture();
        }

        @Override public Future<Set<Long>> segments(String node) { return Future.succeededFuture(Set.copyOf(rows)); }

        @Override public Future<Void> drop(String node, Set<Long> segments) {
            rows.removeAll(segments);
            dropped.addAll(segments);
            return Future.succeededFuture();
        }

        @Override public Future<Set<Long>> sweep(String node, Set<Long> candidates, Instant keptSince) {
            Set<Long> gone = new TreeSet<>(candidates);
            gone.retainAll(rows);
            rows.removeAll(gone);
            swept.addAll(gone);
            return Future.succeededFuture(Set.copyOf(gone));
        }
    }

    Journal open(Path dir, HeldFiles files, KeptRows rows, FakeClock clock) throws Exception {
        JournalConfig config = settings(dir).recordMax(4096).pendingBytes(1L << 20).config();
        Journal j = OnVirtualThread.call(() -> Journal.open(config, clock, new FakeTimer(clock), keys(KEY), files, rows));
        journals.add(j);
        return j;
    }

    static Set<Long> files(Path dir, String sub) {
        Set<Long> out = new TreeSet<>();
        Path d = dir.resolve(sub);
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> l = Files.list(d)) {
            for (Path p : l.toList()) {
                String f = p.getFileName().toString();
                if (f.matches("[0-9]+\\..*")) out.add(Long.parseLong(f.substring(0, f.indexOf('.'))));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    @Test
    void aRowWhoseFileWasNeverMovedIsDroppedAtStart() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("journal"));
        HeldFiles files = new HeldFiles();
        KeptRows rows = new KeptRows();
        FakeClock clock = FakeClock.at(JournalFixture.START);
        Journal j = open(dir, files, rows, clock);
        Journal.Spill spill = OnVirtualThread.call(() -> j.spill(BlobKey.body("m-large", 1), KEY, new GeneratedInput(7, LARGE), LARGE, Hasher.sha256()));
        long n = spill.segment();
        Path image = tmp.resolve("image");
        AtomicReference<Set<Long>> rowsAtCrash = new AtomicReference<>();
        rows.crashAt.set(n);
        rows.onCrashInsert = () -> {   // the row is in, the spill file not yet moved: kill -9 here
            try {
                JournalFixture.crashImage(dir, files, image);
                rowsAtCrash.set(Set.copyOf(rows.rows));
            } catch (Exception e) {
                throw new AssertionError("crash image", e);
            }
        };
        OnVirtualThread.call(spill::persist);
        assertNotNull(rowsAtCrash.get(), "fixture: the crash image was taken at the persist's row insert");
        assertTrue(rowsAtCrash.get().contains(n), "fixture: the row of " + n + " was in at the crash");
        assertTrue(files(image, "spill").contains(n), "fixture: its file was still in spill/ at the crash");
        assertFalse(files(image, "segments").contains(n), "fixture: and not in segments/");

        KeptRows restartRows = new KeptRows(rowsAtCrash.get(), rows.journal);
        Journal restarted = open(image, new HeldFiles(), restartRows, FakeClock.at(JournalFixture.START.plusSeconds(60)));

        assertEquals(List.of(n), restartRows.dropped.stream().filter(x -> x == n).toList(), "the row whose file was never moved is dropped at start, once");
        assertFalse(restartRows.rows.contains(n), "it is gone");
        assertEquals(Set.of(), files(image, "spill"), "spill/ is cleared");
        assertFalse(files(image, "segments").contains(n), "no file of it appears");
        assertTrue(restartRows.rows.stream().allMatch(r -> files(image, "segments").contains(r)), "witness: the rows with a file stay: " + restartRows.rows);
        assertFalse(restarted.isFailed());
    }

    @Test
    void aMovedFileWithoutARefIsSwept() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("journal"));
        HeldFiles files = new HeldFiles();
        KeptRows rows = new KeptRows();
        FakeClock clock = FakeClock.at(JournalFixture.START);
        Journal j = open(dir, files, rows, clock);
        Journal.Spill spill = OnVirtualThread.call(() -> j.spill(BlobKey.body("m-large", 1), KEY, new GeneratedInput(8, LARGE), LARGE, Hasher.sha256()));
        JournalRef ref = OnVirtualThread.call(spill::persist);   // moved, its row in; the store never commits its ref: kill -9 now
        long n = ref.segment();
        Path image = JournalFixture.crashImage(dir, files, tmp.resolve("image"));
        assertTrue(files(image, "segments").contains(n), "fixture: its file was moved before the crash");

        Instant restart = JournalFixture.START.plus(Duration.ofHours(2));
        FakeClock later = FakeClock.at(restart);
        KeptRows restartRows = new KeptRows(Set.copyOf(rows.rows), rows.journal);
        Journal restarted = open(image, new HeldFiles(), restartRows, later);
        assertTrue(restartRows.rows.contains(n), "fixture: a moved file keeps its row at start");
        SegmentSweeper sweeper = new SegmentSweeper(restarted, restartRows, List::of, SegmentSweepIT.CONFIG, later, new SegmentSweepIT.StillTimer());
        sweepers.add(sweeper);

        later.advance(SegmentSweepIT.GRACE.minusMillis(1));
        Set<Long> early = SegmentSweepIT.sweep(sweeper);
        assertFalse(early.contains(n), "inside the grace after the start: kept " + early);
        assertTrue(files(image, "segments").contains(n), "its file stays inside the grace");

        later.advance(Duration.ofMillis(1));
        Set<Long> swept = SegmentSweepIT.sweep(sweeper);
        assertTrue(swept.contains(n), "a moved large segment nothing references is swept: " + swept);
        assertTrue(restartRows.swept.contains(n), "its row deleted through the rows");
        SegmentSweepIT.until(() -> !files(image, "segments").contains(n), () -> "its file is unlinked");
        List<Long> remaining = new ArrayList<>(restartRows.rows);
        assertFalse(remaining.contains(n));
    }
}
