package io.flottila.runtime.journal;

import io.flottila.runtime.journal.JournalFixture.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flottila.runtime.journal.JournalFixture.onVirtual;
import static io.flottila.runtime.journal.JournalFixture.until;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The read channels' concurrency (CORE-4c3 item 6; the implementer's): a channel put out of the set while reads still use it
 * is closed by its last read, never under a read in flight; a close with a read in flight closes that channel when the read
 * is over; a read after the close is refused. The seam {@link GatedFiles} holds every read of one file at a gate.
 */
class ReadChannelsTest {

    @TempDir Path tmp;

    /** Real channels; a read of {@link #gated} waits at the gate, counted on arrival; closes counted per file. */
    static final class GatedFiles implements JournalFiles {
        volatile Path gated;
        final Semaphore gate = new Semaphore(0);
        final AtomicInteger arrived = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final Map<Path, AtomicInteger> closes = new ConcurrentHashMap<>();

        @Override public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
            return new Gated(file, FileChannel.open(file, options));
        }

        int closesOf(Path file) {
            AtomicInteger n = closes.get(file);
            return n == null ? 0 : n.get();
        }

        private final class Gated extends FileChannel {
            private final Path file;
            private final FileChannel inner;

            Gated(Path file, FileChannel inner) {
                this.file = file;
                this.inner = inner;
            }

            @Override public int read(ByteBuffer dst, long position) throws IOException {
                if (file.equals(gated)) {
                    arrived.incrementAndGet();
                    gate.acquireUninterruptibly();
                }
                int n = inner.read(dst, position);
                if (file.equals(gated)) done.incrementAndGet();
                return n;
            }

            @Override public int read(ByteBuffer dst) throws IOException { return inner.read(dst); }
            @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return inner.read(dsts, offset, length); }
            @Override public int write(ByteBuffer src) throws IOException { return inner.write(src); }
            @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { return inner.write(srcs, offset, length); }
            @Override public int write(ByteBuffer src, long position) throws IOException { return inner.write(src, position); }
            @Override public long position() throws IOException { return inner.position(); }
            @Override public FileChannel position(long p) throws IOException { inner.position(p); return this; }
            @Override public long size() throws IOException { return inner.size(); }
            @Override public FileChannel truncate(long size) throws IOException { inner.truncate(size); return this; }
            @Override public void force(boolean metaData) throws IOException { inner.force(metaData); }
            @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { return inner.transferTo(position, count, target); }
            @Override public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException { return inner.transferFrom(src, position, count); }
            @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return inner.map(mode, position, size); }
            @Override public FileLock lock(long position, long size, boolean shared) throws IOException { return inner.lock(position, size, shared); }
            @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return inner.tryLock(position, size, shared); }

            @Override protected void implCloseChannel() throws IOException {
                closes.computeIfAbsent(file, f -> new AtomicInteger()).incrementAndGet();
                inner.close();
            }
        }
    }

    private Path file(String name, String text) throws IOException {
        return Files.writeString(tmp.resolve(name), text, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /**
     * READ_CHANNELS 1: two reads of segment 1 wait inside their pread; a read of segment 2 puts segment 1's channel out. It is
     * not closed under them: the first read to end leaves it open for the other, the last one closes it, once; both read.
     */
    @Test
    void aChannelPutOutWhileReadIsClosedByItsLastRead() throws Exception {
        Path a = file("1.seg", "aaaa-segment-one"), b = file("2.seg", "bbbb-segment-two");
        GatedFiles files = new GatedFiles();
        files.gated = a;
        ReadChannels channels = new ReadChannels(files, 1);
        Outcome<byte[]> r1 = onVirtual(() -> channels.pread(1, a, 0, 4));
        Outcome<byte[]> r2 = onVirtual(() -> channels.pread(1, a, 5, 7));
        until(() -> files.arrived.get() == 2, () -> "both reads of segment 1 wait in their pread: " + files.arrived);

        assertArrayEquals(bytes("bbbb"), channels.pread(2, b, 0, 4), "a read of segment 2 takes the one place");
        assertEquals(0, files.closesOf(a), "segment 1's channel is out of the set, but two reads use it: not closed");

        files.gate.release();
        until(() -> files.done.get() == 1, () -> "one read of segment 1 is over");
        until(() -> r1.done() || r2.done(), () -> "its pread returned");
        assertEquals(0, files.closesOf(a), "the other read still uses it: not closed");

        files.gate.release();
        assertArrayEquals(bytes("aaaa"), r1.join(), "the first read reads");
        assertArrayEquals(bytes("segment"), r2.join(), "and the second, on the channel the first left open");
        assertEquals(1, files.closesOf(a), "the last read closed it, once");
        assertEquals(0, files.closesOf(b), "segment 2's channel stays in the set");
        channels.close();
        assertEquals(1, files.closesOf(b), "the close closes it");
    }

    /** A close while a read is in flight closes that channel only when the read is over; every read after the close is refused. */
    @Test
    void aCloseLeavesAChannelInUseToItsReadAndRefusesLaterReads() throws Exception {
        Path a = file("1.seg", "aaaa-segment-one"), b = file("2.seg", "bbbb-segment-two");
        GatedFiles files = new GatedFiles();
        ReadChannels channels = new ReadChannels(files, 4);
        assertArrayEquals(bytes("bbbb"), channels.pread(2, b, 0, 4), "fixture: an idle channel of segment 2");
        files.gated = a;
        Outcome<byte[]> reading = onVirtual(() -> channels.pread(1, a, 0, 4));
        until(() -> files.arrived.get() == 1, () -> "the read of segment 1 waits in its pread");

        channels.close();

        assertEquals(1, files.closesOf(b), "the idle channel is closed at once");
        assertEquals(0, files.closesOf(a), "the one in use is not closed under its read");
        files.gate.release();
        assertArrayEquals(bytes("aaaa"), reading.join(), "the read in flight reads");
        assertEquals(1, files.closesOf(a), "and its end closes the channel");
        assertThrows(IllegalStateException.class, () -> channels.pread(2, b, 0, 4), "a read after the close is refused");
        assertEquals(1, files.closesOf(b), "and opens nothing");
    }
}
