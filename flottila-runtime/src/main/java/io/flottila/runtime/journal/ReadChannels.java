package io.flottila.runtime.journal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The read channels of a journal (CORE-3 item 8): one per segment, shared by every pread of it, at most {@code max} kept
 * open ({@code FLOTTILA_JOURNAL_READ_CHANNELS}). A read that needs one more closes the least recently used first; a channel
 * still read when it is put out is closed by its last read. A read that fails drops its channel (closed once no read uses
 * it), so the next read of that segment opens a fresh one. {@link #close()} closes every one; a read afterwards is refused.
 */
final class ReadChannels implements Closeable {

    /** One segment's channel and the reads using it now. */
    private static final class Entry {
        final long segment;
        final FileChannel channel;
        int users;          // guarded by lock
        boolean dropped;    // guarded by lock: out of the set, closed by its last user

        Entry(long segment, FileChannel channel) {
            this.segment = segment;
            this.channel = channel;
        }
    }

    /** A read's use of an entry: given back when the read is over, failed or not. */
    private final class Lease implements Closeable {
        final Entry entry;
        boolean read;

        Lease(Entry entry) { this.entry = entry; }

        @Override public void close() throws IOException { release(entry, read); }
    }

    private final JournalFiles files;
    private final int max;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<Long, Entry> open = new LinkedHashMap<>(16, 0.75f, true);   // guarded by lock; least recently used first
    private boolean closed;   // guarded by lock

    ReadChannels(JournalFiles files, int max) {
        this.files = files;
        this.max = max;
    }

    /**
     * {@code length} bytes at {@code position} of segment {@code segment}'s {@code file}, read on its shared channel.
     *
     * @throws IllegalStateException the channels were closed
     * @throws IOException           the open or the read failed (the channel is dropped), or the file ends first
     */
    byte[] pread(long segment, Path file, long position, int length) throws IOException {
        try (Lease lease = acquire(segment, file)) {
            byte[] b = JournalIo.read(lease.entry.channel, position, length);
            lease.read = true;
            return b;
        }
    }

    private Lease acquire(long segment, Path file) throws IOException {
        lock.lock();
        try {
            if (closed) throw new IllegalStateException("the journal is closed");
            Entry e = open.get(segment);
            if (e == null) {
                while (open.size() >= max) {
                    Iterator<Entry> eldest = open.values().iterator();
                    Entry out = eldest.next();
                    eldest.remove();
                    out.dropped = true;
                    if (out.users == 0) out.channel.close();
                }
                e = new Entry(segment, files.open(file, Set.of(StandardOpenOption.READ)));
                open.put(segment, e);
            }
            e.users++;
            return new Lease(e);
        } finally {
            lock.unlock();
        }
    }

    private void release(Entry e, boolean read) throws IOException {
        lock.lock();
        try {
            e.users--;
            if (!read) {   // the channel failed: no later read takes it
                open.remove(e.segment, e);
                e.dropped = true;
            }
            if (e.dropped && e.users == 0) e.channel.close();
        } finally {
            lock.unlock();
        }
    }

    /** Closes every channel no read uses now (one in use is closed by its last read); every later read is refused. */
    @Override
    public void close() throws IOException {
        List<FileChannel> idle = new ArrayList<>();
        lock.lock();
        try {
            closed = true;
            for (Entry e : open.values()) {
                e.dropped = true;
                if (e.users == 0) idle.add(e.channel);
            }
            open.clear();
        } finally {
            lock.unlock();
        }
        JournalIo.closeAll(idle.iterator());
    }
}
