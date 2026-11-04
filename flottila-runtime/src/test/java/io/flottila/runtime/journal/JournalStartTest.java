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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.zip.CRC32C;

import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.crashImage;
import static io.flottila.runtime.journal.JournalFixture.inImage;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start beyond the packet's recovery cases (CORE-3 item 9, file part): the LOCK, the NODE identity, the refusals a
 * start makes instead of cutting (a header that fails its CRC over records, another journal's segment), what the scan
 * cuts (zeros, a CRC-sound record of no known format, a tail torn inside the first record) and what it reads in pieces.
 */
class JournalStartTest {

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<Journal> journals = new ArrayList<>();
    private int images;

    @AfterEach
    void closeEverything() throws Exception {
        for (Journal j : journals) j.close();
    }

    private Path dir(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name));
    }

    private static Settings in(Path dir) {
        return settings(dir).segmentAge(Duration.ofHours(1));
    }

    private Journal open(Settings s, JournalFiles files) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys(JournalFixture.KEY), files);
        journals.add(j);
        return j;
    }

    private Journal open(Settings s) throws IOException {
        return open(s, JournalFiles.real());
    }

    private void close(Journal j) throws IOException {
        journals.remove(j);
        j.close();
    }

    private IOException refusedOpen(Settings s) {
        return assertThrows(IOException.class, () -> Journal.open(s.config(), clock, timer, keys(JournalFixture.KEY), JournalFiles.real()));
    }

    private static JournalRef appendForced(Journal j, JournalRecord r) throws Exception {
        Journal.Appended a = j.append(r, live());
        await(a.forced());
        return a.ref();
    }

    private Path image(Path dir, HeldFiles files) throws IOException {
        return crashImage(dir, files, tmp.resolve("image-" + (++images)));
    }

    private static List<String> indexed(Journal j, long n) throws IOException {
        Optional<List<JournalIndexEntry>> index = j.index(n);
        assertTrue(index.isPresent(), "segment " + n + " is sealed after the start");
        return index.get().stream().sorted(Comparator.comparingLong(e -> e.ref().position())).map(JournalIndexEntry::messageId).toList();
    }

    private static void truncate(Path file, long size) throws IOException {
        try (FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) {
            c.truncate(size);
        }
    }

    private static void flip(Path file, long at) throws IOException {
        byte[] all = Files.readAllBytes(file);
        all[(int) at] ^= 0x5A;
        Files.write(file, all);
    }

    @Test
    void aSecondOpenOfAnOpenJournalIsRefusedAndTheCloseGivesTheLockBack() throws Exception {
        Path dir = dir("lock");
        Journal first = open(in(dir));
        JournalRef ref = appendForced(first, record("lk-1", 100));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Journal.open(in(dir).config(), clock, timer, keys(JournalFixture.KEY), JournalFiles.real()));
        assertTrue(refused.getMessage().contains("LOCK"), refused.getMessage());
        assertArrayEquals(body("lk-1", 100), first.read(ref), "the refused open left the open journal alone");
        close(first);

        Journal second = open(in(dir));
        assertArrayEquals(body("lk-1", 100), second.read(ref));
    }

    @Test
    void aNodeFileOfAnotherNodeOrADamagedOneIsRefused() throws Exception {
        Path dir = dir("node");
        Journal first = open(in(dir));
        JournalRef ref = appendForced(first, record("nd-1", 100));
        close(first);

        Settings other = in(dir);
        other.node = "node-2";
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Journal.open(other.config(), clock, timer, keys(JournalFixture.KEY), JournalFiles.real()));
        assertTrue(refused.getMessage().contains("NODE"), refused.getMessage());

        Journal back = open(in(dir));   // the refused start gave its LOCK back
        assertArrayEquals(body("nd-1", 100), back.read(ref));
        close(back);

        Files.write(dir.resolve("NODE"), new byte[3]);
        assertThrows(IllegalStateException.class, () -> Journal.open(in(dir).config(), clock, timer, keys(JournalFixture.KEY), JournalFiles.real()),
                "a NODE file too short to name a journal");
    }

    @Test
    void aNodeIdIsOneTo255Utf8Bytes() throws Exception {
        Path dir = dir("node-id");
        for (String bad : List.of("", "é".repeat(128))) {
            Settings s = in(dir);
            s.node = bad;
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::config);
            assertTrue(e.getMessage().contains("FLOTTILA_NODE_ID"), e.getMessage());
        }
        Settings s = in(dir);
        s.node = "n".repeat(255);
        assertDoesNotThrow(s::config);
    }

    @Test
    void aHeaderThatFailsItsCrcOverRecordsRefusesTheStartAndCutsNothing() throws Exception {
        Path dir = dir("header");
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir), files);
        JournalRef ref = appendForced(j, record("hd-1", 500));
        Path segment = files.fileOf("hd-1");
        close(j);

        long createdMsLastByte = ref.position() - 5;   // the first record starts where the header ends: · created ms u64 · crc32c u32
        flip(segment, createdMsLastByte);   // the header still names this journal and this segment: only its CRC32C tells
        byte[] before = Files.readAllBytes(segment);
        IOException refused = refusedOpen(in(dir));
        assertTrue(refused instanceof JournalCorruptException && refused.getMessage().contains("fails its CRC32C"),
                "a damaged header, not another journal's: " + refused);
        assertArrayEquals(before, Files.readAllBytes(segment), "the refusal cut nothing");

        flip(segment, createdMsLastByte);
        Journal back = open(in(dir));   // the refusal gave the LOCK back
        assertArrayEquals(body("hd-1", 500), back.read(ref));
    }

    @Test
    void anotherJournalsSegmentRefusesTheStart() throws Exception {
        Path a = dir("journal-a");
        HeldFiles files = new HeldFiles();
        Journal ja = open(in(a), files);
        appendForced(ja, record("fj-1", 100));
        Path segmentOfA = files.fileOf("fj-1");
        close(ja);
        Path b = dir("journal-b");
        close(open(in(b)));   // b has a journal id of its own

        Files.copy(segmentOfA, b.resolve("segments").resolve(segmentOfA.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        IOException refused = refusedOpen(in(b));
        assertTrue(refused instanceof JournalCorruptException && refused.getMessage().contains("not segment"), String.valueOf(refused));
    }

    @Test
    void aStrayFileInTheSegmentsDirectoryIsLeftAlone() throws Exception {
        Path dir = dir("stray");
        close(open(in(dir)));
        Path stray = dir.resolve("segments").resolve("007.seg");
        byte[] content = "not a segment of this journal".getBytes();
        Files.write(stray, content);

        Journal j = open(in(dir));
        JournalRef ref = appendForced(j, record("st-1", 100));
        assertArrayEquals(body("st-1", 100), j.read(ref));
        assertArrayEquals(content, Files.readAllBytes(stray));
    }

    @Test
    void aSegmentTornInsideItsFirstRecordIsRemovedAndNumbersGoOn() throws Exception {
        Path dir = dir("first-torn");
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir), files);
        JournalRef ref = appendForced(j, record("tf-1", 900));
        Path image = image(dir, files);
        Path segment = inImage(dir, image, files.fileOf("tf-1"));
        truncate(segment, ref.position() + 10);

        Journal back = open(in(image));
        assertFalse(Files.exists(segment), "a segment holding no whole record is removed");
        assertThrows(IOException.class, () -> back.read(ref));
        assertTrue(appendForced(back, record("tf-2", 10)).segment() > ref.segment(), "the next segment takes a number above every number seen");
    }

    @Test
    void zerosAfterTheLastRecordAreCut() throws Exception {
        Path dir = dir("zeros");
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir), files);
        JournalRef z1 = appendForced(j, record("zr-1", 300));
        JournalRef z2 = appendForced(j, record("zr-2", 300));
        Path image = image(dir, files);
        Path segment = inImage(dir, image, files.fileOf("zr-1"));
        Files.write(segment, new byte[4096], StandardOpenOption.APPEND);   // a preallocated tail: a record length of 0

        try (LogCapture log = new LogCapture()) {
            Journal back = open(in(image));
            assertEquals(List.of("zr-1", "zr-2"), indexed(back, z1.segment()));
            assertArrayEquals(body("zr-2", 300), back.read(z2));
            assertTrue(log.count(Level.WARNING) >= 1, "the cut is a WARNING: " + log.lines());
        }
        assertTrue(Files.size(segment) < z2.position() + z2.length() + 4096, "the zeros are gone");
    }

    @Test
    void aTrailerThatFailsItsCrcIsRecoveredAsUnsealed() throws Exception {
        Path dir = dir("trailer");
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir).segmentRecords(2), files);
        JournalRef t1 = appendForced(j, record("tc-1", 400));
        appendForced(j, record("tc-2", 400));
        long n = t1.segment();
        until(() -> sealed(j, n), () -> "n is sealed");
        Path segment = files.fileOf("tc-1");
        close(j);
        flip(segment, Files.size(segment) - 21);   // the low byte of the trailer's entry count (2 → 88): its magic holds, its CRC does not

        Journal back = open(in(dir).segmentRecords(2));
        assertEquals(List.of("tc-1", "tc-2"), indexed(back, n), "scanned as unsealed and sealed again");
        assertArrayEquals(body("tc-1", 400), back.read(t1));
    }

    @Test
    void aTrailerWithoutItsEndMagicIsRecoveredAsUnsealed() throws Exception {
        Path dir = dir("trailer-magic");
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir).segmentRecords(2), files);
        JournalRef m1 = appendForced(j, record("tm-1", 400));
        appendForced(j, record("tm-2", 400));
        long n = m1.segment();
        until(() -> sealed(j, n), () -> "n is sealed");
        Path segment = files.fileOf("tm-1");
        close(j);
        byte[] sealedOnce = Files.readAllBytes(segment);
        byte[] edited = sealedOnce.clone();
        edited[edited.length - 1] ^= 0x5A;   // the end magic is the last u32, after the trailer's CRC32C: the CRC still holds
        Files.write(segment, edited);

        try (LogCapture log = new LogCapture()) {
            Journal back = open(in(dir).segmentRecords(2));
            assertEquals(List.of("tm-1", "tm-2"), indexed(back, n), "scanned as unsealed and sealed again");
            assertTrue(log.count(Level.WARNING) >= 1, "the old footer is cut: " + log.lines());
        }
        byte[] now = Files.readAllBytes(segment);
        assertArrayEquals(Arrays.copyOfRange(sealedOnce, sealedOnce.length - 4, sealedOnce.length),
                Arrays.copyOfRange(now, now.length - 4, now.length), "the new seal ends with the end magic again");
    }

    private static boolean sealed(Journal j, long n) {
        try {
            return j.index(n).isPresent();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aRecordLongerThanAScanPieceAndAChunkIsScannedAndReadBack() throws Exception {
        Path dir = dir("long");
        Settings s = in(dir).recordMax(2L << 20);
        HeldFiles files = new HeldFiles();
        Journal j = open(s, files);
        JournalRef ref = appendForced(j, record("lg-1", 1_500_000));   // two 1 MiB chunks; six 256 KiB scan pieces
        assertArrayEquals(body("lg-1", 1_500_000), j.read(ref));
        Path image = image(dir, files);

        Journal back = open(in(image).recordMax(2L << 20));
        assertEquals(List.of("lg-1"), indexed(back, ref.segment()));
        assertArrayEquals(body("lg-1", 1_500_000), back.read(ref));
    }

    /** A record of no known format whose CRC32C holds (CORE-3 item 2 field order). */
    enum NotV1 { VERSION, KIND, PLAIN_MINUS_ONE_THAT_ADDS_UP }

    @ParameterizedTest
    @EnumSource(NotV1.class)
    void aRecordWhoseCrcHoldsButThatIsNoV1RecordIsCutAtStart(NotV1 edit) throws Exception {
        Path dir = dir("not-v1-" + edit);
        HeldFiles files = new HeldFiles();
        Journal j = open(in(dir), files);
        JournalRef v1 = appendForced(j, record("v1-1", 300));
        JournalRef v2 = appendForced(j, record("v1-2", 300));
        appendForced(j, record("v1-3", 300));
        Path image = image(dir, files);
        Path segment = inImage(dir, image, files.fileOf("v1-1"));
        byte[] all = Files.readAllBytes(segment);
        byte[] rec = Arrays.copyOfRange(all, (int) v2.position(), (int) (v2.position() + v2.length()));
        ByteBuffer b = ByteBuffer.wrap(rec);   // crc u32 · length u32 · version u8 · kind u8 · key id u32 · plain u64 · id · step u32 · part · salt[16]
        switch (edit) {
            case VERSION -> b.put(8, (byte) 2);
            case KIND -> b.put(9, (byte) 2);
            case PLAIN_MINUS_ONE_THAT_ADDS_UP -> {
                int idLength = Short.toUnsignedInt(b.getShort(22));
                int partLength = Short.toUnsignedInt(b.getShort(24 + idLength + 4));
                int chunksAt = 24 + idLength + 4 + 2 + partLength + 16;
                rec = Arrays.copyOf(rec, chunksAt + 15);   // plain −1 with one 16-byte tag: the sizes add up to this length
                b = ByteBuffer.wrap(rec);
                b.putInt(4, rec.length).putLong(14, -1);
            }
        }
        CRC32C crc = new CRC32C();
        crc.update(rec, 4, rec.length - 4);
        b.putInt(0, (int) crc.getValue());
        byte[] edited = new byte[all.length - v2.length() + rec.length];
        System.arraycopy(all, 0, edited, 0, (int) v2.position());
        System.arraycopy(rec, 0, edited, (int) v2.position(), rec.length);
        System.arraycopy(all, (int) (v2.position() + v2.length()), edited, (int) v2.position() + rec.length,
                all.length - (int) (v2.position() + v2.length()));
        Files.write(segment, edited);

        Journal back = open(in(image));
        assertEquals(List.of("v1-1"), indexed(back, v1.segment()), "cut at the record of no known format");
        assertArrayEquals(body("v1-1", 300), back.read(v1));
    }
}
