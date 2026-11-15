package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.crypto.SecretKey;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.logging.Level;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.crashImage;
import static io.flottila.runtime.journal.JournalFixture.inImage;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.recordBytes;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Start (CORE-3 item 9, the file part): a segment without a valid trailer (unsealed, or a torn seal) is scanned by length
 * bounds and CRC32C, with no tenant key, cut at its first bad record and sealed; a valid trailer with a bad index CRC is
 * read without the index (WARNING) and never rewritten; a record that fails its CRC on read fails as corrupt.
 *
 * <p>A crash is a {@link JournalFixture#crashImage crash image}: the directory of an open journal copied while every
 * append of the case is forced, which is what a kill -9 leaves. Bytes are then torn or flipped in the image.
 */
class JournalRecoveryTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<HeldFiles> seams = new ArrayList<>();
    private final List<Journal> journals = new ArrayList<>();
    private int images;

    @AfterEach
    void closeEverything() throws Exception {
        seams.forEach(HeldFiles::releaseAll);
        for (Journal j : journals) j.close();
    }

    private Settings in(Path dir) {
        return settings(dir).segmentAge(Duration.ofHours(1));
    }

    private Path dir(String name) throws Exception {
        return Files.createDirectories(tmp.resolve(name));
    }

    private Journal open(Settings s, HeldFiles files, IntFunction<SecretKey> keys) throws Exception {
        seams.add(files);
        Journal j = Journal.open(s.config(), clock, timer, keys, files);
        journals.add(j);
        return j;
    }

    private Journal open(Settings s, HeldFiles files) throws Exception {
        return open(s, files, keys(JournalFixture.KEY));
    }

    private void close(Journal j) throws Exception {
        journals.remove(j);
        j.close();
    }

    private Path image(Path dir, HeldFiles files) throws Exception {
        return crashImage(dir, files, tmp.resolve("image-" + (++images)));
    }

    /** Appends {@code ids} with bodies of {@code size}, each awaited: one record per round. */
    private static Map<String, JournalRef> appendAll(Journal journal, int size, String... ids) throws Exception {
        Map<String, JournalRef> refs = new LinkedHashMap<>();
        for (String id : ids) {
            Journal.Appended a = journal.append(record(id, size), live());
            await(a.forced());
            refs.put(id, a.ref());
        }
        return refs;
    }

    private static void readsBack(Journal journal, Map<String, JournalRef> refs, int size, List<String> ids) throws Exception {
        for (String id : ids) assertArrayEquals(body(id, size), journal.read(refs.get(id)), id + " reads back byte-equal");
    }

    private static List<String> indexed(Journal journal, long segment) throws Exception {
        Optional<List<JournalIndexEntry>> index = journal.index(segment);
        assertTrue(index.isPresent(), "segment " + segment + " is sealed after the start");
        return index.get().stream().sorted(Comparator.comparingLong(e -> e.ref().position())).map(JournalIndexEntry::messageId).toList();
    }

    private static void flip(Path file, long at) throws Exception {
        byte[] all = Files.readAllBytes(file);
        all[(int) at] ^= 0x5A;
        Files.write(file, all);
    }

    private static void truncate(Path file, long size) throws Exception {
        try (FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) {
            c.truncate(size);
        }
    }

    private static void readFails(Journal journal, JournalRef ref, String why) {
        assertThrows(Exception.class, () -> journal.read(ref), why);
    }

    @Test
    void aTornTailIsCutAtTheFirstBadCrc() throws Exception {
        Path dir = dir("torn");
        HeldFiles files = new HeldFiles();
        Journal journal = open(in(dir), files);
        String[] ids = {"tt-1", "tt-2", "tt-3", "tt-4", "tt-5"};
        Map<String, JournalRef> refs = appendAll(journal, 900, ids);
        long n = refs.get("tt-1").segment();
        Path segment = files.fileOf("tt-1");
        Path whole = image(dir, files);
        Path truncated = image(dir, files);
        Path flipped = image(dir, files);
        byte[] tt4 = recordBytes(inImage(dir, flipped, segment), refs.get("tt-4"));
        byte[] tt5 = recordBytes(inImage(dir, flipped, segment), refs.get("tt-5"));

        // witness: the untouched image recovers every record
        Journal back = open(in(whole), new HeldFiles());
        readsBack(back, refs, 900, List.of(ids));
        close(back);

        // a torn last record (the length runs past the end of the file): cut there, the four before it stay
        JournalRef last = refs.get("tt-5");
        truncate(inImage(dir, truncated, segment), last.position() + last.length() / 2);
        back = open(in(truncated), new HeldFiles());
        readsBack(back, refs, 900, List.of("tt-1", "tt-2", "tt-3", "tt-4"));
        readFails(back, last, "tt-5 was torn");
        assertEquals(List.of("tt-1", "tt-2", "tt-3", "tt-4"), indexed(back, n), "the cut segment is sealed over what survived");
        assertTrue(back.append(record("tt-6", 10), live()).ref().segment() > n, "after the start the writer never appends to a recovered segment");
        close(back);

        // a bad CRC in tt-3: cut at tt-3, so tt-4 and tt-5 go too although their bytes are intact
        JournalRef bad = refs.get("tt-3");
        flip(inImage(dir, flipped, segment), bad.position() + bad.length() - 5);
        back = open(in(flipped), new HeldFiles());
        readsBack(back, refs, 900, List.of("tt-1", "tt-2"));
        readFails(back, bad, "tt-3 failed its CRC");
        readFails(back, refs.get("tt-4"), "tt-4 lies past the cut");
        assertEquals(List.of("tt-1", "tt-2"), indexed(back, n), "cut at the first bad CRC, not skipped over");
        byte[] cut = Files.readAllBytes(inImage(dir, flipped, segment));
        assertEquals(-1, HeldFiles.indexOf(cut, tt4, 0), "tt-4's bytes are gone from the segment: it was cut, not kept");
        assertEquals(-1, HeldFiles.indexOf(cut, tt5, 0), "tt-5's bytes are gone from the segment");
    }

    @Test
    void recoveryNeedsNoTenantKey() throws Exception {
        Path dir = dir("no-key");
        HeldFiles files = new HeldFiles();
        Journal journal = open(in(dir), files);
        Map<String, JournalRef> refs = appendAll(journal, 900, "rk-1", "rk-2", "rk-3");
        long n = refs.get("rk-1").segment();
        Path image = image(dir, files);
        JournalRef torn = refs.get("rk-3");
        truncate(inImage(dir, image, files.fileOf("rk-1")), torn.position() + 7);   // a torn tail: the start must scan

        AtomicInteger asked = new AtomicInteger();
        Journal back = open(in(image), new HeldFiles(), id -> {
            asked.incrementAndGet();
            return null;
        });
        assertEquals(List.of("rk-1", "rk-2"), indexed(back, n), "scanned, cut and sealed with no tenant key");
        assertEquals(0, asked.get(), "the start asked for no tenant key: CRC32C only");
        close(back);

        Journal withKey = open(in(image), new HeldFiles());
        readsBack(withKey, refs, 900, List.of("rk-1", "rk-2"));
    }

    /** Where the seal of n is torn: inside its footer index, or inside its trailer. */
    enum Tear { INSIDE_THE_INDEX, INSIDE_THE_TRAILER }

    @ParameterizedTest
    @EnumSource(Tear.class)
    void aTornSealIsRecoveredAsUnsealed(Tear tear) throws Exception {
        Path dir = dir("torn-seal-" + tear);
        HeldFiles files = new HeldFiles();
        Journal journal = open(in(dir).segmentRecords(3), files);
        Map<String, JournalRef> refs = appendAll(journal, 900, "ts-1", "ts-2", "ts-3", "ts-4");
        long n = refs.get("ts-1").segment();
        assertEquals(n + 1, refs.get("ts-4").segment());
        until(() -> sealed(journal, n), () -> "n is sealed");
        Path image = image(dir, files);
        Path segment = inImage(dir, image, files.fileOf("ts-1"));
        JournalRef last = refs.get("ts-3");
        long recordsEnd = last.position() + last.length();
        long size = Files.size(segment);
        assertTrue(size > recordsEnd + 8, "the sealed segment carries a footer after its records (" + size + " > " + recordsEnd + ")");
        truncate(segment, tear == Tear.INSIDE_THE_INDEX ? recordsEnd + 3 : size - 2);

        Journal back = open(in(image).segmentRecords(3), new HeldFiles());
        assertEquals(List.of("ts-1", "ts-2", "ts-3"), indexed(back, n), "scanned as unsealed, cut at the torn footer and sealed again");
        readsBack(back, refs, 900, List.of("ts-1", "ts-2", "ts-3", "ts-4"));
    }

    private static boolean sealed(Journal journal, long segment) {
        try {
            return journal.index(segment).isPresent();
        } catch (Exception e) {
            throw new AssertionError("reading the index of " + segment + " failed", e);
        }
    }

    @Test
    void aBadIndexCrcReadsWithoutTheIndex() throws Exception {
        Path dir = dir("bad-index");
        HeldFiles files = new HeldFiles();
        Journal journal = open(in(dir).segmentRecords(3), files);
        Map<String, JournalRef> refs = appendAll(journal, 900, "ix-1", "ix-2", "ix-3", "ix-4");
        long n = refs.get("ix-1").segment();
        until(() -> sealed(journal, n), () -> "n is sealed");
        Path segment = files.fileOf("ix-1");
        close(journal);
        // two copies of the closed journal, equal but for one byte of n's footer index: what else a start logs is the same
        Path sound = image(dir, files);
        Path edited = image(dir, files);

        byte[] bytes = Files.readAllBytes(inImage(dir, edited, segment));
        JournalRef last = refs.get("ix-3");
        int inIndex = HeldFiles.indexOf(bytes, HeldFiles.bytes("ix-2"), (int) (last.position() + last.length()));
        assertTrue(inIndex > 0, "the footer index names ix-2 after the records");
        bytes[inIndex + 3] ^= 0x01;   // "ix-2" → "ix-3" in the index: the index CRC no longer matches, the trailer is untouched
        Files.write(inImage(dir, edited, segment), bytes);

        long soundWarnings;
        try (LogCapture log = new LogCapture()) {
            Journal clean = open(in(sound).segmentRecords(3), new HeldFiles());
            assertEquals(List.of("ix-1", "ix-2", "ix-3"), indexed(clean, n), "witness: the untouched footer is read at start");
            soundWarnings = log.count(Level.WARNING);
            close(clean);
        }
        try (LogCapture log = new LogCapture()) {
            Journal back = open(in(edited).segmentRecords(3), new HeldFiles());
            assertTrue(log.count(Level.WARNING) > soundWarnings,
                    "a bad index CRC is a WARNING (" + log.count(Level.WARNING) + " against " + soundWarnings + " for the sound copy): " + log.lines());
            assertEquals(Optional.empty(), back.index(n), "the index that fails its CRC is not used");
            readsBack(back, refs, 900, List.of("ix-1", "ix-2", "ix-3", "ix-4"));
            assertArrayEquals(bytes, Files.readAllBytes(inImage(dir, edited, segment)), "never rewritten at start or by the reads");
            close(back);
            assertArrayEquals(bytes, Files.readAllBytes(inImage(dir, edited, segment)), "never rewritten at close");
        }
    }

    @Test
    void aBadRecordCrcFailsItsReadAsCorrupt() throws Exception {
        Path dir = dir("bad-record");
        HeldFiles files = new HeldFiles();
        Journal journal = open(in(dir).segmentRecords(3), files);
        Map<String, JournalRef> refs = appendAll(journal, 900, "bc-1", "bc-2", "bc-3", "bc-4");
        long n = refs.get("bc-1").segment();
        until(() -> sealed(journal, n), () -> "n is sealed");
        Path segment = files.fileOf("bc-1");
        close(journal);
        JournalRef bad = refs.get("bc-2");
        flip(segment, bad.position() + bad.length() - 3);

        try (LogCapture log = new LogCapture()) {
            Journal back = open(in(dir).segmentRecords(3), new HeldFiles());
            readsBack(back, refs, 900, List.of("bc-1", "bc-3", "bc-4"));   // a sealed segment is not cut at start
            long errorsBefore = log.count(Level.SEVERE);
            Throwable corrupt = assertThrows(Exception.class, () -> back.read(bad));
            assertInstanceOf(JournalCorruptException.class, corrupt, "a record failing its CRC on read fails as corrupt, never as not found");
            until(() -> log.count(Level.SEVERE) > errorsBefore, () -> "the corrupt read is logged at ERROR: " + log.lines());
        }
    }
}
