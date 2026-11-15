package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.flottila.runtime.journal.HeldFiles.Hold;
import io.flottila.runtime.journal.JournalFixture.Outcome;
import io.flottila.runtime.journal.JournalFixture.Settings;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.AEADBadTagException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.flottila.runtime.journal.JournalFixture.KEY;
import static io.flottila.runtime.journal.JournalFixture.START;
import static io.flottila.runtime.journal.JournalFixture.await;
import static io.flottila.runtime.journal.JournalFixture.body;
import static io.flottila.runtime.journal.JournalFixture.keys;
import static io.flottila.runtime.journal.JournalFixture.live;
import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.record;
import static io.flottila.runtime.journal.JournalFixture.settings;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large bodies in journal format (CORE-3 item 4, CORE-4b packet Scope 1–3): a body above {@code FLOTTILA_JOURNAL_RECORD_MAX}
 * is spilled from its first byte as a one-record segment of its own under {@code <journal>/spill/}, streamed in 1 MiB AEAD
 * chunks and hashed once while streaming; it reads back from the spill any number of times; persist writes its footer and
 * trailer, forces it, inserts its segment row, moves it into {@code segments/} and forces that directory; a failed insert or
 * move leaves no segment file and can be retried; abandon unlinks it.
 *
 * <p>The API these cases compile against (proposed by the test-author, CORE-4b handback):
 * {@code Journal.open(config, clock, timer, keys, files, SegmentRows rows)};
 * {@code Journal.Spill Journal.spill(BlobKey key, int tenantKeyId, InputStream body, long maxSize, Hasher hasher)} (reads
 * {@code body} to its end on the calling thread; a body above {@code maxSize} fails {@link BodyTooLargeException});
 * {@code Spill}: {@code long segment()}, {@code long size()}, {@code Hash hash()}, {@code InputStream open()},
 * {@code JournalRef persist()}, {@code void abandon()}; {@code InputStream Journal.stream(JournalRef)};
 * {@code SegmentRows}: {@code Future<Void> insert(String node, long segment)}; {@code JournalRef.length()} a {@code long}.
 *
 * <p>Where a case reaches into a segment file it uses the documented format only (CORE-3 items 1 and 2): the trailer's
 * last 32 bytes (count u32 at 8, sealed ms u64 at 12, magic FJSE at 28), and the AEAD chunks as the record's last bytes,
 * 1 MiB of plaintext plus a 16-byte tag each.
 */
class LargeBodyTest {

