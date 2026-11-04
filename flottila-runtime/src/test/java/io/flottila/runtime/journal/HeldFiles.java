package io.flottila.runtime.journal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The test double of {@link JournalFiles} (the second implementation that justifies the seam): real {@link FileChannel}s
 * underneath, every write, force and truncate recorded as an {@link Op} on one global sequence (entered, returned), and
 * {@link Hold}s that stop the calling thread inside a write or a force until the test releases it or makes it fail.
 *
 * <p>A record is found by a <em>marker</em>, its message id: CORE-3 item 2 puts the message id in the record header in
 * plain (AAD, not encrypted). So "the write that carries record X" is the first write whose bytes contain X, and "n's
 * seal" is a later write on n's file that is the first write of none of n's records.
 */
final class HeldFiles implements JournalFiles {

    enum Kind { WRITE, FORCE, TRUNCATE }

    /** One call on a channel: entered and returned on the global sequence, the calling thread, the bytes of a write. */
    static final class Op {
        final Kind kind;
        final Path file;
        final Thread thread;
        final byte[] data;
        final long entered;
        volatile boolean ok;
        volatile long returned = -1;

        Op(Kind kind, Path file, Thread thread, byte[] data, long entered) {
            this.kind = kind;
            this.file = file;
            this.thread = thread;
            this.data = data;
            this.entered = entered;
        }

        boolean carries(String marker) { return data != null && indexOf(data, bytes(marker), 0) >= 0; }

        boolean returnedOk() { return returned >= 0 && ok; }

        @Override public String toString() {
            return "#" + entered + "-" + (returned < 0 ? "…" : returned + (ok ? "" : " FAILED")) + " " + kind + " " + file.getFileName()
                    + (data == null ? "" : " " + data.length + " bytes") + " on " + thread.getName() + (thread.isVirtual() ? " (virtual)" : " (platform)");
        }
    }

    /**
     * A held call: armed by the test, consumed by the first matching call (removed from the armed list before it signals
     * {@link #reached}), which then waits until {@link #release()} (the call goes on) or {@link #fail(IOException)} (the
     * call throws, nothing is written or forced). Failing before the call arrives makes it throw at once.
     */
    final class Hold {
        private final String what;
        private final Predicate<Op> matches;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch go = new CountDownLatch(1);
        private volatile IOException failure;
        private volatile Op op;

        Hold(String what, Predicate<Op> matches) { this.what = what; this.matches = matches; }

        void release() { go.countDown(); }

        void fail(IOException e) { failure = e; go.countDown(); }

        boolean isReached() { return reached.getCount() == 0; }

        /** Waits (bounded) until the call arrived; the failure names what was armed and every op seen so far. */
        void awaitReached() throws InterruptedException {
            if (!reached.await(JournalFixture.BOUND.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("the held " + what + " was not reached within " + JournalFixture.BOUND + "; ops: " + describe());
            }
        }

        /** The held call (after {@link #awaitReached()}). */
        Op op() {
            if (op == null) throw new AssertionError("the held " + what + " was not reached");
            return op;
        }

        Thread thread() { return op().thread; }

        Path file() { return op().file; }

        private void stop(Op arrived) throws IOException {
            op = arrived;
            reached.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException x = new InterruptedIOException("interrupted inside the held " + what);
                x.initCause(e);
                throw x;
            }
            IOException f = failure;
            if (f != null) throw f;
        }

        @Override public String toString() { return what; }
    }

    private final AtomicLong sequence = new AtomicLong();
    private final List<Op> ops = new CopyOnWriteArrayList<>();
    private final List<Hold> armed = new ArrayList<>();          // guarded by this
    private final List<Hold> holds = new CopyOnWriteArrayList<>();
    private final Set<Path> opened = ConcurrentHashMap.newKeySet();

