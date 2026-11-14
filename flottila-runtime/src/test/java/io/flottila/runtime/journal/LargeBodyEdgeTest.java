package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.HeldFiles.Hold;
import io.flottila.runtime.journal.JournalFixture.LogCapture;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.zip.CRC32C;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static io.flottila.runtime.journal.LargeBodyTest.chunkAt;
import static io.flottila.runtime.journal.LargeBodyTest.drain;
import static io.flottila.runtime.journal.LargeBodyTest.onlyFile;
import static io.flottila.runtime.journal.LargeBodyTest.readAll;
import static io.flottila.runtime.journal.LargeBodyTest.regularFiles;
import static io.flottila.runtime.journal.LargeBodyTest.segmentFile;
import static io.flottila.runtime.journal.LargeBodyTest.spillDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large bodies beyond the packet's cases (CORE-4b, implementer): the spill's refusals and its cleanup, a spill's life after
 * persist, abandon, a failed footer and a close, reads of a large ref by {@code read} and {@code stream}, bodies on a chunk
 * boundary, and a start that meets a large segment without a valid trailer.
 */
class LargeBodyEdgeTest {

    static final int CHUNK = 1 << 20;
    /** The largest plaintext a record can carry: 2^31 − 1 chunks (CORE-3 item 2's counter beside its last flag). */
    static final long MAX_PLAIN = (long) Integer.MAX_VALUE * CHUNK;

    @TempDir Path tmp;

