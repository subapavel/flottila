package io.flottila.runtime.journal;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.CRC32C;

/** Whole reads and writes on a channel, the directory force, CRC32C, and the cleanup of what a failing create opened. */
final class JournalIo {

    private JournalIo() { }

    /**
     * Writes every remaining byte of {@code buffers} at the channel's position: a gathering write takes at most IOV_MAX
     * buffers (1 024 on Linux) per call, so it loops past the buffers each call drained.
     */
    static void writeFully(FileChannel channel, ByteBuffer... buffers) throws IOException {
        int first = 0;
        while (first < buffers.length) {
            channel.write(buffers, first, buffers.length - first);
            while (first < buffers.length && !buffers[first].hasRemaining()) first++;
        }
    }

    /** Reads {@code length} bytes from {@code position} into {@code into}; a file that ends first is an {@link EOFException}. */
    static void read(FileChannel channel, long position, byte[] into, int length) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(into, 0, length);
        while (b.hasRemaining()) {
            if (channel.read(b, position + b.position()) < 0) {
                throw new EOFException("the file ends at " + (position + b.position()) + ", before " + (position + length));
            }
        }
    }

    static byte[] read(FileChannel channel, long position, int length) throws IOException {
        byte[] b = new byte[length];
        read(channel, position, b, length);
        return b;
    }

    /** Forces a directory, so the entries created, moved or removed in it survive a crash. */
    static void forceDir(JournalFiles files, Path dir) throws IOException {
        try (FileChannel d = files.open(dir, Set.of(StandardOpenOption.READ))) {
            d.force(true);
        }
    }

    /** Unlinks {@code file} after {@code failure}; an unlink that fails rides on it as suppressed. */
    static void unlinkAfter(Path file, Throwable failure) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException x) {
            failure.addSuppressed(x);
        }
    }

    static int crc(byte[] b, int offset, int length) {
        CRC32C c = new CRC32C();
        c.update(b, offset, length);
        return (int) c.getValue();
    }

    /** Closes every one, the last first; a close that fails rides on the first failure as suppressed (try-with-resources). */
    static void closeAll(Iterator<? extends Closeable> them) throws IOException {
        if (!them.hasNext()) return;
        try (Closeable c = them.next()) {
            closeAll(them);
        }
    }

    /**
     * A channel owned by a try-with-resources until {@link #handOn()}: what a create opened is closed when the create fails
     * half-way, with a close failure suppressed on the create's own by the try.
     */
    static final class Owned implements Closeable {
        private FileChannel channel;

        Owned(FileChannel channel) { this.channel = channel; }

        FileChannel get() { return channel; }

        FileChannel handOn() {
            FileChannel c = channel;
            channel = null;
            return c;
        }

        @Override public void close() throws IOException {
            if (channel != null) channel.close();
        }
    }
}
