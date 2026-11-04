package io.flottila.runtime.journal;

import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import javax.crypto.SecretKey;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;

/**
 * The body journal of one node (CORE-3 items 1–3, 8, 9 file part; D-105): segment files {@code segments/<n>.seg} of
 * encrypted records under {@code FLOTTILA_JOURNAL_DIR}, held by a {@code LOCK} and named by a {@code NODE} file.
 *
 * <p><b>Append.</b> The caller's thread encrypts and CRCs its record, then reserves (segment, position) and enqueues it
 * in one critical section, so queue order is position order; the ref is known at once. One long-lived writer virtual
 * thread drains a round (at most {@value #ROUND_RECORDS} records and 4 MiB), writes it with one gathering write per
 * segment it touches and forces each of those channels once; a round's futures complete only after every force of the
 * round returned, and rounds run one after another, so a future completes only once every earlier position is forced.
 * Each future completes on a fresh virtual thread of its own: a listener that blocks delays no other. The window is the
 * previous force; no timer runs a commit.
 *
 * <p><b>Bound.</b> When the encoded bytes reserved and not yet forced (the round being forced included) leave no room
 * under {@code FLOTTILA_JOURNAL_PENDING_BYTES}, an append waits until room frees, its save is abandoned or
 * {@code FLOTTILA_JOURNAL_APPEND_WAIT} passes on the injected timer, then fails {@link JournalBusyException} with nothing
 * reserved. An append that finds nothing pending is always taken.
 *
 * <p><b>Segments.</b> A segment rolls before the record that would take it past {@code FLOTTILA_JOURNAL_SEGMENT_BYTES},
 * once it holds {@code FLOTTILA_JOURNAL_SEGMENT_RECORDS}, and {@code FLOTTILA_JOURNAL_SEGMENT_AGE} after its first record
 * (the injected timer, and the next append once the clock passed it); an empty segment never rolls. The next segment is
 * pre-opened off the hot path (file, header, force, directory force on a virtual thread). Segment n goes to a sealer
 * virtual thread only after the writer's last write and force on n returned, and the writer never touches n again; the
 * sealer writes n's footer index and trailer and forces it; only then does {@link #index(long)} answer for n. No
 * acknowledged record waits for a footer. A close seals the open segment.
 *
 * <p><b>Fail-stop.</b> Any write or force error fails the journal: the channel it failed on is never forced again, the
 * round's appends, every queued and every later append fail {@link JournalFailedException}, one ERROR is logged; reads go
 * on; only a restart's scan recovers the journal.
 *
 * <p><b>Read.</b> A record is read by its ref with a pread on a channel of its own, checked by CRC32C (a failure is
 * {@link JournalCorruptException} and an ERROR, never "not found"), then decrypted under its tenant key.
 *
 * @since 0.1
 */