    private final FakeClock clock = FakeClock.at(START);
    private final FakeTimer timer = new FakeTimer(clock);
    private final List<HeldFiles> seams = new ArrayList<>();
    private final List<Journal> journals = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        seams.forEach(HeldFiles::releaseAll);
        for (Journal j : journals) j.close();
    }

    private Settings in(String name) throws IOException {
        return settings(Files.createDirectories(tmp.resolve(name))).recordMax(4096).segmentAge(Duration.ofHours(1));
    }

    private Journal open(Settings s, IntFunction<SecretKey> k, JournalFiles files, SegmentRows rows) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, k, files, rows);
        journals.add(j);
        return j;
    }

    private Journal open(Settings s) throws IOException {
        return open(s, keys(KEY), JournalFiles.real(), new RecordingRows());
    }

    private void close(Journal j) throws IOException {
        journals.remove(j);
        j.close();
    }

    private static Journal.Spill spill(Journal j, String id, long seed, long size) throws IOException {
        return j.spill(BlobKey.body(id, 0), KEY, new GeneratedInput(seed, size), size, Hasher.sha256());
    }

    private static JournalRef appendForced(Journal j, JournalRecord r) throws Exception {
        Journal.Appended a = j.append(r, live());
        await(a.forced());
        return a.ref();
    }

    @Test
    void maxSizeAboveTheChunkCounterIsRefusedBeforeAByteIsRead() throws Exception {
        Settings s = in("range");
        Journal j = open(s);
        GeneratedInput untouched = new GeneratedInput(1, 10);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> j.spill(BlobKey.body("rg-1", 0), KEY, untouched, MAX_PLAIN + 1, Hasher.sha256()));
        assertTrue(e.getMessage().contains("maxSize"), e.getMessage());
        assertEquals(0, untouched.delivered());
        assertEquals(List.of(), regularFiles(spillDir(s.dir)));

        Journal.Spill atBound = j.spill(BlobKey.body("rg-2", 0), KEY, new GeneratedInput(2, 10), MAX_PLAIN, Hasher.sha256());
        assertEquals(10, atBound.size(), "the bound itself is taken");
    }

    @Test
    void aMissingOrUnusableTenantKeyIsRefusedAndLeavesNoFile() throws Exception {
        Settings s = in("keys");
        SecretKey notAes = new SecretKeySpec(new byte[32], "HmacSHA256");
        Journal j = open(s, id -> id == KEY ? notAes : null, JournalFiles.real(), new RecordingRows());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> j.spill(BlobKey.body("ky-1", 0), 9, new GeneratedInput(1, 10_000), 10_000, Hasher.sha256()));
        assertTrue(missing.getMessage().contains("9"), "names the key id: " + missing.getMessage());
        IllegalArgumentException unusable = assertThrows(IllegalArgumentException.class,
                () -> j.spill(BlobKey.body("ky-2", 0), KEY, new GeneratedInput(1, 10_000), 10_000, Hasher.sha256()));
        assertNotNull(unusable.getCause(), "the key's refusal rides along");
        assertEquals(List.of(), regularFiles(spillDir(s.dir)), "nothing is left under spill/");
    }

    @Test
    void anUnlinkThatFailsAfterAFailedSpillRidesOnTheFailure() throws Exception {
        Settings s = in("unlink");
        Journal j = open(s);
        IOException sourceFailed = new IOException("the source failed (test)");
        InputStream sabotage = new InputStream() {
            @Override public int read() throws IOException { return read(new byte[1], 0, 1); }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                // the spill file becomes a non-empty directory: its unlink must fail
                Path spilled = onlyFile(spillDir(s.dir));
                Files.delete(spilled);
                Files.createDirectories(spilled);
                Files.writeString(spilled.resolve("in-the-way"), "x");
                throw sourceFailed;
            }
        };

        IOException e = assertThrows(IOException.class, () -> j.spill(BlobKey.body("ul-1", 0), KEY, sabotage, 10_000, Hasher.sha256()));

        assertEquals(sourceFailed, e, "the spill fails with the source's failure");
        assertEquals(1, e.getSuppressed().length, "the failed unlink rides on it: " + Arrays.toString(e.getSuppressed()));
        assertInstanceOf(DirectoryNotEmptyException.class, e.getSuppressed()[0]);
    }

    @Test
    void aPersistedSpillReadsFromSegmentsAndAnswersItsRefAgain() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("again");
        Journal j = open(s, keys(KEY), JournalFiles.real(), rows);
        Journal.Spill spill = spill(j, "ag-1", 3, CHUNK + 9);

        JournalRef ref = spill.persist();

        assertEquals(ref, spill.persist(), "a second persist answers the same ref");
        assertEquals(1, rows.insertsOf(ref.segment()).size(), "and inserts no second row");
        assertArrayEquals(GeneratedInput.bytes(3, CHUNK + 9), readAll(spill.open()), "the spill reads from segments/ after the move");
        spill.abandon();
        assertTrue(Files.isRegularFile(segmentFile(s.dir, ref.segment())), "abandon after persist leaves the segment");
        assertArrayEquals(GeneratedInput.bytes(3, CHUNK + 9), readAll(j.stream(ref)));
    }

    @Test
    void aJournalWithoutSegmentRowsPersistsALargeBody() throws Exception {
        Settings s = in("no-rows");
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), JournalFiles.real());   // CORE-4a's open: no Postgres
        journals.add(j);
        JournalRef ref = spill(j, "nr-1", 21, 10_000).persist();
        assertTrue(Files.isRegularFile(segmentFile(s.dir, ref.segment())));
        assertArrayEquals(GeneratedInput.bytes(21, 10_000), readAll(j.stream(ref)));
    }

    @Test
    void anAbandonedSpillIsNeverPersisted() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("abandoned");
        Journal j = open(s, keys(KEY), JournalFiles.real(), rows);
        Journal.Spill spill = spill(j, "an-1", 4, 10_000);
        spill.abandon();

        assertThrows(IllegalStateException.class, spill::persist);
        assertEquals(List.of(), rows.insertsOf(spill.segment()), "no row for an abandoned spill");
        assertFalse(Files.exists(segmentFile(s.dir, spill.segment())));
        assertThrows(IOException.class, spill::open, "its file is gone");
    }

    @Test
    void aFailedFooterForceIsNeverRetriedAndTheSpillCanOnlyBeAbandoned() throws Exception {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        RecordingRows rows = new RecordingRows();
        Settings s = in("footer");
        Journal j = open(s, keys(KEY), files, rows);
        Journal.Spill spill = spill(j, "ft-1", 5, 10_000);
        Path spilled = onlyFile(spillDir(s.dir));
        Hold force = files.holdForceOn(spilled, files.now(), Thread.currentThread());
        IOException eio = new IOException("EIO on the spill's force (test)");
        force.fail(eio);

        IOException first = assertThrows(IOException.class, spill::persist);
        assertEquals(eio, first);
        int forces = files.forces(spilled.toAbsolutePath().normalize());
        IllegalStateException again = assertThrows(IllegalStateException.class, spill::persist);
        assertEquals(eio, again.getCause(), "the second persist names the first failure");
        assertEquals(forces, files.forces(spilled.toAbsolutePath().normalize()), "the failed force is never retried");
        assertEquals(List.of(), rows.insertsOf(spill.segment()), "no row for a spill whose footer failed");

        spill.abandon();
        assertEquals(List.of(), regularFiles(spillDir(s.dir)));
        assertFalse(Files.exists(segmentFile(s.dir, spill.segment())));
    }

    @Test
    void aClosedJournalRefusesSpillsAndTheirReadsButLetsThemBeAbandoned() throws Exception {
        Settings s = in("closed");
        Journal j = open(s);
        Journal.Spill spill = spill(j, "cl-1", 6, 10_000);
        JournalRef small = appendForced(j, record("cl-0", 100));
        close(j);

        assertThrows(IllegalStateException.class, () -> spill(j, "cl-2", 7, 10_000));
        assertThrows(IllegalStateException.class, spill::open);
        assertThrows(IllegalStateException.class, spill::persist);
        assertThrows(IllegalStateException.class, () -> j.stream(small));
        spill.abandon();
        assertEquals(List.of(), regularFiles(spillDir(s.dir)));
    }

    @Test
    void readTakesALargeRefUpToTheLongestSegmentRecordAndRefusesALongerOne() throws Exception {
        Settings s = in("read").recordMax(CHUNK);
        Journal j = open(s);
        JournalRef twoChunks = spill(j, "rd-1", 8, CHUNK + 100).persist();   // shorter than a record of FLOTTILA_JOURNAL_RECORD_MAX
        assertArrayEquals(GeneratedInput.bytes(8, CHUNK + 100), j.read(twoChunks), "read whole: both chunks authenticate");
        assertArrayEquals(GeneratedInput.bytes(8, CHUNK + 100), readAll(j.stream(twoChunks)));

        Journal small = open(in("read-small"));
        JournalRef large = spill(small, "rd-2", 9, 2L * CHUNK).persist();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> small.read(large));
        assertTrue(e.getMessage().contains("stream"), e.getMessage());
        assertArrayEquals(GeneratedInput.bytes(9, 2 * CHUNK), readAll(small.stream(large)));
    }

    @Test
    void streamReadsASegmentRecordToo() throws Exception {
        Journal j = open(in("small"));
        JournalRef ref = appendForced(j, record("sm-1", 700));
        assertArrayEquals(body("sm-1", 700), readAll(j.stream(ref)));

        Journal wide = open(in("small-wide").recordMax(2L * CHUNK));
        JournalRef twoChunks = appendForced(wide, record("sm-2", CHUNK + 10));   // each chunk's AAD is the whole header
        assertArrayEquals(body("sm-2", CHUNK + 10), readAll(wide.stream(twoChunks)));
    }

    @Test
    void aRetriedPersistWritesNoSecondFooter() throws Exception {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        RecordingRows rows = new RecordingRows();
        Settings s = in("retry");
        Journal j = open(s, keys(KEY), files, rows);
        Journal.Spill spill = spill(j, "rt-1", 18, 10_000);
        Path spilled = onlyFile(spillDir(s.dir)).toAbsolutePath().normalize();
        rows.failNext(spill.segment(), new IllegalStateException("refused (test)"));
        assertThrows(IllegalStateException.class, spill::persist);
        long afterFirst = files.now();

        JournalRef ref = spill.persist();

        assertEquals(List.of(), files.opsOnAfter(spilled, afterFirst), "the retry neither writes nor forces the spill again");
        assertArrayEquals(GeneratedInput.bytes(18, 10_000), readAll(j.stream(ref)));
    }

    @Test
    void aRefusedSecondOpenLeavesTheSpillsOfTheOpenJournalAlone() throws Exception {
        Settings s = in("second");
        Journal j = open(s);
        Journal.Spill spill = spill(j, "so-1", 19, 10_000);

        assertThrows(IllegalStateException.class, () -> open(s), "the LOCK is held");

        assertEquals(1, regularFiles(spillDir(s.dir)).size(), "the refused start cleared nothing");
        assertArrayEquals(GeneratedInput.bytes(19, 10_000), readAll(j.stream(spill.persist())));
    }

    @Test
    void anEmptyBodyAndOneEndingOnAChunkBoundaryReadBack() throws Exception {
        Journal j = open(in("bounds"));
        Journal.Spill empty = spill(j, "bd-0", 10, 0);
        assertEquals(GeneratedInput.expected(10, 0).hash(), empty.hash());
        assertArrayEquals(new byte[0], readAll(empty.open()));
        assertArrayEquals(new byte[0], readAll(j.stream(empty.persist())));

        Journal.Spill exact = spill(j, "bd-2", 11, 2L * CHUNK);
        assertEquals(2L * CHUNK, exact.size());
        JournalRef ref = exact.persist();
        // a writer that added an empty last chunk after two full ones would leave chunk 1 unflagged: the read would fail
        assertArrayEquals(GeneratedInput.bytes(11, 2 * CHUNK), readAll(j.stream(ref)));
    }

    @Test
    void aLargeStreamReadsByteByByteAndAnswersAnEmptyRead() throws Exception {
        Journal j = open(in("bytes"));
        byte[] body = GeneratedInput.bytes(12, CHUNK + 3);
        JournalRef ref = spill(j, "bb-1", 12, CHUNK + 3).persist();
        try (InputStream in = j.stream(ref)) {
            for (int i = 0; i < 3; i++) assertEquals(body[i] & 0xFF, in.read(), "byte " + i);
            assertEquals(0, in.read(new byte[4], 1, 0), "an empty read reads nothing");
            assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[4], 3, 2));
            assertArrayEquals(Arrays.copyOfRange(body, 3, body.length), in.readAllBytes());
            assertEquals(-1, in.read(), "then the end");
            assertEquals(0, in.read(new byte[4], 0, 0), "an empty read at the end still reads nothing");
        }
    }

    @Test
    void aRefLongerThanItsRecordFailsAsCorrupt() throws Exception {
        Journal j = open(in("longer"));
        JournalRef first = appendForced(j, record("lg-1", 300));
        JournalRef second = appendForced(j, record("lg-2", 300));
        assertEquals(first.segment(), second.segment());
        JournalRef both = new JournalRef(first.segment(), first.position(), first.length() + second.length());
        assertThrows(JournalCorruptException.class, () -> j.read(both), "the CRC of the first record holds, its length does not");

        JournalRef large = spill(j, "lg-3", 13, 2L * CHUNK).persist();
        JournalRef shorter = new JournalRef(large.segment(), large.position(), large.length() - 1);
        assertTrue(drain(() -> j.stream(shorter)).failure() instanceof JournalCorruptException);
    }

    @Test
    void anEditedChunkFailsTheStreamAsCorruptionLoggedAtError() throws Exception {
        Settings s = in("edited");
        Journal j = open(s);
        byte[] body = GeneratedInput.bytes(20, 2 * CHUNK + 5);
        JournalRef ref = spill(j, "ed-1", 20, 2L * CHUNK + 5).persist();
        Path segment = segmentFile(s.dir, ref.segment());
        byte[] all = Files.readAllBytes(segment);
        all[(int) chunkAt(ref, 2L * CHUNK + 5, 1) + 7] ^= 0x01;   // a ciphertext byte of chunk 1
        Files.write(segment, all);

        try (LogCapture log = new LogCapture()) {
            LargeBodyTest.Drained d = drain(() -> j.stream(ref));
            assertInstanceOf(JournalCorruptException.class, d.failure(), "corruption, not another I/O failure");
            assertArrayEquals(Arrays.copyOf(body, CHUNK), d.bytes(), "chunk 0 was given, nothing of chunk 1");
            until(() -> log.count(Level.SEVERE) >= 1, () -> "the corrupt chunk is an ERROR: " + log.lines());
        }
    }

    @Test
    void aLengthFieldBelowAnyHeaderFailsAsCorrupt() throws Exception {
        Settings s = in("tiny");
        Journal j = open(s);
        JournalRef ref = appendForced(j, record("tn-1", 300));
        close(j);
        Path segment = segmentFile(s.dir, ref.segment());
        byte[] all = Files.readAllBytes(segment);
        ByteBuffer.wrap(all).putInt((int) ref.position() + 4, 3);
        Files.write(segment, all);

        Journal back = open(s);
        assertThrows(JournalCorruptException.class, () -> back.read(ref));
    }

    @Test
    void aLargeHeaderOfAnotherKindIsNoRecord() throws Exception {
        Settings s = in("kind");
        Journal j = open(s);
        JournalRef sealed = spill(j, "kd-1", 14, 2L * CHUNK).persist();
        JournalRef torn = spill(j, "kd-2", 14, 2L * CHUNK).persist();
        close(j);
        editHeader(segmentFile(s.dir, sealed.segment()), sealed, h -> h.put(9, (byte) 3));
        editHeader(segmentFile(s.dir, torn.segment()), torn, h -> h.put(9, (byte) 3));
        flipLastByte(segmentFile(s.dir, torn.segment()));

        Journal back = open(s);

        assertTrue(drain(() -> back.stream(sealed)).failure() instanceof JournalCorruptException, "read: corrupt");
        assertFalse(Files.exists(segmentFile(s.dir, torn.segment())), "scanned: no record of a kind the format does not know");
    }

    @Test
    void aLargeSegmentWhoseTrailerIsTornIsScannedAndSealedAgain() throws Exception {
        Settings s = in("torn");
        Journal j = open(s);
        JournalRef ref = spill(j, "t-1", 15, CHUNK + 17).persist();   // a header shorter than the shortest segment record
        close(j);
        Path segment = segmentFile(s.dir, ref.segment());
        flipLastByte(segment);

        Journal back = open(s);

        assertEquals(Optional.of(List.of(new JournalIndexEntry("t-1", 0, "body", ref))), back.index(ref.segment()),
                "the scan found the large record and sealed the segment over it");
        assertArrayEquals(GeneratedInput.bytes(15, CHUNK + 17), readAll(back.stream(ref)));
    }

    @Test
    void aLargeSegmentCutInsideItsChunksHoldsNoRecordAndGoes() throws Exception {
        Settings s = in("cut");
        Journal j = open(s);
        JournalRef ref = spill(j, "c-1", 16, CHUNK + 17).persist();
        close(j);
        Path segment = segmentFile(s.dir, ref.segment());
        try (FileChannel c = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            c.truncate(chunkAt(ref, CHUNK + 17, 1));   // the trailer and the last chunk gone
        }

        Journal back = open(s);

        assertFalse(Files.exists(segment), "no whole record: the segment goes");
        IOException e = assertThrows(IOException.class, () -> back.stream(ref));
        assertFalse(e instanceof JournalCorruptException, "no record there is not corruption: " + e);
    }

    @Test
    void aLargeHeaderClaimingAnImpossibleSizeIsNoRecordToTheScan() throws Exception {
        Settings s = in("huge");
        Journal j = open(s);
        JournalRef ref = spill(j, "h-1", 17, CHUNK + 17).persist();
        close(j);
        Path segment = segmentFile(s.dir, ref.segment());
        editHeader(segment, ref, h -> h.putLong(14, Long.MAX_VALUE));
        flipLastByte(segment);

        Journal back = open(s);   // the start does not trip over the size

        assertFalse(Files.exists(segment), "no whole record: the segment goes");
        assertTrue(appendForced(back, record("h-2", 100)).segment() > ref.segment());
    }

    /** Edits a large record's header in place (CORE-3 item 2 offsets) and makes its CRC32C, over the header alone, right again. */
    private static void editHeader(Path segment, JournalRef ref, java.util.function.Consumer<ByteBuffer> edit) throws IOException {
        byte[] all = Files.readAllBytes(segment);
        int at = (int) ref.position();
        ByteBuffer h = ByteBuffer.wrap(all, at, all.length - at).slice();
        int headerLength = h.getInt(4);
        edit.accept(h);
        CRC32C crc = new CRC32C();
        crc.update(all, at + 4, headerLength - 4);
        h.putInt(0, (int) crc.getValue());
        Files.write(segment, all);
    }

    private static void flipLastByte(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        all[all.length - 1] ^= 0x5A;   // the trailer's magic
        Files.write(file, all);
    }

    @Test
    void recordMaxStopsWhereItsLongestRecordStillFitsAJavaArray() {
        // Worked from CORE-3 item 2: the longest header is 46 + 2 × 65 535 = 131 116 bytes and a Java array at most
        // 2^31 − 1 − 8 = 2 147 483 639 bytes; 2 147 319 755 plain bytes take 2 048 chunks, so 32 768 bytes of tags:
        // 131 116 + 2 147 319 755 + 32 768 = 2 147 483 639. One plain byte more and that record is read into no array.
        long bound = 2_147_319_755L;
        assertEquals(bound, settings(tmp).recordMax(bound).segmentBytes(1L << 32).pendingBytes(1L << 32).config().recordMax());
        IllegalArgumentException above = assertThrows(IllegalArgumentException.class,
                () -> settings(tmp).recordMax(bound + 1).segmentBytes(1L << 32).pendingBytes(1L << 32).config());
        assertTrue(above.getMessage().startsWith("FLOTTILA_JOURNAL_RECORD_MAX must lie in 4096.." + bound), above.getMessage());
    }
}