    static final long RECORD_MAX = 4096;
    /** Three chunks: 1 MiB, 1 MiB and 17 bytes. */
    static final int LARGE = (2 << 20) + 17;
    static final int CHUNK = 1 << 20;
    static final int TAG = 16;
    static final int TRAILER = 32;
    static final int END_MAGIC = 0x464A5345;   // "FJSE"
    static final String NODE = "node-1";        // JournalFixture.Settings' node

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
        return settings(Files.createDirectories(tmp.resolve(name))).recordMax(RECORD_MAX).segmentAge(Duration.ofHours(1));
    }

    private HeldFiles seam() {
        HeldFiles files = new HeldFiles();
        seams.add(files);
        return files;
    }

    private Journal open(Settings s, JournalFiles files, SegmentRows rows) throws IOException {
        Journal j = Journal.open(s.config(), clock, timer, keys(KEY), files, rows);
        journals.add(j);
        return j;
    }

    private void close(Journal j) throws IOException {
        journals.remove(j);
        j.close();
    }

    private static JournalRef appendForced(Journal j, JournalRecord r) throws Exception {
        Journal.Appended a = j.append(r, live());
        await(a.forced());
        return a.ref();
    }

    private static Journal.Spill spill(Journal j, String id, long seed, long size) throws IOException {
        return j.spill(BlobKey.body(id, 0), KEY, new GeneratedInput(seed, size), size, Hasher.sha256());
    }

    static Path spillDir(Path journalDir) { return journalDir.resolve("spill"); }

    static Path segmentFile(Path journalDir, long n) { return journalDir.resolve("segments").resolve(n + ".seg"); }

    /** The regular files directly under {@code dir}, none when it does not exist. */
    static List<Path> regularFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> l = Files.list(dir)) {
            return l.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** The one regular file under {@code dir}; anything else fails naming what is there. */
    static Path onlyFile(Path dir) throws IOException {
        List<Path> files = regularFiles(dir);
        assertEquals(1, files.size(), "exactly one file under " + dir + ": " + files);
        return files.get(0);
    }

    static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    private static Optional<List<JournalIndexEntry>> indexOf(Journal j, long n) {
        try {
            return j.index(n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The footer index of segment {@code n} once its sealer has forced it (bounded spin). */
    private static List<JournalIndexEntry> sealedIndex(Journal j, long n) {
        until(() -> indexOf(j, n).isPresent(), () -> "segment " + n + " is sealed");
        return indexOf(j, n).orElseThrow();
    }

    /** Where chunk {@code i} of a body of {@code size} bytes starts in its segment file: counted back from the record's end. */
    static long chunkAt(JournalRef ref, long size, int i) {
        long chunks = Math.max(1, (size + CHUNK - 1) / CHUNK);
        return ref.position() + ref.length() - (size + chunks * TAG) + (long) i * (CHUNK + TAG);
    }

    private static boolean causedBy(Throwable t, Predicate<Throwable> p) {
        int depth = 0;
        for (Throwable c = t; c != null && depth++ < 32; c = c.getCause()) if (p.test(c)) return true;
        return false;
    }

    /** What a stream gave before it ended, and the failure it ended with (null: it ended normally). */
    record Drained(byte[] bytes, IOException failure) { }

    @FunctionalInterface
    interface Opener {
        InputStream open() throws IOException;
    }

    /** Reads what {@code open} opens to its end, keeping every byte it gave before a failure (a failing open gives none). */
    static Drained drain(Opener open) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = open.open()) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
        } catch (IOException e) {
            return new Drained(out.toByteArray(), e);
        }
        return new Drained(out.toByteArray(), null);
    }

    /** {@code d} failed as corruption or by an AEAD tag, never ended normally, and gave no byte that is not the body's. */
    private static void assertFailsAuthentication(Drained d, byte[] body, String what) {
        assertNotNull(d.failure(), what + ": the read ended normally after " + d.bytes().length + " of " + body.length + " bytes instead of failing");
        assertTrue(d.failure() instanceof JournalCorruptException || causedBy(d.failure(), c -> c instanceof AEADBadTagException),
                what + ": fails as JournalCorruptException or by an AEAD tag, not " + d.failure());
        assertTrue(d.bytes().length < body.length && Arrays.equals(d.bytes(), Arrays.copyOf(body, d.bytes().length)),
                what + ": no wrong byte before the failure (" + d.bytes().length + " bytes given)");
    }

    /** {@link Hasher#sha256()} counting its streaming sessions, the bytes they were given and every one-shot hash. */
    static final class CountingHasher implements Hasher {
        private final Hasher inner = Hasher.sha256();
        final AtomicInteger begins = new AtomicInteger();
        final AtomicLong updated = new AtomicLong();
        final AtomicInteger oneShots = new AtomicInteger();

        @Override public String algorithm() { return inner.algorithm(); }

        @Override public long hash(byte[] data) {
            oneShots.incrementAndGet();
            return inner.hash(data);
        }

        @Override public Session begin() {
            begins.incrementAndGet();
            Session s = inner.begin();
            return new Session() {
                @Override public void update(Buffer chunk) {
                    updated.addAndGet(chunk.length());
                    s.update(chunk);
                }

                @Override public Hash finish() { return s.finish(); }
            };
        }
    }

    @Test
    void aBodyAboveRecordMaxIsItsOwnSegmentHashedOnce() throws Exception {
        HeldFiles files = seam();
        RecordingRows rows = new RecordingRows();
        Settings s = in("own");
        Journal j = open(s, files, rows);
        JournalRef small = appendForced(j, record("own-0", 100));
        GeneratedInput input = new GeneratedInput(1, LARGE);
        CountingHasher hasher = new CountingHasher();

        Journal.Spill spill = j.spill(new BlobKey("own-1", 3, "body"), KEY, input, LARGE, hasher);

        assertEquals(GeneratedInput.expected(1, LARGE).hash(), spill.hash(), "the hash is SHA-256 over the input, computed independently here");
        assertEquals(LARGE, spill.size());
        assertEquals(LARGE, input.delivered(), "the input was read to its end once (it cannot be reset)");
        assertEquals(1, hasher.begins.get(), "one streaming hash");
        assertEquals(LARGE, hasher.updated.get(), "every byte hashed once");
        assertEquals(0, hasher.oneShots.get(), "no one-shot hash beside the stream");
        Path spilled = onlyFile(spillDir(s.dir));
        assertEquals(0, files.readsOn(spilled), "hashed while streaming: the spill was not read back to hash it");
        assertNotEquals(small.segment(), spill.segment(), "a segment of its own, not the writer's");
        assertFalse(Files.exists(segmentFile(s.dir, spill.segment())), "nothing in segments/ before persist");
        assertEquals(List.of(), rows.insertsOf(spill.segment()), "no segment row before persist");

        clock.advance(Duration.ofMillis(1234));
        JournalRef ref = spill.persist();

        assertEquals(spill.segment(), ref.segment());
        assertEquals(Optional.of(List.of(new JournalIndexEntry("own-1", 3, "body", ref))), j.index(ref.segment()),
                "the footer index lists the one record");
        byte[] file = Files.readAllBytes(segmentFile(s.dir, ref.segment()));
        ByteBuffer trailer = ByteBuffer.wrap(file, file.length - TRAILER, TRAILER).slice();
        assertEquals(END_MAGIC, trailer.getInt(28), "the file ends with a trailer (magic FJSE)");
        assertEquals(1, trailer.getInt(8), "the trailer counts one record");
        assertEquals(START.plusMillis(1234).toEpochMilli(), trailer.getLong(12), "sealed ms is the injected clock at persist");
        assertEquals(List.of(), regularFiles(spillDir(s.dir)), "the spill moved into segments/");
        assertArrayEquals(GeneratedInput.bytes(1, LARGE), readAll(j.stream(ref)));
    }

    @Test
    void recordMaxStaysInASegmentAndOneByteMoreIsItsOwnFile() throws Exception {
        Settings s = in("edge").segmentRecords(2);
        Journal j = open(s, JournalFiles.real(), new RecordingRows());
        byte[] atMax = body("edge-max", (int) RECORD_MAX);
        JournalRef max = appendForced(j, new JournalRecord("edge-max", 0, "body", KEY, atMax));
        JournalRef other = appendForced(j, record("edge-other", 10));
        assertEquals(max.segment(), other.segment(), "a body of exactly FLOTTILA_JOURNAL_RECORD_MAX is a record of the writer's segment");
        assertEquals(List.of("edge-max", "edge-other"), sealedIndex(j, max.segment()).stream().map(JournalIndexEntry::messageId).toList(),
                "it shares its segment with the next record");
        assertThrows(IllegalArgumentException.class, () -> j.append(record("edge-more", (int) RECORD_MAX + 1), live()),
                "one byte more is no segment record");

        Journal.Spill more = spill(j, "edge-more", 2, RECORD_MAX + 1);
        assertEquals(1, regularFiles(spillDir(s.dir)).size(), "one byte more is a file of its own under spill/");
        JournalRef ref = more.persist();

        assertNotEquals(max.segment(), ref.segment());
        assertEquals(List.of(new JournalIndexEntry("edge-more", 0, "body", ref)), sealedIndex(j, ref.segment()), "alone in its segment");
        assertTrue(Files.isRegularFile(segmentFile(s.dir, ref.segment())));
        assertArrayEquals(atMax, j.read(max));
        assertArrayEquals(GeneratedInput.bytes(2, (int) RECORD_MAX + 1), readAll(j.stream(ref)));
    }

    @Test
    void aTruncatedOrReorderedChunkFailsAuthentication() throws Exception {
        Settings s = in("tamper");
        Journal j = open(s, JournalFiles.real(), new RecordingRows());
        byte[] body = GeneratedInput.bytes(3, LARGE);
        JournalRef cut = spill(j, "tm-cut", 3, LARGE).persist();
        JournalRef swapped = spill(j, "tm-swap", 3, LARGE).persist();
        JournalRef flipped = spill(j, "tm-flip", 3, LARGE).persist();
        JournalRef resized = spill(j, "tm-size", 3, LARGE).persist();
        JournalRef sound = spill(j, "tm-sound", 3, LARGE).persist();

        try (FileChannel c = FileChannel.open(segmentFile(s.dir, cut.segment()), StandardOpenOption.WRITE)) {
            c.truncate(chunkAt(cut, LARGE, 2));   // the file ends where the last chunk began
        }
        swap(segmentFile(s.dir, swapped.segment()), chunkAt(swapped, LARGE, 0), chunkAt(swapped, LARGE, 1), CHUNK + TAG);
        flip(segmentFile(s.dir, flipped.segment()), chunkAt(flipped, LARGE, 1) - 1);   // the last byte of chunk 0's tag
        claimSize(segmentFile(s.dir, resized.segment()), resized, LARGE, 2L << 20);    // as if the body ended after chunk 1

        assertFailsAuthentication(drain(() -> j.stream(cut)), body, "the last chunk dropped");
        assertFailsAuthentication(drain(() -> j.stream(swapped)), body, "chunks 0 and 1 swapped");
        assertFailsAuthentication(drain(() -> j.stream(flipped)), body, "a tag byte flipped");
        assertFailsAuthentication(drain(() -> j.stream(resized)), body, "the plain size edited to end after chunk 1");
        Drained control = drain(() -> j.stream(sound));
        assertNull(control.failure(), "the untouched sibling reads back: " + control.failure());
        assertArrayEquals(body, control.bytes());
    }

    private static void swap(Path file, long a, long b, int length) throws IOException {
        byte[] all = Files.readAllBytes(file);
        byte[] first = Arrays.copyOfRange(all, (int) a, (int) a + length);
        System.arraycopy(all, (int) b, all, (int) a, length);
        System.arraycopy(first, 0, all, (int) b, length);
        Files.write(file, all);
    }

    private static void flip(Path file, long at) throws IOException {
        byte[] all = Files.readAllBytes(file);
        all[(int) at] ^= 0x5A;
        Files.write(file, all);
    }

    /** Rewrites the record header's plain size (u64 big-endian, CORE-3 item 2), found by its value before the chunks. */
    private static void claimSize(Path file, JournalRef ref, long was, long claimed) throws IOException {
        byte[] all = Files.readAllBytes(file);
        byte[] needle = ByteBuffer.allocate(8).putLong(was).array();
        List<Integer> found = new ArrayList<>();
        for (int i = (int) ref.position(); i + 8 <= chunkAt(ref, was, 0); i++) {
            if (Arrays.equals(all, i, i + 8, needle, 0, 8)) found.add(i);
        }
        assertEquals(1, found.size(), "the record header carries the plain size " + was + " once, as u64 big-endian: at " + found);
        ByteBuffer.wrap(all).putLong(found.get(0), claimed);
        Files.write(file, all);
    }

    @Test
    void aSpillReadsBackAnyNumberOfTimesBeforePersist() throws Exception {
        HeldFiles files = seam();
        Settings s = in("reads");
        Journal j = open(s, files, new RecordingRows());
        Journal.Spill spill = spill(j, "rb-1", 4, LARGE);
        byte[] body = GeneratedInput.bytes(4, LARGE);
        Path spilled = onlyFile(spillDir(s.dir));
        long readsBefore = files.readsOn(spilled);

        try (InputStream first = spill.open(); InputStream second = spill.open()) {
            assertArrayEquals(Arrays.copyOf(body, CHUNK + 3), first.readNBytes(CHUNK + 3), "a stream starts at byte 0");
            assertArrayEquals(body, second.readAllBytes(), "a second stream opened beside it starts at byte 0 and reads it all");
            assertArrayEquals(Arrays.copyOfRange(body, CHUNK + 3, LARGE), first.readAllBytes(), "the first goes on where it stopped");
        }
        for (int i = 3; i <= 4; i++) {
            assertArrayEquals(body, readAll(spill.open()), "read " + i);
        }
        assertTrue(files.readsOn(spilled) > readsBefore,
                "the reads went to the spill file through the journal's files seam (the witness the hashed-once case relies on)");

        JournalRef ref = spill.persist();
        assertArrayEquals(body, readAll(j.stream(ref)), "the reads changed nothing: it persists and reads back by its ref");
    }

    @Test
    void persistInsertsTheRowBeforeTheFileAppearsInSegments() throws Exception {
        HeldFiles files = seam();
        RecordingRows rows = new RecordingRows();
        Settings s = in("order");
        Journal j = open(s, files, rows);
        Journal.Spill spill = spill(j, "or-1", 5, LARGE);
        long n = spill.segment();
        Path spilled = onlyFile(spillDir(s.dir));
        Path segment = segmentFile(s.dir, n);
        List<String> atInsert = new CopyOnWriteArrayList<>();
        AtomicReference<Hold> dirForce = new AtomicReference<>();
        rows.onInsert(n, insert -> {
            atInsert.add("in segments/: " + Files.exists(segment));
            atInsert.add("trailer written: " + endsWithTrailer(spilled));
            atInsert.add("forced after its last write: " + files.forcedAfterItsLastWrite(spilled));
            dirForce.set(files.holdForceOn(segment.getParent(), files.now(), insert.thread()));
        });

        Outcome<JournalRef> persist = onVirtual(spill::persist);
        until(() -> dirForce.get() != null || persist.done(), () -> "the segment row is inserted");
        Hold held = dirForce.get();
        assertNotNull(held, "persist inserted the segment row of " + n + ": " + rows.inserts());
        held.awaitReached();
        assertTrue(Files.isRegularFile(segment), "the file was moved into segments/ before the directory force");
        assertFalse(Files.exists(spilled), "moved, so it is no longer under spill/");
        assertFalse(persist.done(), "persist has not returned while the directory force is held");
        held.release();
        JournalRef ref = persist.join();

        assertEquals(List.of("in segments/: false", "trailer written: true", "forced after its last write: true"), atInsert,
                "at the insert the spill was sealed and forced, and segments/ had no such file");
        assertEquals(List.of(NODE), rows.insertsOf(n).stream().map(RecordingRows.Insert::node).toList(), "one row, for this node");
        assertEquals(n, ref.segment());
        assertArrayEquals(GeneratedInput.bytes(5, LARGE), readAll(j.stream(ref)));
    }

    /** True when {@code file} ends with a trailer counting one record (magic FJSE last). */
    private static boolean endsWithTrailer(Path file) {
        try {
            byte[] all = Files.readAllBytes(file);
            if (all.length < TRAILER) return false;
            ByteBuffer t = ByteBuffer.wrap(all, all.length - TRAILER, TRAILER).slice();
            return t.getInt(28) == END_MAGIC && t.getInt(8) == 1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aFailedRowInsertLeavesNoSegmentFileAndPersistFailsRetryable() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("insert-fails");
        Journal j = open(s, JournalFiles.real(), rows);
        Journal.Spill spill = spill(j, "if-1", 6, LARGE);
        long n = spill.segment();
        IllegalStateException refused = new IllegalStateException("the journal_segments insert is refused (test)");
        rows.failNext(n, refused);

        Exception e = assertThrows(Exception.class, spill::persist);
        assertTrue(causedBy(e, c -> c == refused), "persist failed with the insert's failure: " + e);
        assertFalse(Files.exists(segmentFile(s.dir, n)), "no file in segments/ without its row");
        assertEquals(1, regularFiles(spillDir(s.dir)).size(), "the spill stays until abandoned");
        assertArrayEquals(body("if-after", 10), j.read(appendForced(j, record("if-after", 10))), "the journal did not stop");

        JournalRef ref = spill.persist();
        assertEquals(2, rows.insertsOf(n).size(), "the retry inserted the row again");
        assertEquals(n, ref.segment());
        byte[] body = GeneratedInput.bytes(6, LARGE);
        assertArrayEquals(body, readAll(j.stream(ref)));
        close(j);
        Journal back = open(s, JournalFiles.real(), new RecordingRows());
        assertEquals(Optional.of(List.of(new JournalIndexEntry("if-1", 0, "body", ref))), back.index(n), "the retry sealed it once");
        assertArrayEquals(body, readAll(back.stream(ref)), "and it reads back after a restart");
    }

    @Test
    void aFailedMoveLeavesNoSegmentFileAndPersistFailsRetryable() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("move-fails");
        Journal j = open(s, JournalFiles.real(), rows);
        Journal.Spill spill = spill(j, "mf-1", 7, LARGE);
        long n = spill.segment();
        Path segment = segmentFile(s.dir, n);
        Path blocker = segment.resolve("blocker");
        // a non-empty directory where the file would go: rename(2) refuses to replace it (EISDIR), as root too
        rows.onInsert(n, insert -> {
            try {
                Files.createDirectories(segment);
                Files.writeString(blocker, "in the way");
            } catch (IOException x) {
                throw new AssertionError("could not put the blocker in place", x);
            }
        });

        Exception e = assertThrows(Exception.class, spill::persist);
        assertTrue(causedBy(e, c -> c instanceof IOException), "persist failed with the move's failure: " + e);
        assertFalse(Files.isRegularFile(segment), "no segment file: the move failed");
        assertEquals(1, regularFiles(spillDir(s.dir)).size(), "the spill stays until abandoned");
        assertArrayEquals(body("mf-after", 10), j.read(appendForced(j, record("mf-after", 10))), "the journal did not stop");

        Files.delete(blocker);
        Files.delete(segment);
        JournalRef ref = spill.persist();
        assertEquals(n, ref.segment());
        assertTrue(Files.isRegularFile(segment));
        assertArrayEquals(GeneratedInput.bytes(7, LARGE), readAll(j.stream(ref)));
    }

    @Test
    void abandonUnlinksTheSpill() throws Exception {
        RecordingRows rows = new RecordingRows();
        Settings s = in("abandon");
        Journal j = open(s, JournalFiles.real(), rows);
        Journal.Spill spill = spill(j, "ab-1", 8, 100_000);
        Path spilled = onlyFile(spillDir(s.dir));

        spill.abandon();

        assertFalse(Files.exists(spilled), "the spill file is unlinked");
        assertEquals(List.of(), regularFiles(spillDir(s.dir)));
        assertEquals(List.of(), rows.insertsOf(spill.segment()), "an abandoned spill never gets a segment row");
        assertFalse(Files.exists(segmentFile(s.dir, spill.segment())));

        Journal.Spill failed = spill(j, "ab-2", 9, 100_000);
        rows.failNext(failed.segment(), new IllegalStateException("the journal_segments insert is refused (test)"));
        assertThrows(Exception.class, failed::persist);
        assertEquals(1, regularFiles(spillDir(s.dir)).size(), "a failed persist leaves the spill");
        failed.abandon();
        assertEquals(List.of(), regularFiles(spillDir(s.dir)), "abandoning it after a failed persist unlinks it");
        assertFalse(Files.exists(segmentFile(s.dir, failed.segment())));
    }

    @Test
    void aBodyAboveMaxSizeIsRefused() throws Exception {
        Settings s = in("max");
        Journal j = open(s, JournalFiles.real(), new RecordingRows());
        long max = CHUNK + 5;
        GeneratedInput source = new GeneratedInput(10, 32L << 20);
        BlobKey key = BlobKey.body("mx-1", 0);

        BodyTooLargeException e = assertThrows(BodyTooLargeException.class, () -> j.spill(key, KEY, source, max, Hasher.sha256()));

        assertEquals(key, e.key());
        assertEquals(max, e.limit());
        assertTrue(e.size() > max, "the size seen is above the limit: " + e.size());
        assertTrue(source.delivered() <= max + (2L << 20), "refused at the limit, not after draining a 32 MiB source: " + source.delivered());
        assertEquals(List.of(), regularFiles(spillDir(s.dir)), "nothing is left under spill/");

        Journal.Spill atMax = spill(j, "mx-2", 11, max);
        assertEquals(max, atMax.size(), "exactly maxSize is taken");
        assertArrayEquals(GeneratedInput.bytes(11, (int) max), readAll(j.stream(atMax.persist())));

        GeneratedInput untouched = new GeneratedInput(12, 10_000);
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> j.spill(BlobKey.body("mx-3", 0), KEY, untouched, -1, Hasher.sha256()));
        assertTrue(String.valueOf(negative.getMessage()).contains("maxSize"), "the refusal names maxSize: " + negative.getMessage());
        assertEquals(0, untouched.delivered(), "refused before a byte was read");
        assertEquals(List.of(), regularFiles(spillDir(s.dir)));
    }

    @Test
    void aPersistedLargeSegmentReadsBackAfterReopen() throws Exception {
        Settings s = in("reopen");
        Journal first = open(s, JournalFiles.real(), new RecordingRows());
        JournalRef small = appendForced(first, record("ro-0", 100));
        Journal.Spill spill = first.spill(new BlobKey("ro-1", 2, "body"), KEY, new GeneratedInput(13, LARGE), LARGE, Hasher.sha256());
        JournalRef ref = spill.persist();
        close(first);

        HeldFiles files = seam();
        Journal back = open(s, files, new RecordingRows());

        assertArrayEquals(GeneratedInput.bytes(13, LARGE), readAll(back.stream(ref)), "read back streaming after a restart");
        assertEquals(Optional.of(List.of(new JournalIndexEntry("ro-1", 2, "body", ref))), back.index(ref.segment()),
                "recovered as a sealed segment with its footer index");
        assertEquals(List.of(), files.opsOnAfter(segmentFile(s.dir, ref.segment()).toAbsolutePath().normalize(), 0),
                "the start found it sealed: it wrote nothing to it");
        assertArrayEquals(body("ro-0", 100), back.read(small));
        JournalRef after = appendForced(back, record("ro-2", 100));
        assertTrue(after.segment() > ref.segment(), "the next segment comes after the large one: " + after + " after " + ref);
    }

    @Test
    void largeSegmentNumbersNeverCollideWithTheOpenSegment() throws Exception {
        Settings s = in("numbers").segmentRecords(1);   // every append ends its segment: the writer moves on at each one
        Journal j = open(s, JournalFiles.real(), new RecordingRows());
        List<JournalRef> small = new ArrayList<>();
        small.add(appendForced(j, record("nb-1", 100)));
        Journal.Spill first = spill(j, "nb-L1", 14, 100_000);
        JournalRef l1 = first.persist();
        small.add(appendForced(j, record("nb-2", 100)));
        small.add(appendForced(j, record("nb-3", 100)));
        Journal.Spill second = spill(j, "nb-L2", 15, 100_000);
        small.add(appendForced(j, record("nb-4", 100)));   // the writer moves on while the spill waits for its persist
        JournalRef l2 = second.persist();
        small.add(appendForced(j, record("nb-5", 100)));

        List<Long> numbers = new ArrayList<>(small.stream().map(JournalRef::segment).toList());
        numbers.add(l1.segment());
        numbers.add(l2.segment());
        assertEquals(numbers.size(), new HashSet<>(numbers).size(), "no two segments share a number: " + numbers);
        assertReadBack(j, small, l1, l2);
        for (int i = 0; i < small.size(); i++) {
            assertEquals(List.of("nb-" + (i + 1)), sealedIndex(j, small.get(i).segment()).stream().map(JournalIndexEntry::messageId).toList());
        }
        assertEquals(List.of("nb-L1"), sealedIndex(j, l1.segment()).stream().map(JournalIndexEntry::messageId).toList());
        assertEquals(List.of("nb-L2"), sealedIndex(j, l2.segment()).stream().map(JournalIndexEntry::messageId).toList());

        close(j);
        Journal back = open(s, JournalFiles.real(), new RecordingRows());
        assertReadBack(back, small, l1, l2);
        long highest = numbers.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertTrue(appendForced(back, record("nb-6", 100)).segment() > highest, "after a restart the writer starts above every number");
    }

    private static void assertReadBack(Journal j, List<JournalRef> small, JournalRef l1, JournalRef l2) throws IOException {
        for (int i = 0; i < small.size(); i++) {
            assertArrayEquals(body("nb-" + (i + 1), 100), j.read(small.get(i)), "nb-" + (i + 1) + " at " + small.get(i));
        }
        assertArrayEquals(GeneratedInput.bytes(14, 100_000), readAll(j.stream(l1)), "nb-L1 at " + l1);
        assertArrayEquals(GeneratedInput.bytes(15, 100_000), readAll(j.stream(l2)), "nb-L2 at " + l2);
    }

    @Test
    void aRefLengthAboveFourGibibytesIsNeverCutToAnInt() throws Exception {
        Settings s = in("long");
        Journal j = open(s, JournalFiles.real(), new RecordingRows());
        JournalRef small = appendForced(j, record("ln-0", 100));
        JournalRef large = spill(j, "ln-1", 16, 100_000).persist();

        // 2^32 more than the record: cut to an int, the length would be the record's own
        JournalRef longSmall = new JournalRef(small.segment(), small.position(), small.length() + (1L << 32));
        JournalRef longLarge = new JournalRef(large.segment(), large.position(), large.length() + (1L << 32));
        assertEquals(small.length() + (1L << 32), longSmall.length(), "a ref carries a length above 4 GiB as it is");

        assertThrows(IOException.class, () -> j.read(longSmall), "no record of that length lies in the segment");
        assertNotNull(drain(() -> j.stream(longLarge)).failure(), "no large record of that length lies in its segment");
        assertArrayEquals(body("ln-0", 100), j.read(small));
        assertArrayEquals(GeneratedInput.bytes(16, 100_000), readAll(j.stream(large)));
    }
}
