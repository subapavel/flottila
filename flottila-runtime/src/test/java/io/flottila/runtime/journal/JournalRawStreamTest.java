package io.flottila.runtime.journal;

import io.flottila.api.Hasher;
import io.flottila.api.BlobKey;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record's bytes read from a stream instead of this journal's segment (CORE-5a S7, implementer): {@link Journal#raw} gives a
 * record's bytes as they lie, {@link Journal#stream(JournalRef, BlobKey, InputStream)} checks and decodes them. A stream that
 * ends before the record does is corrupt — a small record at once, a large one at the chunk it cuts — and the stream is closed
 * on every failure, a failure of its close kept beside the first.
 */
class JournalRawStreamTest {

    static final BlobKey KEY = BlobKey.body("m-1", 0);
    static final BlobKey LARGE = BlobKey.body("m-large", 0);
    static final long LARGE_SIZE = (32L << 20) + 3;

    @TempDir Path tmp;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal journal;
    byte[] body;
    JournalRef ref;
    JournalRef large;

    @BeforeEach
    void open() throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve("j"))).segmentAge(Duration.ofHours(1));
        JournalConfig config = s.config();
        journal = OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
        body = JournalFixture.body("m-1", 3_000);
        ref = OnVirtualThread.call(() -> {
            Journal.Appended appended = journal.append(new JournalRecord("m-1", 0, BlobKey.BODY, JournalFixture.KEY, body), Promise.promise().future());
            appended.forced().await(20, TimeUnit.SECONDS);
            return appended.ref();
        });
        large = OnVirtualThread.call(() -> journal.spill(LARGE, JournalFixture.KEY, new GeneratedInput(62, LARGE_SIZE), LARGE_SIZE, Hasher.sha256()).persist());
    }

    @AfterEach
    void close() throws Exception {
        journal.close();
    }

    /** {@code n} bytes of the record at {@code at} as it lies, from {@link Journal#raw}. */
    byte[] rawBytes(JournalRef at, long n) throws IOException {
        try (InputStream raw = journal.raw(at)) {
            return raw.readNBytes((int) n);
        }
    }

    /** A stream over {@code bytes} that notes its close and, when {@code failing}, fails it. */
    static final class Watched extends FilterInputStream {
        final boolean failing;
        boolean closed;

        Watched(InputStream in, boolean failing) {
            super(in);
            this.failing = failing;
        }

        @Override public void close() throws IOException {
            closed = true;
            super.close();
            if (failing) throw new IOException("fixture: the close fails");
        }
    }

    @Test
    void aSmallRecordsRawBytesReadBackAsItsBody() throws Exception {
        Watched raw = new Watched(new ByteArrayInputStream(rawBytes(ref, ref.length())), false);
        try (InputStream in = journal.stream(ref, KEY, raw)) {
            assertArrayEquals(body, in.readAllBytes());
        }
        assertTrue(raw.closed, "the raw stream is closed");
    }

    @Test
    void aSmallRecordCutShortIsCorruptAndItsStreamClosed() throws Exception {
        Watched raw = new Watched(new ByteArrayInputStream(rawBytes(ref, ref.length() - 1)), false);
        assertThrows(JournalCorruptException.class, () -> journal.stream(ref, KEY, raw));
        assertTrue(raw.closed, "closed on the failure");
    }

    @Test
    void aFailingCloseIsKeptBesideTheFailure() throws Exception {
        Watched raw = new Watched(new ByteArrayInputStream(rawBytes(ref, ref.length() - 1)), true);
        JournalCorruptException e = assertThrows(JournalCorruptException.class, () -> journal.stream(ref, KEY, raw));
        assertTrue(raw.closed, "closed on the failure");
        assertEquals(1, java.util.Arrays.stream(e.getSuppressed()).filter(x -> "fixture: the close fails".equals(x.getMessage())).count(),
                "the close's failure is suppressed on the first: " + java.util.Arrays.toString(e.getSuppressed()));
    }

    @Test
    void aLargeRecordsRawBytesReadBackChunkByChunk() throws Exception {
        MessageDigest md = GeneratedInput.digest();
        long got;
        try (InputStream raw = journal.raw(large); InputStream in = journal.stream(large, LARGE, raw)) {
            got = in.transferTo(new java.security.DigestOutputStream(OutputStream.nullOutputStream(), md));
        }
        assertEquals(GeneratedInput.expected(62, LARGE_SIZE), new GeneratedInput.Digest(got, GeneratedInput.hashOf(md.digest())));
    }

    @Test
    void aLargeRecordCutShortFailsItsReadAsCorrupt() throws Exception {
        try (InputStream raw = new java.io.SequenceInputStream(InputStream.nullInputStream(), journal.raw(large)) {
                    private long left = large.length() - 1;

                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        if (left == 0) return -1;
                        int n = super.read(b, off, (int) Math.min(len, left));
                        if (n > 0) left -= n;
                        return n;
                    }
                };
             InputStream in = journal.stream(large, LARGE, raw)) {
            IOException e = assertThrows(IOException.class, () -> in.transferTo(OutputStream.nullOutputStream()));
            assertInstanceOf(JournalCorruptException.class, e, String.valueOf(e));
        }
    }
}
