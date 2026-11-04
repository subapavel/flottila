package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyLostException;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hasher;
import io.flottila.api.InputStreamReadStream;
import io.flottila.runtime.journal.Journal;
import io.flottila.runtime.journal.JournalRecord;
import io.flottila.runtime.journal.JournalRef;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The runtime {@link BlobStore} (ARCHITECTURE §4 "where the bytes live", D-105): a body stays in memory while it is at most
 * {@link Config#memoryTierBytes()} and {@code FLOTTILA_JOURNAL_RECORD_MAX} and the node-wide {@link MemoryBudget} has room;
 * otherwise it is a spill of the node's body journal ({@link Journal#spill}: a large record of its own under
 * {@code <journal>/spill}, encrypted at rest). A streamed body starts in memory and spills the moment it outgrows either limit —
 * the prefix already seen is replayed into the spill; the hash runs over every byte once (in memory at the end, or by the
 * spill while it streams). Objects are keyed by message id + step ({@link BlobKey}), never by content. Every limit is finite;
 * sizes are long.
 *
 * <p>Blocking file I/O runs on a virtual thread — the spill of a streamed body on one of its own that drains a bounded queue;
 * the caller's stream is paused while the spill is behind and resumed as it drains, so an event-loop source is never blocked.
 *
 * <p><b>Durable.</b> With Postgres ({@link #TieredBlobStore(Config, Hasher, Journal, int, Pool)}), {@link #persist} makes a
 * local body durable in the journal — a memory body is appended as a record, a spill persisted as its segment — and only once
 * that is forced commits the key's {@code body_refs} row naming it (CORE-3 items 5–7); then the local copy goes. A key's durable
 * bytes never change (D-061 amended by FIX-35): a persist that meets the key's row while it holds a local copy reads the stored
 * record back and compares it byte for byte, 64 KiB at a time — equal is quiet, other bytes fail with an
 * {@link IllegalStateException} and change nothing; a row whose segment row is gone (swept) is replaced in the same statement.
 * A persist of a key held nowhere locally adds no record: one statement holds its source row — the key's {@code body_refs} row,
 * or for an ingress key the work row of its message — and that row's segment row {@code for key share}, inserting the
 * {@code body_refs} row from them; no source is a {@link BlobNotFoundException}, a given-up one ({@code lost_at}) a
 * {@link BodyLostException}. No body byte goes to Postgres; {@code bodies}
 * rows (pre-V12) are only read. The intake makes its body durable with {@link #forced} and commits the rows itself (D-082).
 *
 * <p><b>Reads</b> (CORE-3 item 8): the local copy; then the in-memory location index of the hot path — filled when an intake's
 * save committed ({@link Durable#committed}) and by a claim of this node's work ({@link #located}), emptied by the completion
 * ({@link #forget}), at most {@link Config#indexEntries()} entries, the oldest segment's first out; then the key's
 * {@code body_refs} row, then (an ingress key) the work row of its message, then its pre-V12 {@code bodies} row, then
 * {@link BlobNotFoundException}. A ref whose segment row is gone reads as removed (never written); a ref on another node fails
 * retryable, naming that node ({@link RemoteBodyException}; CORE-5 adds peer reads), and exists there. Without Postgres a key
 * not held locally is not found and every persist is refused.
 *
 * @since 0.1
 */
public final class TieredBlobStore implements BlobStore {

    /** @since 0.1 */
    public enum Location { MEMORY, SPILL }

    /**
     * The tiers. {@code memoryTierBytes} may be 0 (no memory tier) and never exceeds {@code memoryBudgetBytes};
     * {@code durableMaxBytes} bounds what {@link #persist} makes durable; {@code indexEntries} bounds the location index.
     *
     * @since 0.1
     */
    public record Config(long treeLimit, long memoryTierBytes, long memoryBudgetBytes, long durableMaxBytes, int indexEntries) {

        /** The location index's bound by default (CORE-3 item 8). */
        public static final int INDEX_ENTRIES = 262_144;

        public Config {
            if (treeLimit <= 0) throw new IllegalArgumentException("treeLimit must be positive, was " + treeLimit);
            if (memoryTierBytes < 0) throw new IllegalArgumentException("memoryTierBytes must not be negative, was " + memoryTierBytes);
            if (memoryBudgetBytes <= 0) throw new IllegalArgumentException("memoryBudgetBytes must be positive, was " + memoryBudgetBytes);
            if (memoryTierBytes > memoryBudgetBytes) throw new IllegalArgumentException("memoryTierBytes " + memoryTierBytes + " above the memory budget " + memoryBudgetBytes);
            if (memoryTierBytes > Integer.MAX_VALUE) throw new IllegalArgumentException("memoryTierBytes above " + Integer.MAX_VALUE + ": one in-memory body is one buffer");
            if (durableMaxBytes <= 0) throw new IllegalArgumentException("durableMaxBytes must be positive, was " + durableMaxBytes);
            if (indexEntries <= 0) throw new IllegalArgumentException("indexEntries must be positive, was " + indexEntries);
        }

        /** The tiers with the default index bound. */
        public Config(long treeLimit, long memoryTierBytes, long memoryBudgetBytes, long durableMaxBytes) {
            this(treeLimit, memoryTierBytes, memoryBudgetBytes, durableMaxBytes, INDEX_ENTRIES);
        }
    }

    /**
     * A body made durable in the journal for the intake ({@link #forced}): its record forced, its ref known. In the open-save set
     * from the record's reservation until {@link #committed} or {@link #rolledBack} (CORE-3 item 6); a body that was durable
     * already (no local copy) has no ref of its own: its rows name it by its key.
     *
     * @since 0.1
     */
    public final class Durable {
        private final BlobKey key;
        private final Stored local;   // null: durable already
        private final Save save;      // null: durable already

        private Durable(BlobKey key, Stored local, Save save) {
            this.key = key;
            this.local = local;
            this.save = save;
        }

        /** The node whose journal holds the record. */
        public String node() { return node; }

        /** The record's ref; null for a body that was durable already, whose rows name it by its key. */
        public JournalRef ref() { return save == null ? null : save.ref; }

        /**
         * Keeps the key's ref ({@code body_refs}) on {@code tx}, the caller's transaction, as {@link #persist} does (FIX-35): a row
         * the key has already, naming a live record, is compared with the local bytes by reading that record back — the same
         * bytes keep it, other bytes fail with {@link IllegalStateException} (the caller's transaction rolls back); one on
         * another node fails with {@link RemoteBodyException}. Nothing for a body durable already.
         */
        public Future<Void> keepIn(SqlClient tx) {
            return save == null ? Future.succeededFuture() : keep(tx, key, local, save.ref);
        }

        /** The caller's transaction committed and its commit was acknowledged: out of the open saves, the ref indexed, the local copy gone. */
        public void committed() {
            if (save == null) return;
            saves.remove(save);
            index.put(key, save.ref);
            release(key, local);
        }

        /** The caller's transaction rolled back (or its outcome is known to be no commit): out of the open saves; the body stays local. */
        public void rolledBack() {
            if (save != null) saves.remove(save);
        }
    }

    static final long DEFAULT_QUEUE_HIGH_WATER = 1L << 20;   // bytes queued for the spill before the source is paused
    /** FIX-35's read-back compares this many bytes at a time. */
    private static final int COMPARE_BLOCK = 64 * 1024;

    /** A ref row whose segment row is gone (swept, CORE-4d): it names no record any more. */
    private static final String GONE = "not exists (select 1 from journal_segments s where s.node = r.node and s.segment = r.segment)";
    /**
     * A persist's ref (FIX-35 without bytes in Postgres): inserted; or the key's row met — replaced in place when its segment row
     * is gone, else left as it was. Answers the row as it is now: this record's ref, or the one it already names.
     */
    private static final String KEEP = "insert into body_refs as r (message_id, step, part, node, segment, position, length) values ($1, $2, $3, $4, $5, $6, $7) "
            + "on conflict (message_id, step, part) do update set "
            + "node = case when " + GONE + " then excluded.node else r.node end, "
            + "segment = case when " + GONE + " then excluded.segment else r.segment end, "
            + "position = case when " + GONE + " then excluded.position else r.position end, "
            + "length = case when " + GONE + " then excluded.length else r.length end, "
            + "created_at = case when " + GONE + " then now() else r.created_at end, "
            + "archived_at = case when " + GONE + " then null else r.archived_at end "
            + "returning node, segment, position, length";
    /**
     * A persist of a key held nowhere locally (CORE-3 item 10, last sentence): its source — the key's ref row, or for an ingress
     * key ($4) its message's work row — with that row's segment row, both {@code for key share}; a work row's ref is inserted as
     * the key's. A source an accepted loss gave up ({@code lost_at}) is no source. Answers the sources found (or the key's pre-V12
     * {@code bodies} row) — none is no body — and whether a given-up source is there (then the body is lost, CORE-4c3 r1).
     */
    private static final String REFERENCE = """
            with kept as (select 1 from body_refs r join journal_segments s on s.node = r.node and s.segment = r.segment
                           where r.message_id = $1 and r.step = $2 and r.part = $3 and r.lost_at is null for key share),
            src as (select w.body_node, w.body_segment, w.body_position, w.body_length from work w
                      join journal_segments s on s.node = w.body_node and s.segment = w.body_segment
                     where $4 and w.id = $1 and w.lost_at is null and not exists (select 1 from kept) limit 1 for key share),
            ins as (insert into body_refs(message_id, step, part, node, segment, position, length)
                    select $1, $2, $3, body_node, body_segment, body_position, body_length from src on conflict do nothing returning 1)
            select (select count(*) from kept) + (select count(*) from ins)
                 + (select count(*) from bodies where message_id = $1 and step = $2 and part = $3),
                   exists (select 1 from body_refs where message_id = $1 and step = $2 and part = $3 and lost_at is not null)
                or exists (select 1 from work where $4 and id = $1 and lost_at is not null)""";
    /**
     * Where a key's body lies, as Postgres names it: its ref row first, then (an ingress key, $4) its message's work row; whether
     * its segment row is there, and whether an accepted loss gave it up (V12/V13 {@code lost_at}).
     */
    private static final String FIND = """
            select 0 as o, r.node, r.segment, r.position, r.length, s.node is not null as live, r.lost_at is not null as lost from body_refs r
              left join journal_segments s on s.node = r.node and s.segment = r.segment
             where r.message_id = $1 and r.step = $2 and r.part = $3
            union all
            select 1, w.body_node, w.body_segment, w.body_position, w.body_length, s.node is not null, w.lost_at is not null from work w
              left join journal_segments s on s.node = w.body_node and s.segment = w.body_segment
             where $4 and w.id = $1 and w.body_node is not null
            order by o limit 1""";

    private final Config config;
    private final Hasher hasher;
    private final MemoryBudget budget;
    private final Journal journal;
    private final int tenant;
    private final String node;
    private final long memoryTier;   // the memory tier, at most RECORD_MAX: an in-memory body is always one record
    private final Pool pool;          // null: no Postgres, nothing durable
    private final PgBodies bodies;    // the pre-V12 rows, read only; null without Postgres
    private final long queueHighWater;
    private final Map<BlobKey, Stored> objects = new ConcurrentHashMap<>();
    private final LocationIndex index;
    private final Set<Save> saves = ConcurrentHashMap.newKeySet();
    private volatile long peakQueued;   // the most bytes any streaming write held ahead of its spill (a test seam for the back-pressure bound)
    private volatile Runnable afterSinkDecision;   // test seam: see afterSinkDecision(Runnable)

    private static final System.Logger LOG = System.getLogger(TieredBlobStore.class.getName());

    /** One open save (CORE-3 item 6): a record reserved or forced whose rows' commit or rollback is not yet acknowledged. */
    private static final class Save {
        final JournalRef ref;

        Save(JournalRef ref) { this.ref = ref; }
    }

    /**
     * One write's local copy. Identity equality, never value equality (D-062): a same-size rewrite of a body has the same
     * location and size, and {@link #release} must not take it for the entry its persist read.
     */
    private static final class Stored {
        private final Location location;
        private final Buffer bytes;          // MEMORY
        private final Journal.Spill spill;   // SPILL
        private final long size;

        Stored(Location location, Buffer bytes, Journal.Spill spill, long size) {
            this.location = location;
            this.bytes = bytes;
            this.spill = spill;
            this.size = size;
        }

        Location location() { return location; }
        long size() { return size; }

        /** A fresh stream of the local bytes. */
        InputStream stream() throws IOException {
            return location == Location.MEMORY ? new ByteArrayInputStream(bytes.getBytes()) : spill.open();
        }
    }

    /** A store without Postgres: bodies live in memory and in the journal's spill; {@link #persist} refuses every key. */
    public TieredBlobStore(Config config, Hasher hasher, Journal journal, int tenantKeyId) {
        this(config, hasher, journal, tenantKeyId, null, DEFAULT_QUEUE_HIGH_WATER);
    }

    /** The node's store: bodies made durable in {@code journal} under the tenant key {@code tenantKeyId}, their refs on {@code pool}. */
    public TieredBlobStore(Config config, Hasher hasher, Journal journal, int tenantKeyId, Pool pool) {
        this(config, hasher, journal, tenantKeyId, Objects.requireNonNull(pool, "pool"), DEFAULT_QUEUE_HIGH_WATER);
    }

    /** Test seam: a small high-water mark makes the pause / resume of the source deterministic. */
    TieredBlobStore(Config config, Hasher hasher, Journal journal, int tenantKeyId, long queueHighWater) {
        this(config, hasher, journal, tenantKeyId, null, queueHighWater);
    }

    private TieredBlobStore(Config config, Hasher hasher, Journal journal, int tenantKeyId, Pool pool, long queueHighWater) {
        this.config = Objects.requireNonNull(config, "config");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.journal = Objects.requireNonNull(journal, "journal");
        if (queueHighWater <= 1) throw new IllegalArgumentException("queueHighWater must be above 1, was " + queueHighWater);
        this.tenant = tenantKeyId;
        this.node = journal.node();
        this.memoryTier = Math.min(config.memoryTierBytes(), journal.recordMax());
        this.budget = new MemoryBudget(config.memoryBudgetBytes());
        this.queueHighWater = queueHighWater;
        this.pool = pool;
        this.bodies = pool == null ? null : new PgBodies(pool, PgBodies.MAX_BOUND);
        this.index = new LocationIndex(config.indexEntries());
    }

    /** The most bytes any streaming write has queued ahead of its spill since this store was created: back-pressure keeps it near the high-water mark plus one chunk. */
    long peakQueuedBytes() { return peakQueued; }

    /**
     * Test-only: runs on the spill thread once per poll of its queue that took a chunk or the end, after the monitor block that
     * decides on the resume and before the resume it may issue, the final poll included — not on the poll that ends the spill on a
     * recorded failure; {@code null} (the default) = none.
     */
    void afterSinkDecision(Runnable hook) { this.afterSinkDecision = hook; }

    @Override public long treeLimit() { return config.treeLimit(); }

    /** Where a body is held locally (memory, spill), or empty when none is — a durable body lives in the journal only. */
    public Optional<Location> location(BlobKey key) { return Optional.ofNullable(objects.get(key)).map(Stored::location); }

    /** Bytes of bodies currently held in the memory tier. */
    public long memoryInUse() { return budget.inUse(); }

    /** The refs of the saves open now (CORE-3 item 6): reserved or forced, their commit or rollback not yet acknowledged. */
    public List<JournalRef> openSaves() {
        List<JournalRef> open = new ArrayList<>();
        for (Save s : saves) open.add(s.ref);
        return Collections.unmodifiableList(open);
    }

    /** A claim of this node's work names {@code key}'s body at {@code ref} on {@code owner}: indexed when that is this node. */
    public void located(BlobKey key, String owner, JournalRef ref) {
        if (node.equals(owner)) index.put(key, ref);
    }

    /** The work item of {@code key}'s body completed: the hot path reads it no more, its index entry goes. */
    public void forget(BlobKey key) {
        index.remove(key);
    }

    @Override
    public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
        Objects.requireNonNull(key, "key");
        long size = whole.length();
        if (size > maxSize) return Future.failedFuture(new BodyTooLargeException(key, size, maxSize));
        if (size <= memoryTier && budget.tryReserve(size)) {
            local(key, new Stored(Location.MEMORY, whole.copy(), null, size));
            return Future.succeededFuture(Body.of(this, key, hasher.hashOf(whole), size));
        }
        return blocking(() -> {
            Journal.Spill spill = spilled(key, new ByteArrayInputStream(whole.getBytes()), maxSize);
            local(key, new Stored(Location.SPILL, null, spill, size));
            return Body.of(this, key, spill.hash(), size);
        });
    }

    @Override
    public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
        Objects.requireNonNull(key, "key");
        StreamingWrite w = new StreamingWrite(key, in, maxSize);
        w.start();
        return w.result.future();
    }

    /**
     * Spills {@code body} under {@code key} on this thread. A tenant key that cannot encrypt (or a name the record format refuses)
     * is the write's {@link IOException}: the spill could not be written.
     */
    private Journal.Spill spilled(BlobKey key, InputStream body, long maxSize) throws IOException {
        try {
            return journal.spill(key, tenant, body, Math.min(maxSize, Journal.SPILL_MAX), hasher);
        } catch (IllegalArgumentException refused) {
            throw new IOException("the spill of " + key + " was refused: the journal's tenant key " + tenant
                    + " (wrapped under the master key) cannot encrypt it, or its names are too long", refused);
        }
    }

    /**
     * {@inheritDoc} Lazy: the local copy, the index, then — on the stream's virtual thread when it is first read — Postgres (see
     * the class comment); nothing stored fails the stream with a {@link BlobNotFoundException} before any byte.
     */
    @Override
    public ReadStream<Buffer> open(BlobKey key) {
        ReadStream<Buffer> in = stream(key);
        if (in == null) throw noObject(key);
        return in;
    }

    /** The key's bytes: the local copy, the indexed ref, else its rows in Postgres; null without Postgres (nothing else can hold it). */
    private ReadStream<Buffer> stream(BlobKey key) {
        Stored s = objects.get(key);
        if (s != null) return s.location() == Location.MEMORY ? new BufferReadStream(s.bytes) : new InputStreamReadStream(s::stream);
        JournalRef ref = index.get(key);
        if (ref != null) return new InputStreamReadStream(() -> journal.stream(ref, key));
        return pool == null ? null : new InputStreamReadStream(() -> stored(key));
    }

    @Override
    public Future<Buffer> read(BlobKey key) {
        Stored s = objects.get(key);
        if (s != null && s.location() == Location.MEMORY) return Future.succeededFuture(s.bytes.copy());
        ReadStream<Buffer> in = stream(key);
        return in == null ? Future.failedFuture(noObject(key)) : collect(in);
    }

    /**
     * {@inheritDoc} Reads no bytes (D-096): a local copy exists; otherwise Postgres answers — the key's ref (any node's) with its
     * segment row, then its message's work row (an ingress key), then its pre-V12 {@code bodies} row, asked by primary key —
     * never the location index, which serves {@link #open} on the hot path only and may outlive the rows (CORE-4c2 r1); without
     * Postgres nothing else exists.
     */
    @Override
    public Future<Boolean> exists(BlobKey key) {
        if (objects.containsKey(key)) return Future.succeededFuture(Boolean.TRUE);   // a local copy, in flight; never the index (CORE-4c2 r1)
        if (pool == null) return Future.succeededFuture(Boolean.FALSE);
        return find(key).compose(found -> found.isEmpty() ? bodies.exists(pool, key)
                : found.get().lost() ? Future.failedFuture(new BodyLostException(key)) : Future.succeededFuture(found.get().live()));
    }

    /** A durable body the index misses, on the stream's virtual thread: by its rows in Postgres; none is a {@link BlobNotFoundException}. */
    private InputStream stored(BlobKey key) throws IOException {
        Optional<Found> found = find(key).await();
        if (found.isEmpty()) {
            Optional<Buffer> row = bodies.select(key).await();
            if (row.isEmpty()) throw noObject(key);
            return new ByteArrayInputStream(row.get().getBytes());
        }
        return journal.stream(readable(key, found.get()), key);
    }

    /**
     * {@code found}'s ref when this node can read it: a ref an accepted loss gave up is lost (whether its segment row is there or
     * not), one whose segment row is gone is removed, another node's is elsewhere.
     */
    private JournalRef readable(BlobKey key, Found found) {
        if (found.lost()) throw new BodyLostException(key);
        if (!found.live()) throw noObject(key);
        if (!node.equals(found.node())) throw new RemoteBodyException(key, found.node(), node);
        return found.ref();
    }

    /** A ref Postgres names for a key, whether its segment row is there, and whether it was given up. */
    private record Found(String node, JournalRef ref, boolean live, boolean lost) { }

    private Future<Optional<Found>> find(BlobKey key) {
        return pool.preparedQuery(FIND).execute(Tuple.of(key.messageId(), key.step(), key.part(), ingress(key))).map(rows -> {
            for (Row r : rows) return Optional.of(new Found(r.getString(1), new JournalRef(r.getLong(2), r.getLong(3), r.getLong(4)), r.getBoolean(5), r.getBoolean(6)));
            return Optional.empty();
        });
    }

    /** An ingress key — step 0's body — is the one a work row's ref names (the item's input). */
    private static boolean ingress(BlobKey key) {
        return key.equals(BlobKey.body(key.messageId(), 0));
    }

    /**
     * Makes a body durable (D-061, CORE-3 item 7): forced in the journal, then its {@code body_refs} row committed (see the class
     * comment). Without Postgres every key is refused with an {@link IllegalStateException}. A local body above the durable bound
     * fails with {@link BodyTooLargeException} and stays where it is; one whose key's row names other bytes fails with an
     * {@link IllegalStateException} naming the key and stays local; once the row names these bytes the local copy goes before the
     * future completes. A key held nowhere locally is done when its source row and that row's segment row are there (or its
     * pre-V12 row), else refused with a {@link BlobNotFoundException} — with a {@link BodyLostException} when an accepted loss
     * gave its source up ({@code lost_at}, CORE-4c3).
     */
    @Override
    public Future<Void> persist(BlobKey key) {
        if (pool == null) return Future.failedFuture(new IllegalStateException("this blob store has no durable tier: " + key + " cannot be persisted"));
        Stored s = objects.get(key);
        if (s == null) {
            return pool.preparedQuery(REFERENCE).execute(Tuple.of(key.messageId(), key.step(), key.part(), ingress(key)))
                    .compose(rows -> {
                        Row r = rows.iterator().next();
                        if (r.getLong(0) > 0) return Future.<Void>succeededFuture();
                        return Future.failedFuture(r.getBoolean(1) ? new BodyLostException(key) : noObject(key));
                    });
        }
        if (s.size() > config.durableMaxBytes()) return Future.failedFuture(new BodyTooLargeException(key, s.size(), config.durableMaxBytes()));
        return blocking(() -> {
            Save save = durably(key, s, Promise.promise().future());
            try {
                keep(pool, key, s, save.ref).await();
            } finally {
                saves.remove(save);
            }
            index.remove(key);   // the row names the durable bytes now: a stale index entry must not answer for them
            release(key, s);
            return null;
        });
    }

    /**
     * The intake's persist (CORE-3 item 6): a local body forced in the journal — a memory body appended as a record, a spill persisted —
     * in the open-save set from its reservation; its rows are the caller's ({@link Durable}). A key held nowhere locally is durable
     * already: its {@link Durable} has no ref. Fails as {@link #persist} does, nothing reserved after a failure.
     */
    public Future<Durable> forced(BlobKey key) {
        return forced(key, Promise.promise().future());
    }

    /**
     * {@link #forced(BlobKey)} for a save whose answer is {@code abandoned} (CORE-3 item 6): a memory body's append that waits for
     * room under {@code FLOTTILA_JOURNAL_PENDING_BYTES} ends when it completes — retryable, nothing reserved, the body still local
     * — not only at {@code FLOTTILA_JOURNAL_APPEND_WAIT}.
     */
    public Future<Durable> forced(BlobKey key, Future<?> abandoned) {
        Objects.requireNonNull(abandoned, "abandoned");
        Stored s = objects.get(key);
        if (s == null) return Future.succeededFuture(new Durable(key, null, null));
        if (s.size() > config.durableMaxBytes()) return Future.failedFuture(new BodyTooLargeException(key, s.size(), config.durableMaxBytes()));
        return blocking(() -> new Durable(key, s, durably(key, s, abandoned)));
    }

    /**
     * {@code s} forced in the journal, on this (virtual) thread: an open save from its reservation on, out of the set again if it
     * fails; an append's wait for room ends when {@code abandoned} completes.
     */
    private Save durably(BlobKey key, Stored s, Future<?> abandoned) throws IOException {
        if (s.location() == Location.MEMORY) {
            Journal.Appended a = journal.append(new JournalRecord(key.messageId(), key.step(), key.part(), tenant, s.bytes.getBytes()), abandoned);
            Save save = opened(a.ref());
            try {
                a.forced().await();
            } catch (Throwable t) {   // the force's failure, whatever await throws
                saves.remove(save);
                throw t;
            }
            return save;
        }
        Save save = opened(s.spill.ref());
        try {
            s.spill.persist();
        } catch (IOException | RuntimeException | Error e) {
            saves.remove(save);
            throw e;
        }
        return save;
    }

    private Save opened(JournalRef ref) {
        Save save = new Save(ref);
        saves.add(save);
        return save;
    }

    /**
     * Keeps {@code key}'s ref to the record at {@code ref} on {@code client} (FIX-35): inserted, or replacing a row whose segment row
     * is gone; a row naming another live record of this node is compared with the local bytes {@code s} by reading that record
     * back (on a virtual thread): other bytes fail with {@link IllegalStateException} and change nothing. A row on another node
     * cannot be read here: {@link RemoteBodyException}.
     */
    private Future<Void> keep(SqlClient client, BlobKey key, Stored s, JournalRef ref) {
        return client.preparedQuery(KEEP).execute(Tuple.of(key.messageId(), key.step(), key.part(), node, ref.segment(), ref.position(), ref.length()))
                .compose(rows -> {
                    Row r = rows.iterator().next();
                    String owner = r.getString(0);
                    if (!node.equals(owner)) return Future.failedFuture(new RemoteBodyException(key, owner, node));
                    JournalRef named = new JournalRef(r.getLong(1), r.getLong(2), r.getLong(3));
                    if (named.equals(ref)) return Future.succeededFuture();
                    return blocking(() -> {
                        try (InputStream stored = journal.stream(named, key); InputStream mine = s.stream()) {
                            if (sameBytes(stored, mine)) return null;
                        }
                        throw new IllegalStateException("the durable body under " + key + " holds other bytes than these " + s.size()
                                + ": a key's durable bytes never change (D-061, FIX-35); nothing was persisted and the body stays local");
                    });
                });
    }

    /** Whether {@code a} and {@code b} hold the same bytes, compared {@value #COMPARE_BLOCK} bytes at a time. */
    private static boolean sameBytes(InputStream a, InputStream b) throws IOException {
        byte[] x = new byte[COMPARE_BLOCK];
        byte[] y = new byte[COMPARE_BLOCK];
        while (true) {
            int n = a.readNBytes(x, 0, COMPARE_BLOCK);
            int m = b.readNBytes(y, 0, COMPARE_BLOCK);
            if (n != m || !java.util.Arrays.equals(x, 0, n, y, 0, m)) return false;
            if (n < COMPARE_BLOCK) return true;
        }
    }

    /**
     * The local copy of a durable body goes, when the index still holds {@code s} — otherwise it was evicted, written again or
     * released by another persist meanwhile, and its budget is not this persist's. A persisted spill is its segment now: nothing
     * to unlink.
     */
    private void release(BlobKey key, Stored s) {
        if (objects.remove(key, s) && s.location() == Location.MEMORY) budget.release(s.size());
    }

    /**
     * Puts a write's local copy in place. A spill it replaces is given up on a virtual thread (its file unlinked; a failure is a
     * WARNING). Nothing else is freed here: a replaced memory entry's budget stays reserved (FIX-36).
     */
    private void local(BlobKey key, Stored s) {
        Stored replaced = objects.put(key, s);
        if (replaced != null && replaced.location() == Location.SPILL) {
            Thread.ofVirtual().name("flottila-blob-unlink").start(() -> {
                try {
                    replaced.spill.abandon();
                } catch (IOException left) {
                    LOG.log(System.Logger.Level.WARNING, "the replaced spill of " + key + " was not unlinked", left);
                }
            });
        }
    }

    private static BlobNotFoundException noObject(BlobKey key) { return new BlobNotFoundException(key); }

    /** Runs {@code work} on a virtual thread — this one when it is one — and completes with what it returns or throws. */
    private static <T> Future<T> blocking(Callable<T> work) {
        Promise<T> p = Promise.promise();
        Runnable run = () -> {
            try {
                p.complete(work.call());
            } catch (Throwable t) {
                p.fail(t);
            }
        };
        if (Thread.currentThread().isVirtual()) run.run();
        else Thread.ofVirtual().name("flottila-blob-persist").start(run);
        return p.future();
    }

    /** Every byte of {@code stream}, in order. */
    private static Future<Buffer> collect(ReadStream<Buffer> stream) {
        Promise<Buffer> p = Promise.promise();
        Buffer all = Buffer.buffer();
        stream.exceptionHandler(p::tryFail);
        stream.endHandler(v -> p.tryComplete(all));
        stream.handler(all::appendBuffer);
        return p.future();
    }

    /**
     * Forgets a body's local copy and frees what it held (budget, spill file); quiet for an unknown key — a durable body's refs
     * stay, only retention removes them (P2-07). Not for the hot path.
     */
    public Future<Void> evict(BlobKey key) {
        Stored s = objects.remove(key);
        if (s == null) return Future.succeededFuture();
        if (s.location() == Location.MEMORY) {
            budget.release(s.size());
            return Future.succeededFuture();
        }
        Promise<Void> p = Promise.promise();
        Thread.ofVirtual().name("flottila-blob-evict").start(() -> {
            try {
                s.spill.abandon();
                p.complete();
            } catch (Throwable t) {
                p.fail(t);
            }
        });
        return p.future();
    }

    /** One streamed write: memory first; after the spill a virtual thread spills it into the journal from a bounded queue. */
    private final class StreamingWrite {
        private final BlobKey key;
        private final ReadStream<Buffer> in;
        private final long maxSize;
        final Promise<Body> result = Promise.promise();
        private Buffer collected = Buffer.buffer();
        private long reserved;
        private long seen;
        // after the spill (all guarded by this):
        private boolean spilled;
        private final ArrayDeque<Buffer> queue = new ArrayDeque<>();
        private long queued;
        private boolean ended;
        private boolean sourcePaused;
        private boolean attached;      // start() has attached its handlers: from then on the sink thread may resume the source itself
        private boolean resumeOwed = true;   // a resume is owed to the source until start() or a re-check issues it: for the handover (the stream may arrive paused) and after every pause of ours; read by start() only, so meaningful until the attach
        private boolean pausing;   // true while a pause of ours is being entered — from enqueue marking the source paused, or start()'s reconcile, until that pause's re-check has run; the sink leaves a pause it decides for while it is being entered to that re-check
        private Thread handingOver;   // the thread whose hand-over resume (start()'s, issued after the monitor) is in flight; null = none. A pause issued meanwhile on another thread may be undone by it; one on this thread is inside the resume (a synchronous source) and so after it
        private boolean pausedDuringHandover;   // a pause of ours issued on another thread while the hand-over resume was in flight — the resume may have undone it: the hand-over thread issues it once more after its resume (start()), or the pending re-check does (again)
        private boolean resumeDeferred;   // the sink decided a resume while the hand-over resume was in flight and left it to the hand-over thread: after its resume, issued plainly when no pause of ours was marked; otherwise the marked pause's re-check decides it — of start()'s re-issued pause (repause), or the pending one (again)
        private Throwable failure;

        StreamingWrite(BlobKey key, ReadStream<Buffer> in, long maxSize) {
            this.key = key;
            this.in = in;
            this.maxSize = maxSize;
        }

        void start() {
            in.exceptionHandler(this::fail);
            in.endHandler(v -> end());
            in.handler(this::chunk);
            // attaching may already have delivered, paused or failed: a synchronous source (BufferReadStream for a memory-tier body, the
            // testkit's streams) runs inside handler(). Until this moment the sink thread never resumes the source, so exactly one party
            // decides here, under the monitor — except a pause of ours still being entered, which its own re-check resumes; afterwards
            // the sink does (BlobStore contract: a stream handed over paused flows once the handlers are attached, and a pause or
            // failure of our own is never undone). The hand-over resume is issued after the monitor, so what another thread does or wants
            // meanwhile is reconciled after it (handingOver): a pause of ours it may undo (pausedDuringHandover), a resume the sink must
            // not duplicate (resumeDeferred).
            boolean resume;
            synchronized (this) {
                attached = true;
                resume = resumeOwed && !sourcePaused && !pausing && failure == null && !result.future().isComplete();
                if (resume) { resumeOwed = false; handingOver = Thread.currentThread(); }
            }
            if (!resume) return;
            in.resume();
            boolean repause, plainResume;
            synchronized (this) {
                handingOver = null;
                // what another thread did or wanted during our resume: a pause of ours it issued (our resume may have undone it) is issued once
                // more and its re-check decides the resume; a resume the sink left to us with no such pause finds the source paused by a pause of
                // ours our resume did not undo, sourcePaused cleared — issued plainly. A re-check still pending (pausing) reconciles itself (again).
                repause = pausedDuringHandover && !pausing;
                plainResume = !repause && !pausing && resumeDeferred && failure == null;
                if (repause || plainResume) { pausedDuringHandover = false; resumeDeferred = false; }
                if (repause) pausing = true;
            }
            if (repause) pauseAndReCheck();
            else if (plainResume) in.resume();
            if (failed()) in.pause();   // a failure that landed in the window: never consume past it (a second pause cannot hang, nothing resumes after a failure)
        }

        private synchronized boolean over() { return failure != null || result.future().isComplete(); }

        /** Every failure (the source's in fail(), the sink's in drain()) is recorded here before the future fails; the first recorded is the cause. A sink failure after the source's rides as suppressed; a source failure after the sink's is dropped (fail() returns on over(); the future fails with the sink's after the sink's abort()). */
        private synchronized boolean failed() { return failure != null; }

        private void chunk(Buffer c) {
            if (over()) return;
            seen += c.length();
            if (seen > maxSize) {
                fail(new BodyTooLargeException(key, seen, maxSize));
                return;
            }
            if (!spilled) {
                if (seen <= memoryTier && budget.tryReserve(c.length())) {
                    reserved += c.length();
                    collected.appendBuffer(c);
                    return;
                }
                spill();
            }
            enqueue(c);
        }

        /** Hands the prefix seen so far to the sink thread; no I/O happens on the source's thread. */
        private void spill() {
            budget.release(reserved);
            reserved = 0;
            Buffer prefix = collected;
            collected = null;
            spilled = true;
            if (prefix.length() > 0) enqueue(prefix);
            Thread.ofVirtual().name("flottila-blob-write").start(this::drain);
        }

        private void enqueue(Buffer c) {
            boolean pauseSource;
            synchronized (this) {
                queue.addLast(c);
                queued += c.length();
                pauseSource = queued >= queueHighWater && !sourcePaused;
                if (pauseSource) {
                    sourcePaused = true;
                    resumeOwed = true;
                    // one pausing at a time: a pause already being entered (start()'s re-issue) takes effect for this one too, and its
                    // re-check sees sourcePaused and leaves the resume to the sink — a second re-check would resume a second time
                    if (pausing) pauseSource = false;
                    else {
                        pausing = true;
                        if (handingOver != null && handingOver != Thread.currentThread()) pausedDuringHandover = true;
                    }
                }
                if (queued > peakQueued) peakQueued = queued;
                notifyAll();
            }
            if (pauseSource) pauseAndReCheck();
        }

        /** Pauses the source for a pause already marked under the monitor and re-checks after in.pause() returned: the pause is ours to resume when the sink's decision (or, before the attach, nobody) overtook it; a pause issued on another thread while the hand-over resume was in flight (pausedDuringHandover) or a resume the sink deferred meanwhile (resumeDeferred) is reconciled after it: the pause once more, this re-check decides. */
        private void pauseAndReCheck() {
            while (true) {
                in.pause();   // outside the monitor: the source may lock around its own dispatch
                boolean again, resume = false;
                synchronized (this) {
                    again = (pausedDuringHandover || resumeDeferred) && handingOver == null;   // the hand-over resume completed while this pause was being entered and may have undone it
                    if (again) { pausedDuringHandover = false; resumeDeferred = false; }
                    else {
                        pausing = false;
                        resume = !sourcePaused && failure == null;   // ours to resume — never after a failure
                        if (resume && handingOver != null && handingOver != Thread.currentThread()) resume = false;   // the hand-over resume is in flight on another thread: ours now would be a second one for this pause; the hand-over thread reconciles after its resume (this pause is marked)
                        if (resume) resumeOwed = false;
                    }
                }
                if (again) continue;
                if (resume) {
                    in.resume();
                    if (failed()) in.pause();   // a failure that landed between the decision and the resume: never consume past it
                }
                return;
            }
        }

        private void end() {
            if (over()) return;
            if (!spilled) {
                local(key, new Stored(Location.MEMORY, collected, null, seen));
                result.complete(Body.of(TieredBlobStore.this, key, hasher.hashOf(collected), seen));
                return;
            }
            synchronized (this) {
                ended = true;
                notifyAll();
            }
        }

        /** Records the failure; before the spill the promise fails at once, after it the spill thread gives its file up first and then fails it. */
        private void fail(Throwable t) {
            synchronized (this) {
                if (over()) return;
                failure = t;
                notifyAll();
            }
            in.pause();
            budget.release(reserved);
            reserved = 0;
            collected = null;
            if (!spilled) result.tryFail(t);
        }

        /**
         * The spill's virtual thread: spills the queued chunks in order into the journal ({@link Drained}), resuming the source as
         * the queue drains, and indexes the spill at the end; on any failure the spill has left no file before the promise fails.
         */
        private void drain() {
            Throwable problem;
            try {
                Journal.Spill spill = spilled(key, new Drained(), maxSize);
                local(key, new Stored(Location.SPILL, null, spill, seen));
                result.complete(Body.of(TieredBlobStore.this, key, spill.hash(), seen));
                return;
            } catch (Aborted recorded) {
                synchronized (this) {
                    problem = failure;   // the source's failure, recorded before the poll that saw it
                }
            } catch (Throwable t) {
                // the spill's failure takes the source's path: recorded under the monitor first, then the pause, then the promise,
                // so every party that later looks (start()'s re-check, chunk(), fail()) sees the failure before the future fails; the first
                // failure recorded under the monitor (the source's in fail() or the spill's here) is the cause. A spill failure after the
                // source's rides as suppressed; a source failure after the spill's is dropped (fail() returns on over())
                synchronized (this) {
                    if (failure == null) failure = t;
                    else if (t != failure) failure.addSuppressed(t);   // a source failure racing in here has already stopped the source
                    problem = failure;
                    notifyAll();
                }
                in.pause();
            }
            result.tryFail(problem);
        }

        /** The queue as the spill reads it: the next chunk once there is one, the end once the source ended. */
        private final class Drained extends InputStream {
            private Buffer current;
            private int at;

            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                while (current == null || at == current.length()) {
                    current = poll();
                    at = 0;
                    if (current == null) return -1;
                }
                int n = Math.min(len, current.length() - at);
                current.getBytes(at, at + n, b, off);
                at += n;
                return n;
            }
        }

        /**
         * The next queued chunk, null once the source ended; waits while the queue is empty. Resumes the source once the queue fell
         * below half the high-water mark; a recorded failure ends the spill ({@link Aborted}).
         */
        private Buffer poll() throws IOException {
            Buffer next;
            boolean resumeSource = false;
            synchronized (this) {
                try {
                    while (queue.isEmpty() && !ended && failure == null) wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("the spill of " + key + " was interrupted");
                }
                if (failure != null) throw new Aborted();
                next = queue.pollFirst();   // null only when the queue is empty and the source has ended
                if (next != null) {
                    queued -= next.length();
                    if (sourcePaused && queued < queueHighWater / 2) {
                        sourcePaused = false;
                        // after the attach the spill resumes a pause whose re-check has run — unless the hand-over resume is in flight: then the hand-over
                        // thread reconciles (a resume of ours now would be a second one for this pause and could undo the source's next); a pause still
                        // being entered is resumed by its own re-check (which sees sourcePaused cleared); before the attach the re-check or start() issues the one resume
                        if (attached && !pausing) {
                            if (handingOver == null) resumeSource = true;
                            else resumeDeferred = true;   // the hand-over resume is in flight: the hand-over thread issues this resume after it
                        }
                    }
                }
            }
            Runnable hook = afterSinkDecision;
            if (hook != null) hook.run();
            if (resumeSource) {
                in.resume();
                if (failed()) in.pause();   // the source's fail() landed between the decision and the resume: never consume past it
            }
            return next;
        }
    }

    /** A failure recorded while the spill waited for its queue: it ends the spill, which gives its file up. */
    private static final class Aborted extends IOException {
        Aborted() {
            super("the streamed write failed: its spill is given up");
        }
    }
}