    @Override public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
        Path at = file.toAbsolutePath().normalize();
        opened.add(at);   // before the file can exist: a crash image never copies a file it does not know about yet
        return new HeldChannel(at, FileChannel.open(file, options));
    }

    /** Holds the first write (any file) whose bytes carry every marker. */
    Hold holdWrite(String... markers) {
        return arm("write carrying " + Arrays.toString(markers), op -> {
            if (op.kind != Kind.WRITE) return false;
            for (String m : markers) if (!op.carries(m)) return false;
            return true;
        });
    }

    /**
     * Holds the sealer's first write on segment n: the first write on the file of {@code recordsOfN[0]}, entered after that
     * record's write returned, that writes none of {@code recordsOfN} for the first time — so not a record write, whether
     * the footer goes out in one write or entry by entry, index first or trailer first.
     */
    Hold holdSealWriteOf(String... recordsOfN) {
        return arm("seal write of n " + Arrays.toString(recordsOfN), op -> {
            if (op.kind != Kind.WRITE) return false;
            Op first = firstWrite(recordsOfN[0]);
            if (first == null || first == op || !first.returnedOk() || !first.file.equals(op.file) || op.entered < first.returned) return false;
            for (String m : recordsOfN) if (firstWrite(m) == op) return false;
            return true;
        });
    }

    /** Holds the first force on the file that carries {@code marker}, entered after that write returned. */
    Hold holdForceAfter(String marker) {
        return arm("force after " + marker, op -> {
            if (op.kind != Kind.FORCE) return false;
            Op w = firstWrite(marker);
            return w != null && w.returnedOk() && w.file.equals(op.file) && op.entered > w.returned;
        });
    }

    private synchronized Hold arm(String what, Predicate<Op> matches) {
        Hold h = new Hold(what, matches);
        armed.add(h);
        holds.add(h);
        return h;
    }

    private synchronized Hold consume(Op op) {
        for (Iterator<Hold> it = armed.iterator(); it.hasNext(); ) {
            Hold h = it.next();
            if (h.matches.test(op)) {
                it.remove();
                return h;
            }
        }
        return null;
    }

    /** Lets every hold go (an {@code @AfterEach} runs this before closing a journal). */
    void releaseAll() {
        synchronized (this) { armed.clear(); }
        holds.forEach(Hold::release);
    }

    /** The current point of the global sequence: every op entered later has a larger {@link Op#entered}. */
    long now() { return sequence.get(); }

    List<Op> ops() { return List.copyOf(ops); }

    /** The first write that carries {@code marker}, or null. */
    Op firstWrite(String marker) {
        for (Op op : ops) if (op.kind == Kind.WRITE && op.carries(marker)) return op;
        return null;
    }

    /** The file of the first write carrying {@code marker} (the record's segment file). */
    Path fileOf(String marker) {
        Op w = firstWrite(marker);
        if (w == null) throw new AssertionError("no write carried " + marker + "; ops: " + describe());
        return w.file;
    }

    /** Forces entered on {@code file}, held and failed ones included. */
    List<Op> forcesOn(Path file) {
        return ops.stream().filter(op -> op.kind == Kind.FORCE && op.file.equals(file)).toList();
    }

    int forces(Path file) { return forcesOn(file).size(); }

    /** Ops on {@code file} entered after {@code seq}. */
    List<Op> opsOnAfter(Path file, long seq) {
        return ops.stream().filter(op -> op.file.equals(file) && op.entered > seq).toList();
    }

    /** True once a force on the marker's file, entered after the marker's write returned, has returned successfully. */
    boolean forcedPast(String marker) {
        Op w = firstWrite(marker);
        if (w == null || !w.returnedOk()) return false;
        for (Op f : ops) if (f.kind == Kind.FORCE && f.file.equals(w.file) && f.entered > w.returned && f.returnedOk()) return true;
        return false;
    }

    /** {@link #forcedPast} by the thread that wrote the marker (the writer's own force, not a sealer's). */
    boolean forcedByItsWriter(String marker) {
        Op w = firstWrite(marker);
        if (w == null || !w.returnedOk()) return false;
        for (Op f : ops) {
            if (f.kind == Kind.FORCE && f.file.equals(w.file) && f.thread == w.thread && f.entered > w.returned && f.returnedOk()) return true;
        }
        return false;
    }

    /** Files opened through the seam whose every force so far failed or that were never forced (a pre-open in flight). */
    Set<Path> neverForced() {
        Set<Path> forced = ops.stream().filter(op -> op.kind == Kind.FORCE && op.returnedOk()).map(op -> op.file).collect(Collectors.toSet());
        return opened.stream().filter(p -> !forced.contains(p)).collect(Collectors.toSet());
    }

    String describe() {
        return ops.stream().map(Op::toString).collect(Collectors.joining("\n  ", "\n  ", ""));
    }

    private Op enter(Kind kind, Path file, byte[] data) {
        Op op = new Op(kind, file, Thread.currentThread(), data, sequence.incrementAndGet());
        ops.add(op);
        return op;
    }

    private void leave(Op op, boolean ok) {
        op.ok = ok;
        op.returned = sequence.incrementAndGet();
    }

    private void maybeHold(Op op) throws IOException {
        Hold h = consume(op);
        if (h != null) h.stop(op);
    }

    static byte[] bytes(String marker) { return marker.getBytes(StandardCharsets.UTF_8); }

    static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] remaining(ByteBuffer[] srcs, int offset, int length) {
        int n = 0;
        for (int i = offset; i < offset + length; i++) n += srcs[i].remaining();
        byte[] all = new byte[n];
        int at = 0;
        for (int i = offset; i < offset + length; i++) {
            ByteBuffer d = srcs[i].duplicate();
            int r = d.remaining();
            d.get(all, at, r);
            at += r;
        }
        return all;
    }

    /** A delegating channel that records and may hold its writes and forces. */
    private final class HeldChannel extends FileChannel {
        private final Path file;
        private final FileChannel inner;

        HeldChannel(Path file, FileChannel inner) { this.file = file; this.inner = inner; }

        private interface Call<T> { T run() throws IOException; }

        private <T> T recorded(Kind kind, byte[] data, Call<T> call) throws IOException {
            Op op = enter(kind, file, data);
            boolean ok = false;
            try {
                maybeHold(op);
                T result = call.run();
                ok = true;
                return result;
            } finally {
                leave(op, ok);
            }
        }

        @Override public int write(ByteBuffer src) throws IOException {
            return recorded(Kind.WRITE, remaining(new ByteBuffer[] {src}, 0, 1), () -> inner.write(src));
        }

        @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return recorded(Kind.WRITE, remaining(srcs, offset, length), () -> inner.write(srcs, offset, length));
        }

        @Override public int write(ByteBuffer src, long position) throws IOException {
            return recorded(Kind.WRITE, remaining(new ByteBuffer[] {src}, 0, 1), () -> inner.write(src, position));
        }

        @Override public void force(boolean metaData) throws IOException {
            recorded(Kind.FORCE, null, () -> {
                inner.force(metaData);
                return null;
            });
        }

        @Override public FileChannel truncate(long size) throws IOException {
            recorded(Kind.TRUNCATE, null, () -> inner.truncate(size));
            return this;
        }

        @Override public int read(ByteBuffer dst) throws IOException { return inner.read(dst); }

        @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return inner.read(dsts, offset, length); }

        @Override public int read(ByteBuffer dst, long position) throws IOException { return inner.read(dst, position); }

        @Override public long position() throws IOException { return inner.position(); }

        @Override public FileChannel position(long newPosition) throws IOException {
            inner.position(newPosition);
            return this;
        }

        @Override public long size() throws IOException { return inner.size(); }

        @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return inner.transferTo(position, count, target);
        }

        @Override public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return recorded(Kind.WRITE, null, () -> inner.transferFrom(src, position, count));
        }

        @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            throw new IOException("mmap is not part of the journal design (CORE-3 'Rejected'): " + file);
        }

        @Override public FileLock lock(long position, long size, boolean shared) throws IOException { return inner.lock(position, size, shared); }

        @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return inner.tryLock(position, size, shared); }

        @Override protected void implCloseChannel() throws IOException { inner.close(); }
    }
}