public final class Journal implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Journal.class.getName());
    /** A round drains at most this many records … */
    static final int ROUND_RECORDS = 4096;
    /** … and at most this many bytes; its first record always. */
    static final long ROUND_BYTES = 4L << 20;

    /**
     * An append taken: {@code ref} is where the record lies; {@code forced} completes once this record and every earlier
     * position are forced, or fails {@link JournalFailedException}.
     *
     * @param ref    the record's ref
     * @param forced completes when the record is durable
     * @since 0.1
     */
    public record Appended(JournalRef ref, Future<Void> forced) { }

    /** A sealed segment's footer index: its length after the records, its CRC32C and its entry count. */
    record Index(int length, int crc, int count) { }

    /** What a read may reach in a segment: the records below {@code end}; the footer index once sealed with a sound one. */
    static final class Extent {
        volatile long end;
        volatile Index index;

        Extent(long end) { this.end = end; }

        static Extent sealed(long end, Index index) {
            Extent e = new Extent(end);
            e.index = index;
            return e;
        }
    }

    private sealed interface Item permits Rec, End { }

    /** A record reserved: its index entry (with its ref), its bytes and the promise of its force. */
    private record Rec(JournalIndexEntry entry, ByteBuffer bytes, Promise<Void> promise) implements Item { }

    /** Segment {@code n} takes no more records. */
    private record End(long n) implements Item { }

    /** The reservation side of the segment taking records. Guarded by {@link #lock}. */
    private static final class Open {
        final long n;
        long end;
        int records;
        Instant first;

        Open(long n, long end) { this.n = n; this.end = end; }
    }

    /** A segment file the writer writes (then hands to a sealer). Owned by one thread at a time. */
    private static final class SegmentFile {
        final long n;
        final FileChannel channel;
        final Extent extent;
        long end;
        final List<JournalIndexEntry> entries = new ArrayList<>();
        private final List<ByteBuffer> round = new ArrayList<>();
        private long roundBytes;

        SegmentFile(long n, FileChannel channel, Extent extent) {
            this.n = n;
            this.channel = channel;
            this.extent = extent;
            this.end = extent.end;
        }

        void add(Rec r) {
            round.add(r.bytes());
            roundBytes += r.entry().ref().length();
            entries.add(r.entry());
        }

        void write() throws IOException {
            JournalIo.writeFully(channel, round.toArray(ByteBuffer[]::new));
            end += roundBytes;
            round.clear();
            roundBytes = 0;
        }

        void force() throws IOException {
            channel.force(false);
            extent.end = end;
        }
    }

    /** A pre-open's outcome: the segment file, or why it could not be made. */
    private record Opened(SegmentFile file, Throwable failure) { }

    private final JournalConfig config;
    private final Clock clock;
    private final Timer timer;
    private final IntFunction<SecretKey> keys;
    private final JournalFiles files;
    private final SegmentFormat format;
    private final Path segments;
    private final FileChannel lockChannel;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Extent> extents = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition work = lock.newCondition();
    private final Condition room = lock.newCondition();
    private final Condition done = lock.newCondition();
    // guarded by lock
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private Open current;
    private long pending;
    private Throwable failure;
    private volatile boolean closing;
    private boolean writerDone;
    private int sealing;

    // writer-owned (handed over by Thread.start, read by close after the writer is done)
    private SegmentFile writing;
    private CompletableFuture<Opened> next;
    private final List<SegmentFile> held = new ArrayList<>();

    private Journal(JournalConfig config, Clock clock, Timer timer, IntFunction<SecretKey> keys, JournalFiles files,
                    SegmentFormat format, Path segments, FileChannel lockChannel, Map<Long, Extent> recovered, SegmentFile first) {
        this.config = config;
        this.clock = clock;
        this.timer = timer;
        this.keys = keys;
        this.files = files;
        this.format = format;
        this.segments = segments;
        this.lockChannel = lockChannel;
        extents.putAll(recovered);
        extents.put(first.n, first.extent);
        current = new Open(first.n, format.headerLength());
        writing = first;
        held.add(first);
        next = preopen(first.n + 1);
        Thread.ofVirtual().name("journal-writer-" + config.node()).start(this::runWriter);
    }

    /**
     * Opens the journal in {@code config.dir()}: takes the {@code LOCK} (held elsewhere → refused), reads or writes the
     * {@code NODE} identity, recovers every segment (no tenant key is asked for) and starts a new segment after the
     * highest number seen.
     *
     * @param keys the tenant key of a key id, or null when there is none
     */
    public static Journal open(JournalConfig config, Clock clock, Timer timer, IntFunction<SecretKey> keys, JournalFiles files)
            throws IOException {
        Path dir = config.dir();
        Files.createDirectories(dir);
        try (JournalIo.Owned lockChannel = new JournalIo.Owned(files.open(dir.resolve("LOCK"),
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)))) {
            takeLock(lockChannel.get(), dir);
            SegmentFormat format = new SegmentFormat(identity(dir, config.node(), files), config.node());
            Path segments = dir.resolve("segments");
            if (!Files.isDirectory(segments)) {
                Files.createDirectories(segments);
                JournalIo.forceDir(files, dir);
            }
            Recovery.Result recovered = Recovery.recover(segments, format, files, clock);
            SegmentFile first = create(recovered.next(), files, segments, format, clock);
            return new Journal(config, clock, timer, keys, files, format, segments, lockChannel.handOn(), recovered.extents(), first);
        }
    }

    private static void takeLock(FileChannel channel, Path dir) throws IOException {
        FileLock held;
        try {
            held = channel.tryLock();
        } catch (OverlappingFileLockException inThisProcess) {
            held = null;
        }
        if (held == null) throw new IllegalStateException("the journal in " + dir + " is open elsewhere (its LOCK is held): refused");
    }

    /** The journal id of {@code NODE}; a first start writes it (tmp, force, atomic move, directory force). */
    private static UUID identity(Path dir, String node, JournalFiles files) throws IOException {
        Path file = dir.resolve("NODE");
        if (Files.exists(file)) {
            byte[] found = Files.readAllBytes(file);
            UUID id = SegmentFormat.journalOf(found);
            if (id == null || !Arrays.equals(found, new SegmentFormat(id, node).identity())) {
                throw new IllegalStateException("the NODE file in " + dir + " is not the journal identity of node " + node
                        + " (another node's, or damaged): refused");
            }
            return id;
        }
        UUID id = UUID.randomUUID();
        Path tmp = dir.resolve("NODE.tmp");
        try (FileChannel ch = files.open(tmp, Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
            JournalIo.writeFully(ch, ByteBuffer.wrap(new SegmentFormat(id, node).identity()));
            ch.force(false);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        JournalIo.forceDir(files, dir);
        return id;
    }

    /** Creates segment {@code n}: the file, its header, a force, a directory force. A failure part-way closes the channel. */
    private static SegmentFile create(long n, JournalFiles files, Path segments, SegmentFormat format, Clock clock) throws IOException {
        try (JournalIo.Owned ch = new JournalIo.Owned(files.open(segments.resolve(n + ".seg"),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
            JournalIo.writeFully(ch.get(), format.header(n, clock.millis()));
            ch.get().force(false);
            JournalIo.forceDir(files, segments);
            return new SegmentFile(n, ch.handOn(), new Extent(format.headerLength()));
        }
    }

    // ---------------------------------------------------------------------------------------------------- append

    /**
     * Appends {@code record}: encrypts it on this thread, reserves its position and returns at once with its ref; the
     * returned {@code forced} completes when it is durable. Waits for room under the pending bound (see the class
     * comment) until {@code abandoned} completes or the append wait passes.
     *
     * @throws IllegalArgumentException a body above {@code FLOTTILA_JOURNAL_RECORD_MAX}, a name above 65 535 bytes, or a
     *                                  tenant key that is not there or cannot encrypt
     * @throws JournalBusyException     no room before the wait ended (or the waiting thread was interrupted); nothing reserved
     * @throws JournalFailedException   the journal failed; nothing reserved
     * @throws IllegalStateException    the journal is closed
     */
    public Appended append(JournalRecord record, Future<?> abandoned) {
        Objects.requireNonNull(abandoned, "abandoned");
        ByteBuffer bytes = encode(record);
        int length = bytes.remaining();
        Room wait = null;
        lock.lock();
        try {
            while (true) {
                if (failure != null) throw failed();
                if (closing) throw closed();
                if (pending == 0 || pending + length <= config.pendingBytes()) return reserve(record, bytes);
                if (wait == null) wait = new Room(abandoned);
                if (wait.over()) {
                    throw new JournalBusyException("no room for " + length + " bytes under FLOTTILA_JOURNAL_PENDING_BYTES " + config.pendingBytes()
                            + " (" + pending + " pending): " + (wait.abandoned ? "its save was abandoned" : "FLOTTILA_JOURNAL_APPEND_WAIT "
                            + config.appendWait() + " passed"), null);
                }
                room.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JournalBusyException("interrupted while waiting for room under FLOTTILA_JOURNAL_PENDING_BYTES", e);
        } finally {
            lock.unlock();
            if (wait != null) wait.deadline.cancel();
        }
    }

    private ByteBuffer encode(JournalRecord record) {
        if (record.body().length > config.recordMax()) {
            throw new IllegalArgumentException("a body of " + record.body().length + " bytes is above FLOTTILA_JOURNAL_RECORD_MAX "
                    + config.recordMax() + " (a large body is a segment of its own: CORE-4b)");
        }
        SecretKey key = keys.apply(record.tenantKeyId());
        if (key == null) throw new IllegalArgumentException("no tenant key " + record.tenantKeyId());
        byte[] salt = new byte[ChunkedAead.SALT_BYTES];
        random.nextBytes(salt);
        try {
            return RecordFormat.encode(record, key, salt);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("tenant key " + record.tenantKeyId() + " cannot encrypt a record", e);
        }
    }

    /** Takes the next position; rolls before when the segment is full by bytes or age, after when full by count. */
    private Appended reserve(JournalRecord record, ByteBuffer bytes) {
        int length = bytes.remaining();
        Instant now = clock.instant();
        if (current.records > 0 && (current.end + length > config.segmentBytes() || !now.isBefore(current.first.plus(config.segmentAge())))) {
            roll();
        }
        Open seg = current;
        // The age timer is armed before seg changes: a timer that throws fails this append and leaves seg as it was.
        if (seg.records == 0) timer.schedule(config.segmentAge(), () -> ageRoll(seg));
        JournalRef ref = new JournalRef(seg.n, seg.end, length);
        seg.end += length;
        if (seg.records++ == 0) seg.first = now;
        Promise<Void> promise = Promise.promise();
        queue.add(new Rec(new JournalIndexEntry(record.messageId(), record.step(), record.part(), ref), bytes, promise));
        pending += length;
        if (seg.records == config.segmentRecords()) roll();
        work.signal();
        return new Appended(ref, promise.future());
    }

    /** Ends the segment taking records; the next one takes the following record. Under the lock. */
    private void roll() {
        queue.add(new End(current.n));
        current = new Open(current.n + 1, format.headerLength());
        work.signal();
    }

    /** The age timer of {@code seg}: rolls it if it still takes records (a roll by bytes or count may have come first). */
    private void ageRoll(Open seg) {
        lock.lock();
        try {
            if (current == seg) roll();
        } finally {
            lock.unlock();
        }
    }

    /** An append's wait for room: it ends when its save is abandoned or the append wait passes. */
    private final class Room {
        boolean abandoned;   // guarded by lock
        boolean late;        // guarded by lock
        final Timer.Cancellable deadline;

        Room(Future<?> save) {
            deadline = timer.schedule(config.appendWait(), () -> end(false));
            save.onComplete(ar -> end(true));
        }

        boolean over() { return abandoned || late; }

        void end(boolean byAbandon) {
            lock.lock();
            try {
                if (byAbandon) abandoned = true;
                else late = true;
                room.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private JournalFailedException failed() {
        return new JournalFailedException("the journal in " + config.dir() + " failed and stopped (fail-stop): " + failure, failure);
    }

    private static IllegalStateException closed() {
        return new IllegalStateException("the journal is closed");
    }

    // ---------------------------------------------------------------------------------------------------- writer

    private void runWriter() {
        try {
            for (List<Item> round; (round = take()) != null; ) {
                try {
                    write(round);
                    completed(round);
                } catch (IOException | RuntimeException | Error e) {
                    fail(e, round);
                    return;
                }
            }
        } finally {
            lock.lock();
            try {
                writerDone = true;
                done.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * The next round, or null once the journal failed (a sealer's failure is seen at the next wake-up: the writer has
     * nothing in flight then), or is closing with nothing left.
     */
    private List<Item> take() {
        lock.lock();
        try {
            while (queue.isEmpty() && !closing) work.awaitUninterruptibly();
            if (failure != null || queue.isEmpty()) return null;
            List<Item> round = new ArrayList<>();
            int records = 0;
            long bytes = 0;
            for (Item item; (item = queue.peek()) != null; ) {
                if (item instanceof Rec r) {
                    int length = r.entry().ref().length();
                    if (records > 0 && (records == ROUND_RECORDS || bytes + length > ROUND_BYTES)) break;
                    records++;
                    bytes += length;
                }
                round.add(queue.poll());
            }
            return round;
        } finally {
            lock.unlock();
        }
    }

    /** One gathering write per segment the round touches, then one force each, then the ended segments go to sealers. */
    private void write(List<Item> round) throws IOException {
        List<SegmentFile> wrote = new ArrayList<>(2);
        List<SegmentFile> ended = new ArrayList<>(1);
        for (Item item : round) {
            if (item instanceof Rec r) {
                if (writing == null) writing = moveOn();
                if (wrote.isEmpty() || wrote.getLast() != writing) wrote.add(writing);
                writing.add(r);
            } else {
                ended.add(writing);
                writing = null;
            }
        }
        for (SegmentFile f : wrote) f.write();
        for (SegmentFile f : wrote) f.force();
        for (SegmentFile f : ended) seal(f);
    }

    /** The pre-opened next segment (waited for), and the pre-open of the one after it. */
    private SegmentFile moveOn() throws IOException {
        Opened o = next.join();
        if (o.failure() != null) throw new IOException("the pre-open of the next journal segment failed", o.failure());
        held.add(o.file());
        next = preopen(o.file().n + 1);
        return o.file();
    }

    private CompletableFuture<Opened> preopen(long n) {
        CompletableFuture<Opened> f = new CompletableFuture<>();
        Thread.ofVirtual().name("journal-open-" + n).start(() -> {
            try {
                SegmentFile file = create(n, files, segments, format, clock);
                extents.put(n, file.extent);
                f.complete(new Opened(file, null));
            } catch (IOException | RuntimeException | Error e) {
                f.complete(new Opened(null, e));
            }
        });
        return f;
    }

    /** Hands {@code f} to a sealer virtual thread: the writer's last write and force on it have returned. */
    private void seal(SegmentFile f) {
        Thread sealer = Thread.ofVirtual().name("journal-sealer-" + f.n).unstarted(() -> {
            try (FileChannel ch = f.channel) {
                SegmentFormat.Footer footer = SegmentFormat.footer(f.entries, f.end, clock.millis());
                JournalIo.writeFully(ch, footer.bytes());
                ch.force(false);
                f.extent.index = new Index(footer.indexLength(), footer.indexCrc(), f.entries.size());
            } catch (IOException | RuntimeException | Error e) {
                fail(e, List.of());
            } finally {
                sealed();
            }
        });
        lock.lock();
        try {
            sealing++;
        } finally {
            lock.unlock();
        }
        try {
            sealer.start();
        } catch (RuntimeException | Error e) {
            // No test reaches this: a virtual thread's start fails only when its scheduler refuses it. A sealer that never
            // ran never counts itself out, so the count is undone here (else close() would wait for it forever); f stays
            // held, so close() closes its channel; the journal fails as on a sealer's own failure.
            sealed();
            fail(e, List.of());
            return;
        }
        held.remove(f);
    }

    /** A sealer is done (or never started): close() may stop waiting for it. */
    private void sealed() {
        lock.lock();
        try {
            sealing--;
            done.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** The round is forced: its bytes leave the bound, then each future completes on a fresh virtual thread. */
    private void completed(List<Item> round) {
        List<Promise<Void>> promises = new ArrayList<>(round.size());
        long bytes = 0;
        for (Item item : round) {
            if (item instanceof Rec r) {
                bytes += r.entry().ref().length();
                promises.add(r.promise());
            }
        }
        lock.lock();
        try {
            pending -= bytes;
            room.signalAll();
        } finally {
            lock.unlock();
        }
        for (Promise<Void> p : promises) Thread.ofVirtual().name("journal-forced").start(p::tryComplete);
    }

    /**
     * Fail-stop: the first failure is kept and logged once; the round's records and every queued one fail, each on a fresh
     * virtual thread; waiting appends wake to fail; the writer stops at its next round; no channel is forced again.
     */
    private void fail(Throwable cause, List<Item> round) {
        List<Promise<Void>> doomed = new ArrayList<>();
        boolean first;
        JournalFailedException e;
        lock.lock();
        try {
            first = failure == null;
            if (first) failure = cause;
            e = failed();
            for (Item item : round) if (item instanceof Rec r) doomed.add(r.promise());
            for (Item item : queue) if (item instanceof Rec r) doomed.add(r.promise());
            queue.clear();
            room.signalAll();
        } finally {
            lock.unlock();
        }
        if (first) {
            LOG.log(System.Logger.Level.ERROR, "journal " + config.dir() + " failed and stopped (fail-stop): no write or force is"
                    + " retried, every append fails until a restart recovers it; reads go on", cause);
        }
        for (Promise<Void> p : doomed) Thread.ofVirtual().name("journal-failed").start(() -> p.tryFail(e));
    }

    // ---------------------------------------------------------------------------------------------------- read

    /**
     * The plaintext of the record at {@code ref}: read with a pread, checked by CRC32C, decrypted under its tenant key.
     *
     * @throws JournalCorruptException the bytes fail their CRC32C, are not a v1 record of that length, or fail
     *                                 authentication (logged at ERROR)
     * @throws JournalKeyException     the record's tenant key is not there or cannot be used
     * @throws IOException             no record can lie at {@code ref} (a segment this journal does not hold, or past the
     *                                 last record written and forced), or the read failed
     */
    public byte[] read(JournalRef ref) throws IOException {
        if (closing) throw closed();
        Extent e = extents.get(ref.segment());
        long end = e == null ? format.headerLength() : e.end;
        if (ref.length() < RecordFormat.MIN_LENGTH || ref.position() < format.headerLength() || ref.position() > end - ref.length()) {
            throw new IOException("no journal record can lie at " + ref + ": segment " + ref.segment() + " holds records up to " + end);
        }
        byte[] rec = new byte[ref.length()];
        try (FileChannel ch = files.open(segmentFile(ref.segment()), Set.of(StandardOpenOption.READ))) {
            JournalIo.read(ch, ref.position(), rec, rec.length);
        }
        if (!RecordFormat.crcHolds(rec)) throw corrupt(ref, "fails its CRC32C", null);
        RecordFormat.Header h = RecordFormat.header(rec, rec.length, rec.length);
        if (h == null) throw corrupt(ref, "is not a v1 journal record of " + rec.length + " bytes", null);
        SecretKey key = keys.apply(h.keyId());
        if (key == null) throw new JournalKeyException("the tenant key " + h.keyId() + " of the journal record at " + ref + " is not there", null);
        SecretKey recordKey;
        try {
            recordKey = RecordFormat.recordKey(key, h.salt(), h.keyId());
        } catch (GeneralSecurityException x) {
            throw new JournalKeyException("the tenant key " + h.keyId() + " of the journal record at " + ref + " cannot be used", x);
        }
        try {
            return RecordFormat.decrypt(rec, h, recordKey);
        } catch (GeneralSecurityException x) {
            throw corrupt(ref, "fails authentication", x);
        }
    }

    /**
     * The footer index of segment {@code n} once it is sealed (its footer forced), or empty: a segment still open, one this
     * journal does not hold, or one whose index failed its CRC32C at start.
     *
     * @throws JournalCorruptException the index no longer holds the CRC32C it was sealed or found with
     */
    public Optional<List<JournalIndexEntry>> index(long n) throws IOException {
        if (closing) throw closed();
        Extent e = extents.get(n);
        Index idx = e == null ? null : e.index;
        if (idx == null) return Optional.empty();
        byte[] bytes;
        try (FileChannel ch = files.open(segmentFile(n), Set.of(StandardOpenOption.READ))) {
            bytes = JournalIo.read(ch, e.end, idx.length());
        }
        if (JournalIo.crc(bytes, 0, bytes.length) != idx.crc()) {
            throw corrupt(new JournalRef(n, e.end, idx.length()), "(the footer index of segment " + n + ") fails its CRC32C", null);
        }
        return Optional.of(SegmentFormat.entries(bytes, n, idx.count()));
    }

    private Path segmentFile(long n) {
        return segments.resolve(n + ".seg");
    }

    private static JournalCorruptException corrupt(JournalRef ref, String what, Throwable cause) {
        JournalCorruptException e = new JournalCorruptException("the journal record at " + ref + " " + what, cause);
        LOG.log(System.Logger.Level.ERROR, e.getMessage(), e);
        return e;
    }

    // ---------------------------------------------------------------------------------------------------- close

    /**
     * Stops taking appends, lets the writer write and force every append already taken, seals the open segment, waits for
     * every sealer, then closes the channels and gives the {@code LOCK} up. A failed journal closes too, forcing nothing.
     * A second close finds nothing to write, seal or close.
     */
    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            closing = true;
            if (current.records > 0) roll();
            work.signalAll();
            room.signalAll();
            while (!writerDone || sealing > 0) done.awaitUninterruptibly();
        } finally {
            lock.unlock();
        }
        List<Closeable> open = new ArrayList<>();
        open.add(lockChannel);
        Opened o = next.join();
        if (o.file() != null) open.add(o.file().channel);
        for (SegmentFile f : held) open.add(f.channel);
        JournalIo.closeAll(open.iterator());
    }
}
