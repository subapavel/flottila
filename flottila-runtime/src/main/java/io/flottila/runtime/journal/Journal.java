package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hash;
import io.flottila.api.Health;
import io.flottila.api.Hasher;
import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
 * <p><b>Journal bound</b> (CORE-3 item 11). The journal's bytes — every segment file (header, records, footer), every spill
 * file and every record reserved and not yet written — are counted against {@code FLOTTILA_JOURNAL_MAX_BYTES} (max). An
 * {@link Bound#INTAKE intake}'s record is taken while the bytes and its record stay at or below max − max/16 and refused one
 * byte above it; a {@link Bound#RESERVE step body} (a park's included) may use the reserve up to max. A refused record is a
 * {@link JournalFullException} (retryable: 503 for an intake), nothing reserved or written; a spill's footer is checked so at
 * its persist (the spill's own bytes counted already). A WARNING is logged when the bytes cross 80 % of max upward, an ERROR
 * when the refusals of a bound start; each again only after the bytes fell below 80 % (the sweeper's unlinks) and crossed or
 * were refused again.
 *
 * <p><b>Segments.</b> A segment rolls before the record that would take it past {@code FLOTTILA_JOURNAL_SEGMENT_BYTES},
 * once it holds {@code FLOTTILA_JOURNAL_SEGMENT_RECORDS}, and {@code FLOTTILA_JOURNAL_SEGMENT_AGE} after its first record
 * (the injected timer, and the next append once the clock passed it); an empty segment never rolls. Every segment has its
 * {@link SegmentRows row} inserted before its file is created. The next segment is pre-opened off the hot path (row, file,
 * header, force, directory force on a virtual thread); a pre-open whose row insert fails tries it again on the injected
 * timer ({@value #FIRST_RETRY_MS} ms doubling to {@value #LAST_RETRY_S} s) while {@link #health()} answers DEGRADED and the
 * writer waits for that segment (appends meanwhile fail retryable at the append wait); a close stops trying. Segment n goes to a sealer
 * virtual thread only after the writer's last write and force on n returned, and the writer never touches n again; the
 * sealer writes n's footer index and trailer and forces it; only then does {@link #index(long)} answer for n. No
 * acknowledged record waits for a footer. A close seals the open segment.
 *
 * <p><b>Fail-stop.</b> Any write or force error of the writer, a sealer or a large body's persist (its footer, or the
 * directory force after its move) fails the journal: the channel it failed on is never forced again, the round's appends,
 * every queued and every later append, spill and persist fail {@link JournalFailedException}, one ERROR is logged and
 * {@link #isFailed()} answers true ({@link #health()} DOWN); reads go on; only a restart's scan recovers the journal. Every
 * force of {@code segments/} (a pre-open's, a persist's) has a generation: a persist whose own directory force returned waits
 * for every one begun before it to end and fails when one of them failed (a later force proves nothing for earlier writes,
 * and one failing while it ran may have dropped its move). A failed segment row insert is no write or force error: it fails no journal, and a close while it is retried
 * stops the writer without a fail-stop ERROR.
 *
 * <p><b>Read.</b> A record is read by its ref with a pread on its segment's read channel — at most
 * {@code FLOTTILA_JOURNAL_READ_CHANNELS} kept open, the least recently used closed first, one whose read failed dropped
 * ({@link ReadChannels}) — checked by CRC32C and, read for a key, against the key its header names (a failure is
 * {@link JournalCorruptException} and an ERROR, never "not found"), then decrypted under its tenant key.
 *
 * <p><b>Large bodies</b> (CORE-3 item 4). A body above {@code FLOTTILA_JOURNAL_RECORD_MAX} is {@link #spill spilled}: written
 * from its first byte, on the caller's thread, as a one-record segment of its own under {@code spill/} (a large record,
 * {@link RecordFormat}), hashed once while it streams. Its number comes from the one sequence the writer's segments take
 * theirs from, so it never names a segment the writer has or will have. It reads back from the spill any number of times;
 * {@link Spill#persist() persist} writes its footer and trailer (sealed ms on the injected clock), forces it, inserts its
 * {@link SegmentRows segment row}, moves it into {@code segments/} (the same inode: no byte is copied) and forces that
 * directory; {@link Spill#abandon() abandon} unlinks it. A start clears {@code spill/}. A persisted large segment is a
 * sealed segment like any other; {@link #stream(JournalRef)} reads it one chunk at a time.
 *
 * @since 0.1
 */
public final class Journal implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Journal.class.getName());
    /** The largest body a {@link #spill} takes: {@code (2^31 - 1) MiB}, the chunk counter's range. */
    public static final long SPILL_MAX = RecordFormat.MAX_PLAIN;
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
        /** The {@link #sealedMs} of a segment not sealed yet. */
        static final long OPEN = Long.MAX_VALUE;
        volatile long end;
        volatile Index index;
        /** The ms it was sealed at (its trailer's; a segment found at start: the start's), or {@link #OPEN}. */
        volatile long sealedMs = OPEN;
        /** The journal bytes its file counts for once sealed (its size at start for one found then): what its unlink gives back. */
        volatile long bytes;

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

    /** Segment {@code n} takes no more records; the segment after the writer's next one is {@code following}. */
    private record End(long n, long following) implements Item { }

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

    /**
     * What a record may use of {@code FLOTTILA_JOURNAL_MAX_BYTES} (CORE-3 item 11).
     *
     * @since 0.1
     */
    public enum Bound {
        /** An intake's record: up to max − max/16. */
        INTAKE,
        /** A step body or a park's body: the reserve, up to max. */
        RESERVE
    }

    /** The force of a directory (a segment file's creation). */
    @FunctionalInterface
    private interface DirForce {
        void run() throws IOException;
    }

    /** A write or force of a spill's persist. */
    @FunctionalInterface
    private interface Forcing<T> {
        T run() throws IOException;
    }

    /** A pre-open's outcome: the segment file, or why it could not be made. */
    private record Opened(SegmentFile file, Throwable failure) { }

    /**
     * A pre-open given up because the journal closed while its row insert was retried (CORE-4c3): the writer stops on it like
     * on any failed pre-open — the records waiting for that segment fail retryable — but no write or force failed, so it logs
     * no fail-stop ERROR.
     */
    private static final class ClosedBeforeOpen extends IllegalStateException {
        ClosedBeforeOpen(Throwable insert) {
            super("the journal closed while the journal_segments row of its next segment was retried", insert);
        }
    }

    /** The rows of a journal without Postgres (CORE-4a's tests): every insert succeeds at once, no row is kept. */
    private static final SegmentRows NO_ROWS = (node, segment) -> Future.succeededFuture();
    /** The journal ids of the opens that are given no id source (tests without a node): a fresh random one. */
    private static final Supplier<UUID> RANDOM_IDS = UUID::randomUUID;
    /** A pre-open's failed row insert is tried again after this, the wait doubling up to {@link #LAST_RETRY}. */
    private static final long FIRST_RETRY_MS = 100;
    private static final long LAST_RETRY_S = 5;
    static final Duration FIRST_RETRY = Duration.ofMillis(FIRST_RETRY_MS);
    static final Duration LAST_RETRY = Duration.ofSeconds(LAST_RETRY_S);

    private final JournalConfig config;
    private final Clock clock;
    private final Timer timer;
    private final IntFunction<SecretKey> keys;
    private final JournalFiles files;
    private final SegmentRows rows;
    private final SegmentFormat format;
    private final Path segments;
    private final Path spills;
    /** The longest record a segment takes: a longer ref is a large body's, read by {@link #stream}. */
    private final long longestRecord;
    private final FileChannel lockChannel;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Extent> extents = new ConcurrentHashMap<>();
    private final Map<Long, Long> unlinking = new ConcurrentHashMap<>();   // swept segments whose file is not unlinked yet: their bytes
    private final ReadChannels channels;
    private final Replicas replicas;
    /** The most journal bytes each {@link Bound} may reach, by ordinal: max − max/16, max. */
    private final long[] limits;
    /** 80 % of max, rounded up: the WARNING's mark. */
    private final long warnAt;

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
    private int inFlight;      // spills and persists running: close() waits for them
    private long nextNumber;   // the next segment number nothing has taken
    private long upcoming;     // the number the next roll gives the writer, taken already
    private Throwable outage;  // the last failure of the pre-open row insert being retried (DEGRADED), else null
    private Timer.Cancellable retry;               // the pending retry of that insert (a fired one cancels to false)
    private CompletableFuture<Opened> retrying;   // the pre-open it completes
    private long bytes;        // the journal's bytes: segment, spill and replica files, reservations (CORE-3 item 11)
    private long replicaBytes; // the replicas' part of them: their files and reservations (CORE-5b1)
    private boolean warned;    // the bytes crossed 80 % and did not fall below since
    private final boolean[] refusing = new boolean[Bound.values().length];   // by ordinal: refusals began, not fallen below 80 % since
    private long dirForces;    // forces of segments/ begun by this journal: the last one's generation
    private final TreeSet<Long> dirForcing = new TreeSet<>();   // the generations of those not ended
    private long dirFailedAt = Long.MAX_VALUE;   // the least generation whose force failed

    // writer-owned (handed over by Thread.start, read by close after the writer is done)
    private SegmentFile writing;
    private CompletableFuture<Opened> next;
    private long afterNext;   // the number of the segment after the pre-opened one, from the last End
    private final List<SegmentFile> held = new ArrayList<>();

    private Journal(JournalConfig config, Clock clock, Timer timer, IntFunction<SecretKey> keys, JournalFiles files, SegmentRows rows,
                    SegmentFormat format, Path dir, FileChannel lockChannel, Map<Long, Extent> recovered, SegmentFile first, long heldBytes,
                    long replicaBytes) {
        this.config = config;
        this.clock = clock;
        this.timer = timer;
        this.keys = keys;
        this.files = files;
        this.rows = rows;
        this.format = format;
        this.segments = dir.resolve("segments");
        this.spills = dir.resolve("spill");
        this.longestRecord = RecordFormat.MAX_HEADER + config.recordMax() + (long) RecordFormat.chunks(config.recordMax()) * ChunkedAead.TAG_BYTES;
        this.lockChannel = lockChannel;
        this.channels = new ReadChannels(files, config.readChannels());
        this.replicas = new Replicas(this, files, dir.resolve("replicas"));
        this.replicaBytes = replicaBytes;
        long max = config.maxBytes();
        this.limits = new long[] {max - max / 16, max};
        this.warnAt = max - max / 5;
        long start = clock.millis();
        for (Extent e : recovered.values()) e.sealedMs = start;   // after a restart the grace counts from the start
        extents.putAll(recovered);
        extents.put(first.n, first.extent);
        current = new Open(first.n, format.headerLength());
        upcoming = first.n + 1;
        nextNumber = first.n + 2;
        writing = first;
        held.add(first);
        grown(heldBytes);
        next = preopen(upcoming);
        Thread.ofVirtual().name("journal-writer-" + config.node()).start(this::runWriter);
    }

    /** A journal without segment rows (no Postgres): {@link #open(JournalConfig, Clock, Timer, IntFunction, JournalFiles, SegmentRows)}. */
    public static Journal open(JournalConfig config, Clock clock, Timer timer, IntFunction<SecretKey> keys, JournalFiles files)
            throws IOException {
        return open(config, clock, timer, keys, files, NO_ROWS);
    }

    /** {@link #open(JournalConfig, Clock, Timer, IntFunction, JournalFiles, SegmentRows, Supplier)} with a random journal id for a first start. */
    public static Journal open(JournalConfig config, Clock clock, Timer timer, IntFunction<SecretKey> keys, JournalFiles files,
                               SegmentRows rows) throws IOException {
        return open(config, clock, timer, keys, files, rows, RANDOM_IDS);
    }

    /**
     * Opens the journal in {@code config.dir()} (CORE-3 item 9). It takes the {@code LOCK} (held elsewhere → refused), then
     * only reads until every check passed: the file system's room for {@code FLOTTILA_JOURNAL_MAX_BYTES}; the identity
     * ({@code NODE} against the node's {@code journal_nodes} row, {@link JournalStart#identity}: a first start writes
     * {@code NODE} with the id {@code journalIds} gives, then the row; a {@code NODE} without its row and no segment file
     * registers it; a journal lost by identity is refused unless its loss is accepted); the segment files with their row (every
     * one when no rows are kept), each one's records read by CRC32C (no tenant key is asked for); and the committed pins into
     * records no longer held — a segment file gone, or past a scanned segment's cut —, refused unless that loss is accepted
     * ({@code FLOTTILA_JOURNAL_ACCEPT_LOSS}, {@link JournalStart}). A refusal changes nothing on disk or in Postgres, with one
     * exception: a first start, or the registration of a {@code NODE} without its row while no segment file is there, has written
     * {@code NODE} and the {@code journal_nodes} row before a pins refusal (the identity check comes first). Then it
     * clears {@code spill/}, removes a crashed generation's {@code MASTER.*.tmp}, unlinks every segment file without its row,
     * cuts and seals what the scan found, drops every row whose file is not there, and starts a new segment, its row first,
     * numbered above every number a file or a row named (a number is never used twice).
     *
     * @param keys       the tenant key of a key id, or null when there is none
     * @param rows       the journal's rows: its identity, its segments, its pins and its accepted losses
     * @param journalIds the journal id of a first start, or of the journal that replaces one given up (asked for nothing else)
     * @throws IllegalStateException the {@code LOCK} is held, the file system has too little room, the identity is refused, or
     *                               committed pins name records the journal does not hold
     */
    public static Journal open(JournalConfig config, Clock clock, Timer timer, IntFunction<SecretKey> keys, JournalFiles files,
                               SegmentRows rows, Supplier<UUID> journalIds) throws IOException {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(journalIds, "journalIds");
        Path dir = config.dir();
        String node = config.node();
        JournalIo.createDirectoriesDurably(files, dir);
        try (JournalIo.Owned lockChannel = new JournalIo.Owned(files.open(dir.resolve("LOCK"),
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)))) {
            takeLock(lockChannel.get(), dir);
            Path segments = dir.resolve("segments");
            List<Long> numbers = Files.isDirectory(segments) ? Recovery.numbers(segments) : List.of();
            JournalStart start = new JournalStart(config, files, rows);
            Path replicaFiles = dir.resolve("replicas");
            start.requireRoom(segments, numbers, Replicas.found(replicaFiles));
            Set<Long> registered = rows.segments(node).await();   // null: no rows are kept
            JournalStart.Identity identity = start.identity(numbers, registered != null, journalIds);
            SegmentFormat format = new SegmentFormat(identity.id(), node);
            // the files of the journal: every one when no rows are kept, else those with their row; none of one given up whole
            List<Long> kept = identity.replaced() ? List.of() : registered == null ? numbers : numbers.stream().filter(registered::contains).toList();
            Recovery.Plan plan = Recovery.plan(segments, format, files, kept);
            if (!identity.replaced()) start.pins(identity.id(), plan.ends());
            clearSpill(dir.resolve("spill"));
            JournalIo.createDirectoriesDurably(files, segments);   // a first start: its entry in dir forced, then segments/
            MasterKeySource.removeCrashedTmps(dir);
            long highest = highest(numbers, registered);
            unlinkOthers(segments, numbers, kept);
            Map<Long, Extent> recovered = Recovery.apply(plan, files, clock);
            if (registered != null) dropUnfiled(rows, node, identity.replaced() ? Set.of() : registered, recovered.keySet());
            for (Map.Entry<Long, Extent> e : recovered.entrySet()) e.getValue().bytes = Files.size(segments.resolve(e.getKey() + ".seg"));
            long replicaBytes = Replicas.recover(files, replicaFiles);
            SegmentFile first = create(highest + 1, node, rows, files, segments, format, clock);
            return new Journal(config, clock, timer, keys, files, rows, format, dir, lockChannel.handOn(), recovered, first,
                    sizes(segments) + replicaBytes, replicaBytes);
        }
    }

    /** The highest number a segment file or a row named (0: none). */
    private static long highest(List<Long> numbers, Set<Long> registered) {
        long highest = 0;
        for (long n : numbers) highest = Math.max(highest, n);
        if (registered != null) for (long n : registered) highest = Math.max(highest, n);
        return highest;
    }

    /** Unlinks every segment file of {@code numbers} that is not one of the journal's {@code kept} (before any read of its records). */
    private static void unlinkOthers(Path segments, List<Long> numbers, List<Long> kept) throws IOException {
        Set<Long> journal = new java.util.HashSet<>(kept);
        for (long n : numbers) {
            if (!journal.contains(n)) {
                Files.delete(segments.resolve(n + ".seg"));
                LOG.log(System.Logger.Level.WARNING, "journal segment {0} had no journal_segments row of this journal: unlinked", n);
            }
        }
    }

    /** Drops the rows whose segment has no file after recovery (none was there, or it held no record and went). */
    private static void dropUnfiled(SegmentRows rows, String node, Set<Long> registered, Set<Long> filed) {
        Set<Long> unfiled = new java.util.HashSet<>(registered);
        unfiled.removeAll(filed);
        if (!unfiled.isEmpty()) rows.drop(node, unfiled).await();
    }

    /** The bytes of the files in {@code segments/}: the journal's bytes at the start (its spill was cleared). */
    private static long sizes(Path segments) throws IOException {
        long sum = 0;
        try (Stream<Path> files = Files.list(segments)) {
            for (Iterator<Path> it = files.iterator(); it.hasNext(); ) sum += Files.size(it.next());
        }
        return sum;
    }

    /** Unlinks whatever an earlier run left under {@code spill/}: a spill never persisted nor abandoned is no one's. */
    private static void clearSpill(Path spill) throws IOException {
        // not createDirectoriesDurably: spill/ holds nothing durable (cleared here at every start; a persist moves its file
        // into segments/ and forces that), so losing spill/'s entry in a crash loses no record
        Files.createDirectories(spill);
        try (Stream<Path> left = Files.list(spill)) {
            for (Iterator<Path> it = left.iterator(); it.hasNext(); ) Files.delete(it.next());
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

    /**
     * Creates segment {@code n}: its row (the insert answered), then the file, its header, a force, a directory force. A
     * failure part-way closes the channel; a failed insert creates no file.
     */
    private static SegmentFile create(long n, String node, SegmentRows rows, JournalFiles files, Path segments, SegmentFormat format, Clock clock)
            throws IOException {
        rows.insert(node, n).await();
        return createFile(n, files, segments, format, clock, () -> JournalIo.forceDir(files, segments));
    }

    /** The file of segment {@code n}, whose row is in: created, its header written and forced, the directory forced by {@code dirForce}. */
    private static SegmentFile createFile(long n, JournalFiles files, Path segments, SegmentFormat format, Clock clock, DirForce dirForce)
            throws IOException {
        try (JournalIo.Owned ch = new JournalIo.Owned(files.open(segments.resolve(n + ".seg"),
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)))) {
            JournalIo.writeFully(ch.get(), format.header(n, clock.millis()));
            ch.get().force(false);
            dirForce.run();
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
        return append(record, abandoned, Bound.INTAKE);
    }

    /**
     * {@link #append(JournalRecord, Future)} of a record that may use {@code bound} of {@code FLOTTILA_JOURNAL_MAX_BYTES}.
     *
     * @throws JournalFullException the journal's bytes and this record would pass {@code bound}'s limit; nothing reserved
     */
    public Appended append(JournalRecord record, Future<?> abandoned, Bound bound) {
        Objects.requireNonNull(abandoned, "abandoned");
        Objects.requireNonNull(bound, "bound");
        ByteBuffer bytes = encode(record);
        int length = bytes.remaining();
        Room wait = null;
        lock.lock();
        try {
            while (true) {
                if (failure != null) throw failed();
                if (closing) throw closed();
                if (this.bytes + length > limits[bound.ordinal()]) throw full(length, bound);
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
                    + config.recordMax() + " (a large body is spilled as a segment of its own: spill())");
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
        grow(length);
        if (seg.records == config.segmentRecords()) roll();
        work.signal();
        return new Appended(ref, promise.future());
    }

    /**
     * Ends the segment taking records; the next one, whose number was taken already, takes the following record, and the
     * number after it is taken now (the writer pre-opens that one once it moves on). Under the lock.
     */
    private void roll() {
        long following = nextNumber++;
        queue.add(new End(current.n, following));
        current = new Open(upcoming, format.headerLength());
        upcoming = following;
        work.signal();
    }

    /** A segment number of the journal's one sequence, for a large body's segment. */
    private long number() {
        lock.lock();
        try {
            return nextNumber++;
        } finally {
            lock.unlock();
        }
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

    /** {@code n} more journal bytes; a WARNING once they cross 80 % of max upward. Under the lock. */
    private void grow(long n) {
        bytes += n;
        if (!warned && bytes >= warnAt) {
            warned = true;
            LOG.log(System.Logger.Level.WARNING, "journal " + config.dir() + " holds " + bytes + " bytes, 80 % of FLOTTILA_JOURNAL_MAX_BYTES "
                    + config.maxBytes() + " or more: intakes are refused above " + limits[0] + " (segments go once no row pins them)");
        }
    }

    /** {@link #grow} under the lock. */
    private void grown(long n) {
        lock.lock();
        try {
            grow(n);
        } finally {
            lock.unlock();
        }
    }

    /** {@code n} journal bytes fewer (a segment swept, a spill abandoned); below 80 % the alerts may log again. */
    private void shrunk(long n) {
        lock.lock();
        try {
            bytes -= n;
            if (bytes < warnAt) {
                warned = false;
                Arrays.fill(refusing, false);
            }
        } finally {
            lock.unlock();
        }
    }

    /** {@code length} more bytes of {@code bound}, counted, or refused when they do not fit. */
    private void reserveRoom(long length, Bound bound) {
        lock.lock();
        try {
            if (bytes + length > limits[bound.ordinal()]) throw full(length, bound);
            grow(length);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reserves {@code length} bytes for a replica's OPEN (CORE-5b1 C4), or refuses them when the replicas' bytes and they would
     * pass half the intake edge (max − max/16), or the journal's bytes and they the edge: the holder's own intake keeps the rest.
     */
    void reserveReplica(long length) {
        lock.lock();
        try {
            long edge = limits[Bound.INTAKE.ordinal()];
            if (length > edge / 2 - replicaBytes || length > edge - bytes) {   // subtracted: OPEN's length is a wire u64 (r1 R1)
                throw new JournalFullException("journal " + config.dir() + " has no room for a replica of " + length + " bytes: replicas hold "
                        + replicaBytes + " of their half " + edge / 2 + " of the intake edge " + edge + ", the journal " + bytes);
            }
            replicaBytes += length;
            grow(length);
        } finally {
            lock.unlock();
        }
    }

    /** A replica's bytes changed by {@code delta}, unchecked: written past its reservation, or trued to its file's size. */
    void replicaCounted(long delta) {
        lock.lock();
        try {
            replicaBytes += delta;
            if (delta > 0) grow(delta);
            else shrunk(-delta);
        } finally {
            lock.unlock();
        }
    }

    /** The other nodes' segments this node holds (CORE-5b1): their files count in {@link #bytes()}. */
    public Replicas replicas() {
        return replicas;
    }

    /** The refusal of {@code length} bytes of {@code bound}; an ERROR when the refusals of that bound start. Under the lock. */
    private JournalFullException full(long length, Bound bound) {
        String what = bound == Bound.INTAKE
                ? "intakes are refused (retryable, 503) above FLOTTILA_JOURNAL_MAX_BYTES − 1/16 = " + limits[0]
                : "step bodies are refused (recorded metadata-only, parks hold) above FLOTTILA_JOURNAL_MAX_BYTES " + config.maxBytes();
        String message = "journal " + config.dir() + " is full: " + bytes + " bytes and a record of " + length + "; " + what;
        if (!refusing[bound.ordinal()]) {
            refusing[bound.ordinal()] = true;
            LOG.log(System.Logger.Level.ERROR, message + " (logged again once the bytes fell below 80 % and refusals start again)");
        }
        return new JournalFullException(message);
    }

    /** The node whose journal this is ({@code FLOTTILA_NODE_ID}): its refs name it. */
    public String node() {
        return config.node();
    }

    /** This journal's id ({@code journal_nodes.journal_id}, its segments' headers): what a peer's READ names it by (CORE-5a W3). */
    public UUID id() {
        return format.journal();
    }

    /** {@code FLOTTILA_JOURNAL_RECORD_MAX}: the largest body {@link #append} takes; a longer one is {@link #spill spilled}. */
    public long recordMax() {
        return config.recordMax();
    }

    /** Whether segment {@code n} is one this journal holds now: not swept, not given up. */
    public boolean holds(long n) {
        return extents.containsKey(n);
    }

    /** The journal's bytes now (CORE-3 item 11): its segment and spill files and the records reserved and not yet written. */
    public long bytes() {
        lock.lock();
        try {
            return bytes;
        } finally {
            lock.unlock();
        }
    }

    /** True once the journal failed and stopped (fail-stop): the admin health reports it DOWN. */
    public boolean isFailed() {
        lock.lock();
        try {
            return failure != null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The admin health of the journal: DOWN once it failed (fail-stop); else DEGRADED while a pre-open's segment row insert
     * is retried (appends wait for that segment meanwhile); else UP.
     */
    public Health health() {
        lock.lock();
        try {
            if (failure != null) return Health.down("the journal failed and stopped (fail-stop): every append is refused until a restart recovers it");
            if (outage != null) {
                return Health.degraded("a journal_segments insert failed and is retried (appends wait for the segment meanwhile): " + outage);
            }
            return Health.up();
        } finally {
            lock.unlock();
        }
    }

    /** A spill or a persist begins: refused once the journal failed or its close began, else counted until {@link #leave()}. */
    private void enter() {
        lock.lock();
        try {
            if (failure != null) throw failed();
            if (closing) throw closed();
            inFlight++;
        } finally {
            lock.unlock();
        }
    }

    /** A spill or a persist is over: close() may stop waiting for it. */
    private void leave() {
        lock.lock();
        try {
            inFlight--;
            done.signalAll();
        } finally {
            lock.unlock();
        }
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
                    long length = r.entry().ref().length();
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
                afterNext = ((End) item).following();
            }
        }
        for (SegmentFile f : wrote) f.write();
        for (SegmentFile f : wrote) f.force();
        for (SegmentFile f : ended) seal(f);
    }

    /** The pre-opened next segment (waited for), and the pre-open of the one after it (its number from the last End). */
    private SegmentFile moveOn() throws IOException {
        Opened o = next.join();
        if (o.failure() instanceof ClosedBeforeOpen closed) throw closed;
        if (o.failure() != null) throw new IOException("the pre-open of the next journal segment failed", o.failure());
        held.add(o.file());
        next = preopen(afterNext);
        return o.file();
    }

    /**
     * Begins segment {@code n}'s pre-open; its header counts from now, before its file exists (a reservation, as an append's), so
     * the bytes a writer's move on adds are counted when the move returns. A pre-open that ends without its file keeps them: it
     * fails the journal (fail-stop) or ends with its close, and neither takes a byte again.
     */
    private CompletableFuture<Opened> preopen(long n) {
        CompletableFuture<Opened> f = new CompletableFuture<>();
        grown(format.headerLength());
        attempt(n, f, FIRST_RETRY);
        return f;
    }

    /**
     * One try of segment {@code n}'s pre-open, on a virtual thread of its own: its row, then its file. A failed row insert
     * is no fail-stop (no file exists): it is tried again {@code backoff} later on the timer (CORE-3 item 3, CORE-4c1 r1);
     * a failed file creation completes {@code f} with its failure, which fails the journal when the writer moves on.
     */
    private void attempt(long n, CompletableFuture<Opened> f, Duration backoff) {
        Thread.ofVirtual().name("journal-open-" + n).start(() -> {
            try {
                rows.insert(config.node(), n).await();
            } catch (Throwable e) {   // the row insert's await throws its future's cause, checked or not
                retryLater(n, f, backoff, e);
                return;
            }
            try {
                SegmentFile file = createFile(n, files, segments, format, clock, this::forceSegments);
                extents.put(n, file.extent);
                rowIn(n);
                f.complete(new Opened(file, null));
            } catch (Throwable e) {
                f.complete(new Opened(null, e));
            }
        });
    }

    /** Schedules the next try of segment {@code n}'s row insert, the journal DEGRADED meanwhile; a closing journal stops trying. */
    private void retryLater(long n, CompletableFuture<Opened> f, Duration backoff, Throwable cause) {
        boolean began;
        lock.lock();
        try {
            if (closing) {
                f.complete(new Opened(null, new ClosedBeforeOpen(cause)));
                return;
            }
            began = outage == null;
            outage = cause;
            Duration later = backoff.multipliedBy(2).compareTo(LAST_RETRY) > 0 ? LAST_RETRY : backoff.multipliedBy(2);
            retrying = f;
            retry = timer.schedule(backoff, () -> attempt(n, f, later));
        } finally {
            lock.unlock();
        }
        if (began) {
            LOG.log(System.Logger.Level.WARNING, "the journal_segments row of segment " + n + " could not be inserted: journal " + config.dir()
                    + " is DEGRADED, its appends wait for the segment; tried again from " + FIRST_RETRY + " to every " + LAST_RETRY, cause);
        }
    }

    /** Segment {@code n}'s row is in: the journal is no longer DEGRADED. */
    private void rowIn(long n) {
        boolean ended;
        lock.lock();
        try {
            ended = outage != null;
            outage = null;
        } finally {
            lock.unlock();
        }
        if (ended) LOG.log(System.Logger.Level.INFO, "the journal_segments row of segment {0} is in: journal {1} is UP again", n, config.dir());
    }

    /**
     * Forces {@code segments/} as one generation of its forces (a pre-open's, a persist's): the generation is taken before the
     * force and ended after it, a failure recorded with its generation before the end is (CORE-4c3 r1 carry).
     *
     * @return the force's generation
     */
    private long forceSegments() throws IOException {
        long gen;
        lock.lock();
        try {
            gen = ++dirForces;
            dirForcing.add(gen);
        } finally {
            lock.unlock();
        }
        boolean ok = false;
        try {
            JournalIo.forceDir(files, segments);
            ok = true;
            return gen;
        } finally {
            lock.lock();
            try {
                if (!ok) dirFailedAt = Math.min(dirFailedAt, gen);
                dirForcing.remove(gen);
                done.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * After the force of generation {@code gen} returned: waits until every force of {@code segments/} begun before it ended, and
     * refuses when one of those failed — a force that succeeds after a failed one proves nothing for what was written before it
     * (fsyncgate), and one that failed while this force ran may have dropped this persist's move.
     */
    private void requireNoEarlierDirFailure(long gen) {
        lock.lock();
        try {
            while (!dirForcing.isEmpty() && dirForcing.first() < gen) done.awaitUninterruptibly();
            if (dirFailedAt < gen) {
                throw new JournalFailedException("a force of " + segments + " begun before this persist's own (generation " + gen + ") failed (generation "
                        + dirFailedAt + "): its move into segments/ is not known to be durable", failure);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Hands {@code f} to a sealer virtual thread: the writer's last write and force on it have returned. */
    private void seal(SegmentFile f) {
        Thread sealer = Thread.ofVirtual().name("journal-sealer-" + f.n).unstarted(() -> {
            try (FileChannel ch = f.channel) {
                long sealedMs = clock.millis();
                SegmentFormat.Footer footer = SegmentFormat.footer(f.entries, f.end, sealedMs);
                int length = footer.bytes().remaining();
                JournalIo.writeFully(ch, footer.bytes());
                grown(length);
                ch.force(false);
                f.extent.bytes = f.end + length;   // the file: its header and records, then the footer
                f.extent.sealedMs = sealedMs;
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
        if (first && !(cause instanceof ClosedBeforeOpen)) {
            LOG.log(System.Logger.Level.ERROR, "journal " + config.dir() + " failed and stopped (fail-stop): no write or force is"
                    + " retried, every append fails until a restart recovers it; reads go on", cause);
        }
        for (Promise<Void> p : doomed) Thread.ofVirtual().name("journal-failed").start(() -> p.tryFail(e));
    }

    // ---------------------------------------------------------------------------------------------------- read

    /**
     * The plaintext of the record at {@code ref}: read with a pread, checked by CRC32C, decrypted under its tenant key. A
     * record no longer than a segment record can be is read whole, a large one too.
     *
     * @throws IllegalArgumentException {@code ref} is longer than any segment record: a large body, read with {@link #stream}
     * @throws JournalCorruptException  the bytes fail their CRC32C, are not a v1 record of that length, or fail
     *                                  authentication (logged at ERROR)
     * @throws JournalKeyException      the record's tenant key is not there or cannot be used
     * @throws IOException              no record can lie at {@code ref} (a segment this journal does not hold, or past the
     *                                  last record written and forced), or the read failed
     */
    public byte[] read(JournalRef ref) throws IOException {
        return read(ref, null);
    }

    /**
     * {@link #read(JournalRef)} of the body under {@code key}: a record whose header names another message id, step or part
     * fails as {@link JournalCorruptException} (ERROR) — a misaddressed ref never answers another body's bytes.
     */
    public byte[] read(JournalRef ref, BlobKey key) throws IOException {
        Path file = located(ref);
        if (ref.length() > longestRecord) {
            throw new IllegalArgumentException("the record at " + ref + " is longer than any segment record (FLOTTILA_JOURNAL_RECORD_MAX "
                    + config.recordMax() + "): a large body is read with stream()");
        }
        return plain(ref, channels.pread(ref.segment(), file, ref.position(), (int) ref.length()), key);
    }

    /** The plaintext of {@code rec}, the whole record at {@code ref}, checked as {@link #read(JournalRef, BlobKey)} checks it. */
    private byte[] plain(JournalRef ref, byte[] rec, BlobKey key) throws IOException {
        RecordFormat.Header h = checked(ref, rec, rec.length, key);
        SecretKey recordKey = recordKey(ref, h);
        try {
            return RecordFormat.decrypt(rec, h, recordKey);
        } catch (GeneralSecurityException x) {
            throw corrupt(ref, "fails authentication", x);
        }
    }

    /**
     * The plaintext of the record at {@code ref} as a stream of its own: a large body's one chunk at a time (its header
     * checked by CRC32C, each chunk authenticated by its tag before a byte of it is given), any other record as
     * {@link #read} reads it. The checks and failures are {@link #read}'s; a large chunk cut off, out of place or edited
     * fails the stream's read as {@link JournalCorruptException}.
     */
    public InputStream stream(JournalRef ref) throws IOException {
        return stream(ref, null);
    }

    /** {@link #stream(JournalRef)} of the body under {@code key}, its header checked against it as {@link #read(JournalRef, BlobKey)} checks it. */
    public InputStream stream(JournalRef ref, BlobKey key) throws IOException {
        Path file = located(ref);
        if (ref.length() <= longestRecord) return new ByteArrayInputStream(read(ref, key));
        return chunks(file, ref, key);
    }

    /**
     * {@link #stream(JournalRef, BlobKey)} of a record that lies in another node's journal at {@code ref}, its bytes as they lie
     * there — from its first one, in order — read from {@code raw} (the peer link's READ, CORE-5a S7): checked here by CRC32C,
     * against {@code key} and by AEAD under this journal's tenant keys, exactly as a local record is; a record of
     * {@code ref}'s length longer than this journal's segment records is read as a large one, one chunk at a time. Closing the
     * answer closes {@code raw}, and so does a failure here; {@code raw} ending before the record does is corrupt.
     */
    public InputStream stream(JournalRef ref, BlobKey key, InputStream raw) throws IOException {
        byte[] small;
        try {
            if (ref.length() > longestRecord) {
                byte[] head = whole(ref, raw, (int) Math.min(ref.length(), RecordFormat.MAX_HEADER));
                RecordFormat.Header h = checked(ref, head, head.length, key);
                return LargeRecord.reader(raw, ref, h, recordKey(ref, h), head);
            }
            small = plain(ref, whole(ref, raw, (int) ref.length()), key);
        } catch (IOException | RuntimeException | Error e) {
            try {
                raw.close();
            } catch (IOException x) {
                e.addSuppressed(x);
            }
            throw e;
        }
        raw.close();   // the small record read whole: its stream is done (closed once on every path)
        return new ByteArrayInputStream(small);
    }

    /** The first {@code n} bytes of {@code raw}; fewer are a record cut off: corrupt. */
    private static byte[] whole(JournalRef ref, InputStream raw, int n) throws IOException {
        byte[] b = raw.readNBytes(n);
        if (b.length < n) throw corrupt(ref, "ends after " + b.length + " of its bytes", null);
        return b;
    }

    /**
     * The bytes of the record at {@code ref} as they lie in its segment, unchecked: what this node's peer link sends a reader on
     * another node, which checks them ({@link #stream(JournalRef, BlobKey, InputStream)}, CORE-5a). The stream reads a channel of
     * its own of the segment file from the record's first byte on — its caller reads {@code ref.length()} of them; closing it closes
     * the channel.
     *
     * @throws IOException no record can lie at {@code ref} (a segment this journal does not hold, or past the last record
     *                     written and forced), or the file cannot be opened
     */
    public InputStream raw(JournalRef ref) throws IOException {
        try (JournalIo.Owned ch = new JournalIo.Owned(files.open(located(ref), Set.of(StandardOpenOption.READ)))) {
            ch.get().position(ref.position());
            return java.nio.channels.Channels.newInputStream(ch.handOn());
        }
    }

    /** The file of {@code ref}'s segment, once it is a place a record can lie at; else an {@link IOException}. */
    private Path located(JournalRef ref) throws IOException {
        if (closing) throw closed();
        Extent e = extents.get(ref.segment());
        long end = e == null ? format.headerLength() : e.end;
        if (ref.length() < RecordFormat.MIN_LENGTH || ref.position() < format.headerLength() || ref.position() > end - ref.length()) {
            throw new IOException("no journal record can lie at " + ref + ": segment " + ref.segment() + " holds records up to " + end);
        }
        return segmentFile(ref.segment());
    }

    /** The large record at {@code ref} in {@code file}, read one chunk at a time on a channel the stream closes. */
    private InputStream chunks(Path file, JournalRef ref, BlobKey key) throws IOException {
        try (JournalIo.Owned ch = new JournalIo.Owned(files.open(file, Set.of(StandardOpenOption.READ)))) {
            int available = (int) Math.min(ref.length(), RecordFormat.MAX_HEADER);
            byte[] head = JournalIo.read(ch.get(), ref.position(), available);
            RecordFormat.Header h = checked(ref, head, available, key);
            SecretKey recordKey = recordKey(ref, h);
            return LargeRecord.reader(ch.handOn(), ref, h, recordKey, head);
        }
    }

    /**
     * The header of the record at {@code ref} from its first {@code available} bytes, once its CRC32C and its sizes hold and it
     * is the record of {@code key} (null: any key).
     */
    private static RecordFormat.Header checked(JournalRef ref, byte[] b, int available, BlobKey key) throws JournalCorruptException {
        if (!RecordFormat.crcHolds(b, available)) throw corrupt(ref, "fails its CRC32C", null);
        RecordFormat.Header h = RecordFormat.header(b, available);
        if (h == null || h.length() != ref.length()) throw corrupt(ref, "is not a v1 journal record of " + ref.length() + " bytes", null);
        if (key != null && !key.equals(new BlobKey(h.messageId(), h.step(), h.part()))) {
            throw corrupt(ref, "is the record of " + new BlobKey(h.messageId(), h.step(), h.part()) + ", not of " + key + " (a misaddressed ref)", null);
        }
        return h;
    }

    /** The record's own key under its tenant key. */
    private SecretKey recordKey(JournalRef ref, RecordFormat.Header h) throws JournalKeyException {
        SecretKey key = keys.apply(h.keyId());
        if (key == null) throw new JournalKeyException("the tenant key " + h.keyId() + " of the journal record at " + ref + " is not there", null);
        try {
            return RecordFormat.recordKey(key, h.salt(), h.keyId());
        } catch (GeneralSecurityException x) {
            throw new JournalKeyException("the tenant key " + h.keyId() + " of the journal record at " + ref + " cannot be used", x);
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
        byte[] bytes = channels.pread(n, segmentFile(n), e.end, idx.length());
        if (JournalIo.crc(bytes, 0, bytes.length) != idx.crc()) {
            throw corrupt(new JournalRef(n, e.end, idx.length()), "(the footer index of segment " + n + ") fails its CRC32C", null);
        }
        return Optional.of(SegmentFormat.entries(bytes, n, idx.count()));
    }

    private Path segmentFile(long n) {
        return segments.resolve(n + ".seg");
    }

    static JournalCorruptException corrupt(JournalRef ref, String what, Throwable cause) {
        JournalCorruptException e = new JournalCorruptException("the journal record at " + ref + " " + what, cause);
        LOG.log(System.Logger.Level.ERROR, e.getMessage(), e);
        return e;
    }

    // ---------------------------------------------------------------------------------------------------- large bodies

    /**
     * Spills {@code body} under {@code key}: reads it to its end on this thread and writes it, from its first byte, as the
     * one large record of a segment of its own under {@code spill/}, encrypted under the tenant key {@code tenantKeyId} and
     * hashed by {@code hasher} once while it streams. Nothing is forced, nothing is in {@code segments/} and no segment row
     * exists until {@link Spill#persist()}. Any failure leaves no file behind. The spill is bounded by
     * {@link Bound#RESERVE}: see {@link #spill(BlobKey, int, InputStream, long, Hasher, Bound)}.
     *
     * @param maxSize the largest body taken, in {@code 0..(2^31 - 1) MiB} (the chunk counter's range)
     * @throws IllegalArgumentException {@code maxSize} out of range (before a byte is read), a name above 65 535 bytes, or a
     *                                  tenant key that is not there or cannot encrypt
     * @throws BodyTooLargeException    the body goes past {@code maxSize}; it was read no further than one chunk beyond it
     * @throws JournalFailedException   the journal failed (retryable); nothing read
     * @throws IllegalStateException    the journal is closed (a close waits for every spill already running)
     * @throws IOException              the body could not be read, or the spill not written
     * @throws JournalFullException     the spill would pass {@code FLOTTILA_JOURNAL_MAX_BYTES}
     */
    public Spill spill(BlobKey key, int tenantKeyId, InputStream body, long maxSize, Hasher hasher) throws IOException {
        return spill(key, tenantKeyId, body, maxSize, hasher, Bound.RESERVE);
    }

    /**
     * {@link #spill(BlobKey, int, InputStream, long, Hasher)} of a spill bounded while it streams (CORE-4d r1): its segment
     * header is refused ({@link JournalFullException}, no file, nothing read) when it does not fit under {@code bound}'s limit —
     * an intake's spill past {@code FLOTTILA_JOURNAL_MAX_BYTES − 1/16} writes no chunk only to be answered 503 —, then each
     * write is counted as it is made and one that would pass max fails the spill with a {@link JournalFullException}; its file
     * is then unlinked on a virtual thread of its own (off the hot path), its bytes counted until it is gone.
     */
    public Spill spill(BlobKey key, int tenantKeyId, InputStream body, long maxSize, Hasher hasher, Bound bound) throws IOException {
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(hasher, "hasher");
        if (maxSize < 0 || maxSize > RecordFormat.MAX_PLAIN) {
            throw new IllegalArgumentException("maxSize must lie in 0.." + RecordFormat.MAX_PLAIN + ", was " + maxSize);
        }
        enter();
        try {
            return spilled(key, tenantKeyId, body, maxSize, hasher, bound);
        } finally {
            leave();
        }
    }

    private Spill spilled(BlobKey key, int tenantKeyId, InputStream body, long maxSize, Hasher hasher, Bound bound) throws IOException {
        SecretKey tenantKey = keys.apply(tenantKeyId);
        if (tenantKey == null) throw new IllegalArgumentException("no tenant key " + tenantKeyId);
        byte[] salt = new byte[ChunkedAead.SALT_BYTES];
        random.nextBytes(salt);
        long header = format.headerLength();
        reserveRoom(header, bound);   // before the file: a spill refused here writes nothing
        long n = number();
        Path file = spills.resolve(n + ".seg");
        long[] counted = {header};    // the bytes taken so far, the header's first
        Throwable failure = null;     // set by every failure: the finally then unlinks what was written
        try (FileChannel ch = files.open(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            JournalIo.writeFully(ch, format.header(n, clock.millis()));
            LargeRecord.Written w = LargeRecord.write(ch, key, tenantKeyId, tenantKey, salt, body, maxSize, hasher, length -> {
                reserveRoom(length, Bound.RESERVE);
                counted[0] += length;
            });
            JournalRef ref = new JournalRef(n, header, w.length());
            return new Spill(file, new JournalIndexEntry(key.messageId(), key.step(), key.part(), ref), w.size(), w.hash(), counted[0]);
        } catch (GeneralSecurityException e) {
            failure = new IllegalArgumentException("tenant key " + tenantKeyId + " cannot encrypt a large body", e);
            throw (IllegalArgumentException) failure;
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            if (failure instanceof JournalFullException) {
                unlinkLater(file, counted[0]);
            } else if (failure != null) {
                JournalIo.unlinkAfter(file, failure);
                shrunk(counted[0]);
            }
        }
    }

    /** Unlinks a spill refused while it streamed on a virtual thread of its own, its {@code n} bytes counted until it is gone. */
    private void unlinkLater(Path file, long n) {
        Thread.ofVirtual().name("journal-unlink-" + file.getFileName()).start(() -> {
            try {
                Files.deleteIfExists(file);
                shrunk(n);
            } catch (IOException e) {   // the file stays (a start unlinks what spill/ holds): its bytes stay counted
                LOG.log(System.Logger.Level.WARNING, "the refused spill " + file + " could not be unlinked; a restart unlinks it", e);
            }
        });
    }

    /**
     * A large body spilled (CORE-3 item 4): a one-record segment of its own under {@code spill/}, until it is persisted into
     * {@code segments/} or abandoned. Its ref is known from the start.
     *
     * @since 0.1
     */
    public final class Spill {
        private final Path file;
        private final JournalIndexEntry entry;
        private final long size;
        private final Hash hash;
        // guarded by this
        private long counted;         // the journal bytes its file is counted for
        private long sealedMs;        // its footer's sealed ms, once written
        private Index index;          // the footer, once written and forced
        private Throwable broken;     // a write or force of the footer failed: it is never retried
        private boolean persisted;
        private boolean abandoned;

        private Spill(Path file, JournalIndexEntry entry, long size, Hash hash, long counted) {
            this.file = file;
            this.entry = entry;
            this.size = size;
            this.hash = hash;
            this.counted = counted;
        }

        /** The segment number: from the journal's one sequence, the writer's segments never take it. */
        public long segment() { return entry.ref().segment(); }

        /** The ref of its record, known from the start: where it lies once {@link #persist persisted}. */
        public JournalRef ref() { return entry.ref(); }

        /** The body's size in bytes. */
        public long size() { return size; }

        /** The body's hash, computed once while it streamed in. */
        public Hash hash() { return hash; }

        /**
         * A fresh stream of the body, from its first byte, read from where the segment lies (the spill, or {@code segments/}
         * once persisted); each chunk is authenticated before a byte of it is given.
         *
         * @throws IllegalStateException the journal is closed
         * @throws IOException           the file is not there (abandoned), or it is not what was spilled
         */
        public synchronized InputStream open() throws IOException {
            if (closing) throw closed();
            return chunks(persisted ? segmentFile(segment()) : file, entry.ref(), null);
        }

        /**
         * Makes the segment durable and part of the journal: writes its footer index and trailer (sealed ms on the injected
         * clock) and forces it, once; inserts its segment row; moves the file into {@code segments/}; forces that directory.
         * A failed insert or move leaves no file in {@code segments/} and the spill in place: persist again (the footer is not
         * written twice, the row is inserted again) or {@link #abandon()}. A failed footer (its write or force) or a failed
         * directory force after the move fails the journal (fail-stop) and breaks the spill: it is never written, forced or
         * answered as persisted again, it can only be abandoned. A persisted spill not abandoned since answers its ref again
         * while the journal is open and has not failed; a failed journal refuses it ({@link JournalFailedException}), a closed
         * one too ({@link IllegalStateException}), though the segment stays durable and readable after a restart.
         *
         * @return the ref of its record
         * @throws IllegalStateException  the journal is closed (a close waits for every persist already running), the spill
         *                                was abandoned, or it broke before
         * @throws JournalFailedException the journal failed (retryable); nothing written
         * @throws JournalFullException   its footer does not fit under {@link Bound#INTAKE}'s limit; nothing written
         */
        public JournalRef persist() throws IOException {
            return persist(Bound.INTAKE);
        }

        /**
         * {@link #persist()} of a spill that may use {@code bound} of {@code FLOTTILA_JOURNAL_MAX_BYTES}: its footer is refused
         * ({@link JournalFullException}, nothing written) when the journal's bytes — this spill's counted already — and the footer
         * would pass the bound's limit. A force of {@code segments/} that failed while — or before — its own directory force ran
         * fails it ({@link JournalFailedException}): it waits for every such force begun before its own to end, then asks.
         */
        public synchronized JournalRef persist(Bound bound) throws IOException {
            Objects.requireNonNull(bound, "bound");
            if (abandoned) throw new IllegalStateException("the spill of segment " + segment() + " was abandoned: it is never persisted");
            if (broken != null) {
                throw new IllegalStateException("the spill of segment " + segment() + " failed (" + broken
                        + "): it is never written, forced or persisted again; abandon the spill", broken);
            }
            enter();
            try {
                if (persisted) return entry.ref();
                if (index == null) {
                    long end = entry.ref().position() + entry.ref().length();
                    long ms = clock.millis();
                    SegmentFormat.Footer footer = SegmentFormat.footer(List.of(entry), end, ms);
                    int length = footer.bytes().remaining();
                    reserveRoom(length, bound);
                    counted += length;
                    index = durably(() -> seal(end, footer));
                    sealedMs = ms;
                }
                rows.insert(config.node(), segment()).await();
                Files.move(file, segmentFile(segment()), StandardCopyOption.ATOMIC_MOVE);
                long gen = durably(Journal.this::forceSegments);
                // a force that succeeds after a failed one proves nothing for what was written before it (fsyncgate): a force of
                // this directory begun before this one — another persist's, a pre-open's — that failed fails this persist
                requireNoEarlierDirFailure(gen);
                Extent sealed = Extent.sealed(entry.ref().position() + entry.ref().length(), index);
                sealed.bytes = counted;
                sealed.sealedMs = sealedMs;
                extents.put(segment(), sealed);
                persisted = true;
                return entry.ref();
            } finally {
                leave();
            }
        }

        /** A write or force of the spill: its failure breaks the spill for good and fails the journal (fail-stop). */
        private <T> T durably(Forcing<T> step) throws IOException {
            try {
                return step.run();
            } catch (IOException | RuntimeException | Error e) {
                broken = e;
                fail(e, List.of());
                throw e;
            }
        }

        /** Writes {@code footer} (index and trailer) after the record, which ends at {@code end}, and forces the file. */
        private Index seal(long end, SegmentFormat.Footer footer) throws IOException {
            try (FileChannel ch = files.open(file, Set.of(StandardOpenOption.WRITE))) {
                ch.position(end);
                JournalIo.writeFully(ch, footer.bytes());
                ch.force(false);
            }
            return new Index(footer.indexLength(), footer.indexCrc(), 1);
        }

        /**
         * Gives the spill up: unlinks its file under {@code spill/} (nothing once it was moved into {@code segments/}); it is
         * never persisted afterwards.
         */
        public synchronized void abandon() throws IOException {
            abandoned = true;
            if (Files.deleteIfExists(file)) shrunk(counted);
        }
    }

    // ---------------------------------------------------------------------------------------------------- sweep

    /** The segments this journal holds that were sealed at or before {@code ms} (a segment found at start: at the start). */
    Set<Long> sealedBy(long ms) {
        Set<Long> out = new TreeSet<>();
        for (Map.Entry<Long, Extent> e : extents.entrySet()) if (e.getValue().sealedMs <= ms) out.add(e.getKey());
        return out;
    }

    /**
     * Segment {@code n}, sealed, has lost its row to the sweeper (CORE-3 item 10): the journal holds it no more (a read of it
     * fails as of a segment it does not hold), its cached read channel is dropped — closed now or by its last read — and its file
     * unlinked, its bytes out of the bound. On the sweeper's thread, never on the hot path.
     */
    void swept(long n) throws IOException {
        unlinking.put(n, extents.remove(n).bytes);   // a candidate of this journal's: held until now
        channels.drop(n);
        unlink(n);
    }

    /**
     * Unlinks swept segment {@code n}'s file, the bytes the journal counted for it out of the bound once it is gone; one that
     * fails stays counted, and the sweeper tries it again ({@link SegmentSweeper}). A file already gone counts as unlinked.
     */
    void unlink(long n) throws IOException {
        long size = unlinking.get(n);
        try {
            Files.delete(segmentFile(n));
        } catch (java.nio.file.NoSuchFileException gone) {
            // gone already (by hand, or a restart's leftovers): unlinked all the same, nothing left to retry
        }
        unlinking.remove(n);
        shrunk(size);
    }

    // ---------------------------------------------------------------------------------------------------- close

    /**
     * Stops taking appends, spills and persists, lets the writer write and force every append already taken, seals the open
     * segment, waits for every sealer and every spill and persist already running, then closes the channels and gives the
     * {@code LOCK} up. A failed journal closes too, forcing nothing. A second close finds nothing to write, seal or close.
     */
    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            closing = true;
            if (retry != null && retry.cancel()) {   // a pending retry of a pre-open's row: the close does not wait for Postgres
                retrying.complete(new Opened(null, new ClosedBeforeOpen(outage)));
            }
            if (current.records > 0) roll();
            work.signalAll();
            room.signalAll();
            while (!writerDone || sealing > 0 || inFlight > 0) done.awaitUninterruptibly();
        } finally {
            lock.unlock();
        }
        List<Closeable> open = new ArrayList<>();
        open.add(lockChannel);
        open.add(channels);
        Opened o = next.join();
        if (o.file() != null) open.add(o.file().channel);
        for (SegmentFile f : held) open.add(f.channel);
        JournalIo.closeAll(open.iterator());
    }
}
