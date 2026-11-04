package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bound on a spill (CORE-3 item 11, CORE-4d; the implementer's): a spill's bytes count as they are written, one that would
 * pass max while it streams is refused and its file goes off the hot path, its bytes counted until then; its footer is
 * refused above its bound's limit with nothing written and the journal not failed, and taken from the reserve up to
 * {@code FLOTTILA_JOURNAL_MAX_BYTES}; an abandoned spill's bytes leave the bound, and the 80 % WARNING logs again only once
 * the bytes fell below 80 %. The bytes are the files: a sealed segment's footer counts.
 */
class JournalSpillAccountingTest {

    private static final long MAX = 1L << 20;
    /** A body above the intake's limit (max − max/16) once spilled, far below max with its footer. */
    private static final int ABOVE_INTAKE = 1_000_000;

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private Journal journal;

    @AfterEach
    void closeJournal() throws Exception {
        if (journal != null) journal.close();
    }

    private Journal open(String name) throws IOException {
        Settings s = settings(Files.createDirectories(tmp.resolve(name))).segmentAge(Duration.ofHours(1)).maxBytes(MAX);
        journal = Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real(), (node, segment) -> Future.succeededFuture());
        Journal j = journal;
        until(() -> j.holds(2), () -> "the open's pre-open of segment 2 counted its header");
        return journal;
    }

    private Journal.Spill spill(String id, int seed, int size) throws IOException {
        return journal.spill(BlobKey.body(id, 0), KEY, new GeneratedInput(seed, size), MAX, Hasher.sha256());
    }

    /** The sizes of the files in {@code dir}; one unlinked while they are listed (a refused spill's, off the hot path) counts 0. */
    private static long sizes(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            long n = 0;
            for (Path f : files.toList()) {
                try {
                    n += Files.size(f);
                } catch (java.nio.file.NoSuchFileException gone) {
                    // unlinked since it was listed: no bytes on disk
                }
            }
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long maxWarnings(LogCapture logs) {
        return logs.records().stream()
                .filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("80 % of FLOTTILA_JOURNAL_MAX_BYTES")).count();
    }

    @Test
    void aFooterAboveTheIntakeLimitIsRefusedWithNothingWrittenAndTakenFromTheReserve() throws Exception {
        Journal j = open("footer");
        Path spills = tmp.resolve("footer").resolve("spill");
        Journal.Spill s = spill("sp-big", 1, ABOVE_INTAKE);
        long counted = j.bytes();
        long written = sizes(spills);
        assertTrue(counted > MAX - MAX / 16, "fixture: the spill's bytes pass the intake's limit: " + counted);

        assertThrows(JournalFullException.class, s::persist, "its footer is refused under the intake's limit");
        assertEquals(counted, j.bytes(), "nothing counted");
        assertEquals(written, sizes(spills), "nothing written");
        assertFalse(j.isFailed(), "a refusal is no failure of the journal");

        JournalRef ref = s.persist(Journal.Bound.RESERVE);
        assertTrue(j.bytes() > counted && j.bytes() <= MAX, "the reserve takes the footer, up to max: " + j.bytes());
        assertArrayEquals(new GeneratedInput(1, ABOVE_INTAKE).readAllBytes(), j.stream(ref).readAllBytes(), "and the spill is persisted");
    }

    @Test
    void anAbandonedSpillLeavesTheBoundAndTheWarningRepeatsOnlyBelowEightyPercent() throws Exception {
        Journal j = open("abandon");
        try (LogCapture logs = new LogCapture()) {
            Journal.Spill a = spill("sp-a", 1, 900_000);
            assertEquals(1, maxWarnings(logs), "crossing 80 % of max is a WARNING: " + logs.lines());
            long withA = j.bytes();

            Journal.Spill b = spill("sp-b", 2, 50_000);
            assertTrue(j.bytes() > withA, "fixture: b counted");
            b.abandon();
            assertEquals(withA, j.bytes(), "an abandoned spill's bytes leave the bound");
            Journal.Spill c = spill("sp-c", 3, 50_000);
            assertEquals(1, maxWarnings(logs), "still above 80 % since the WARNING: not logged again: " + logs.lines());

            c.abandon();
            a.abandon();
            assertTrue(j.bytes() < MAX - MAX / 5, "fixture: below 80 % again: " + j.bytes());
            spill("sp-d", 4, 900_000);
            assertEquals(2, maxWarnings(logs), "a crossing after the bytes fell below 80 % is a WARNING again: " + logs.lines());
        }
    }

    @Test
    void aSpillThatWouldPassMaxWhileItStreamsIsRefusedAndItsFileGoesWithItsBytes() throws Exception {
        Journal j = open("streams");
        Path dir = tmp.resolve("streams");
        spill("sp-a", 1, 900_000);
        long before = j.bytes();

        assertThrows(JournalFullException.class, () -> spill("sp-b", 2, 200_000), "the chunk that would pass max refuses the spill");
        assertTrue(j.bytes() <= MAX, "the bytes never pass max: " + j.bytes());
        assertTrue(sizes(dir.resolve("spill")) + sizes(dir.resolve("segments")) <= MAX, "nor do the files");
        assertFalse(j.isFailed(), "a refusal is no failure of the journal");
        until(() -> j.bytes() == before && sizes(dir.resolve("spill")) + sizes(dir.resolve("segments")) == before,
                () -> "its file is unlinked and its bytes go with it: " + j.bytes() + " counted, " + before + " before");
    }

    @Test
    void aSpillThatFailsOtherwiseLeavesNoBytesCounted() throws Exception {
        Journal j = open("too-large");
        long before = j.bytes();
        assertThrows(io.flottila.api.BodyTooLargeException.class,
                () -> j.spill(BlobKey.body("sp-big", 0), KEY, new GeneratedInput(5, 300_000), 100_000, Hasher.sha256()),
                "a body past its maxSize fails the spill");
        assertEquals(before, j.bytes(), "the chunks it wrote are no longer counted once its file is unlinked");
        assertEquals(0, sizes(tmp.resolve("too-large").resolve("spill")), "and the file is gone");
    }

    /** Each bound's refusals start with an ERROR of their own: an intake's first refusal does not silence the reserve's first. */
    @Test
    void eachBoundsFirstRefusalIsAnErrorOfItsOwn() throws Exception {
        Journal j = open("refusals");
        spill("sp-a", 1, 1_000_000);
        assertTrue(j.bytes() > MAX - MAX / 16, "fixture: past the intake's limit: " + j.bytes());
        try (LogCapture logs = new LogCapture()) {
            assertInstanceOf(JournalFullException.class, JournalFixture.appendFailure(j, JournalFixture.record("r-intake", 100)),
                    "an intake's record is refused");
            assertEquals(1, logs.count(Level.SEVERE), "the intake's refusals start: one ERROR: " + logs.lines());
            assertThrows(JournalFullException.class, () -> spill("sp-b", 2, 100_000), "a step body past max is refused");
            assertEquals(2, logs.count(Level.SEVERE), "the reserve's refusals start too: an ERROR of their own: " + logs.lines());
            assertInstanceOf(JournalFullException.class, JournalFixture.appendFailure(j, JournalFixture.record("r-intake-2", 100)),
                    "a second intake refusal");
            assertEquals(2, logs.count(Level.SEVERE), "logs no ERROR again: " + logs.lines());
        }
    }

    /** The WARNING is at 80 % of max exactly: bytes that reach {@code max − max/5} warn, one byte fewer does not. */
    @Test
    void theWarningIsAtEightyPercentExactly() throws Exception {
        Settings s = settings(Files.createDirectories(tmp.resolve("edge"))).segmentAge(Duration.ofHours(1)).recordMax(4096)
                .pendingBytes(512L << 10).segmentBytes(MAX).maxBytes(MAX);
        journal = Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real(), (node, segment) -> Future.succeededFuture());
        Journal j = journal;
        until(() -> j.holds(2), () -> "the open's pre-open of segment 2 counted its header");
        long warnAt = MAX - MAX / 5;
        try (LogCapture logs = new LogCapture()) {
            long overhead = reserve(j, 1_000) - 1_000;
            long below = warnAt - overhead - 1;   // a one-byte record from here reaches 80 % exactly
            while (below - j.bytes() - overhead > 3_000) reserve(j, 3_000);
            reserve(j, Math.toIntExact(below - j.bytes() - overhead));
            assertEquals(below, j.bytes(), "fixture: one record of one byte below 80 %");
            assertEquals(0, maxWarnings(logs), "below 80 %: no WARNING: " + logs.lines());

            reserve(j, 1);
            assertEquals(warnAt, j.bytes(), "fixture: 80 % exactly");
            assertEquals(1, maxWarnings(logs), "at 80 % exactly: the WARNING: " + logs.lines());
        }
    }

    private int next;

    /** A record of {@code size} bytes taken from the reserve and forced; its length (the bytes it counts). */
    private long reserve(Journal j, int size) throws Exception {
        String id = String.format("r-%05d", next++);
        return io.flottila.runtime.node.OnVirtualThread.call(() -> {
            Journal.Appended a = j.append(new JournalRecord(id, 1, "body", KEY, new byte[size]), JournalFixture.live(), Journal.Bound.RESERVE);
            JournalFixture.await(a.forced());
            return a.ref().length();
        });
    }

    /** The bytes are the files: a sealed segment counts its header, its records and its footer, as on disk. */
    @Test
    void theBytesOfASealedSegmentAreItsFile() throws Exception {
        Settings s = settings(Files.createDirectories(tmp.resolve("sealed"))).segmentRecords(1).maxBytes(MAX);
        journal = Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real(), (node, segment) -> Future.succeededFuture());
        Journal j = journal;
        Journal.Appended a = j.append(JournalFixture.record("r-1", 10_000), JournalFixture.live());
        JournalFixture.await(a.forced());
        long n = a.ref().segment();
        clock.advance(s.segmentAge);   // segment n seals by age
        until(() -> sealed(j, n), () -> "segment " + n + " sealed");

        Path segments = s.dir.resolve("segments");
        until(() -> sizes(segments) == j.bytes(), () -> "the journal's bytes are its segment files, footer included: " + j.bytes() + " counted, "
                + sizes(segments) + " on disk");
    }

    private static boolean sealed(Journal j, long n) {
        try {
            return io.flottila.runtime.node.OnVirtualThread.call(() -> j.index(n)).isPresent();
        } catch (Exception e) {
            throw new AssertionError("index of " + n, e);
        }
    }
}
