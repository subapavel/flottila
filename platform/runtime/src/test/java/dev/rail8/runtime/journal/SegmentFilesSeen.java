package dev.rail8.runtime.journal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Delegates to {@code delegate} and notes every segment file the journal creates ({@code CREATE_NEW} of a {@code <n>.seg}) and
 * every force of the directory it lies in (a read-open of the parent directory, as {@code JournalIo.forceDir} does it);
 * only a file in a directory named {@code segments} is a segment file (a spill file in {@code spill/} is named {@code <n>.seg} too).
 *
 * <p>Why: the journal counts the header of the pre-opened next segment, and inserts its row, from the moment the pre-open
 * starts, on a {@code journal-open-N} virtual thread, before the file exists on disk. A test that compares the journal's count
 * or its rows with the disk has to wait until no pre-open is in flight. {@link #awaitSegment} does it by identity: the file of
 * the pre-open it names was created, and every segment file seen created has had its directory forced since (the pre-open's
 * last step), not by call order and not by tempo. The wait is bounded and fails with what it waited for.
 */
final class SegmentFilesSeen implements JournalFiles {

    private record Created(long n, Path file) { }

    private final JournalFiles delegate;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final List<Created> unforced = new ArrayList<>();   // created, no force of the directory since
    private final List<Long> created = new ArrayList<>();        // every segment number seen created, in the order seen
    private long first = -1;

    SegmentFilesSeen(JournalFiles delegate) {
        this.delegate = delegate;
    }

    @Override public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
        boolean create = options.contains(StandardOpenOption.CREATE_NEW) && file.getFileName().toString().endsWith(".seg")
                && file.getParent() != null && file.getParent().getFileName().toString().equals("segments");
        boolean dirRead = options.equals(Set.of(StandardOpenOption.READ));
        // a force of the directory opens it after the files it makes durable were created: the files noted before this call
        List<Created> before = dirRead ? snapshot() : List.of();
        FileChannel ch = delegate.open(file, options);
        lock.lock();
        try {
            if (create) {
                long n = Long.parseLong(file.getFileName().toString().replace(".seg", ""));
                unforced.add(new Created(n, file));
                created.add(n);
                if (first < 0) first = n;
                changed.signalAll();
            } else if (dirRead) {
                int sizeBefore = unforced.size();
                unforced.removeIf(c -> before.contains(c) && file.equals(c.file().getParent()));
                if (unforced.size() != sizeBefore) changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
        return ch;
    }

    private List<Created> snapshot() {
        lock.lock();
        try {
            return List.copyOf(unforced);
        } finally {
            lock.unlock();
        }
    }

    @Override public FileStore fileStore(Path dir) throws IOException {
        return delegate.fileStore(dir);
    }

    /** The number of the first segment file created (the start's own: it exists when {@code Journal.open} returned). */
    long first() {
        lock.lock();
        try {
            if (first < 0) throw new AssertionError("no segment file was created yet");
            return first;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until the file of segment {@code n} was created and every segment file created has had its directory forced
     * since: no pre-open that began before is still on its way to its file.
     *
     * @throws AssertionError after {@link JournalFixture#BOUND}, naming the segments seen created and those not yet forced
     */
    void awaitSegment(long n) throws InterruptedException {
        long left = JournalFixture.BOUND.toNanos();
        lock.lock();
        try {
            while (!created.contains(n) || !unforced.isEmpty()) {
                if (left <= 0) {
                    throw new AssertionError("waited " + JournalFixture.BOUND + " for the pre-open of segment " + n + " to create its file and"
                            + " for its directory force; segments created: " + created + ", created and not forced since: "
                            + unforced.stream().map(Created::n).toList());
                }
                left = changed.awaitNanos(left);
            }
        } finally {
            lock.unlock();
        }
    }
}
