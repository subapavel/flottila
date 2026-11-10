package io.flottila.runtime.store;

import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Timer;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.batch.OverloadedException;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.Intake;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.journal.JournalRef;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.queue.PgWorkQueue;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The node's {@link Intake} (D-082 restated by D-105, CORE-3 item 6): no row naming a body commits before its record is forced.
 * The ingress body is made durable in the node's journal first ({@link TieredBlobStore#forced}: appended, or its spill
 * persisted, and forced) — on the caller's thread, before the save joins a group, so no statement of it is sent while the record
 * is not forced and a force that fails saves nothing. A body durable already (no local copy) adds no record and no kept ref: its
 * item copies the key's kept ref ({@link PgWorkQueue#enqueue(SqlClient, String, String, int, String, io.flottila.api.BlobKey)}).
 * A body of another store is persisted there first, its item naming no ref.
 *
 * <p><b>Groups</b> (CORE-6b, D-109). The save then joins a group through a {@link BatchingWriter}: saves waiting while groups are in
 * flight go together, at most {@link NodeConfig.IntakeGroup#max} of them and {@link NodeConfig.IntakeGroup#bytes} bytes (a
 * member weighs the UTF-8 bytes it binds: ids, flow, keys, its record's headers, props and keys as JSON), a partial group waiting
 * at most the window, at most {@link NodeConfig.IntakeGroup#concurrency} groups in flight; a backlog of {@code max × concurrency
 * × 16} saves full fails the save at once with {@link OverloadedException} (retryable; its body stays local, it is not open). A
 * single save is a group of one ({@link NodeConfig.IntakeGroup#SINGLE}). One Postgres transaction on one connection holds a
 * group: each member's ingress record ({@link PgMessageStore}, outside its batching writer), its work item naming the record's
 * ref ({@link PgWorkQueue}) and — only when its flow keeps its input ({@code keepInput}) — its body's kept ref ({@code body_refs}).
 * Its statements go in turns (D-109), a turn's statements sent together and the next turn sent only once every reply of this one
 * came: BEGIN alone; the claim turn when a member has a dedupe key; the rows — the records as one insert into messages and one
 * into message_keys, then each member's item and kept ref in call order (so one partition's items keep the call order); then,
 * after the {@code abandoned} check, COMMIT alone. A failed statement of a turn fails the turn with its own cause (the first
 * failed in the turn's order, never a later statement's aborted transaction) and the transaction rolls back.
 *
 * <p>{@code abandoned} — the delivery's answer — drops a member without a key whose answer is decided when its group is taken and
 * again once the group's connection came: it fails as abandoned, nothing sent for it. After the rows every member writing rows is
 * asked once more, just before the commit; one complete rolls the group back. Its completion also ends the wait of the body's
 * record for room under {@code FLOTTILA_JOURNAL_PENDING_BYTES} ({@link TieredBlobStore#forced(io.flottila.api.BlobKey, Future)}:
 * retryable, nothing reserved).
 *
 * <p>Outcomes. Once the commit is acknowledged each member writing rows has its ref indexed and its local copy gone, then its save
 * succeeds with its own id. A group that fails before its commit (a statement, a kept ref's check, a member abandoned at the
 * check) answers nothing from its transaction: a group of one fails with the cause; in a larger one each member without a key whose
 * answer is decided fails as abandoned and every other member runs again alone, in a transaction of its own ({@link
 * GroupStats#reruns}), so a poison member never fails its neighbours. A commit the server refuses with an error reply of
 * severity ERROR fails every member with its refusal, each out of the open saves. A commit sent whose reply never came (the
 * connection lost) or came as FATAL or PANIC (the session ended, the commit may have landed) fails every member but leaves the
 * members writing rows open, by the transaction id their items' inserts answered, until {@code pg_xact_status} of that id is known — asked at each {@link #openSaves} — or the node
 * restarts. A save is an open save ({@link #openSaves}) from its record's reservation until its group's commit or rollback is
 * acknowledged, or its force failed. Any failure — of the force, a statement, the commit or the body's own store — fails the
 * save; nothing here throws.
 *
 * <p>A member with a dedupe key (D-084) is claimed in a turn of its own after BEGIN, two statements over every keyed member of the
 * group sorted by (flow, key) — one lock order, so two groups racing for the same keys never deadlock: the keys' rows in {@code
 * processed_ids} — scope {@code flow}, the SHA-256 of the key's UTF-8 bytes, the id of the first member in call order with that
 * key, the ingress snapshot's start — {@code on conflict do nothing}; then the ids the keys' rows name. A row inserted is that
 * member's: it writes its rows and commits with the group, so a group that fails or is rolled back leaves the key free. A key
 * not inserted is taken — by a committed save, by an open one this statement waited for (READ COMMITTED) and that committed, or by
 * an earlier member of the group: the member writes nothing — no savepoint, its neighbours unaffected — {@code abandoned} is not
 * asked for it, and once the group is done (committed, or rolled back when no member writes rows) this store's copy of its body is
 * evicted (its record, forced already, stays unreferenced in the journal; a body of another store keeps what its persist made
 * durable) and its save succeeds with the id the key's row names. A taken key whose row is gone when read (the retention job)
 * fails its save. A group without a key runs no claim turn.
 *
 * @since 0.1
 */
public final class PgIntake implements Intake {

    /** The keys' rows for their first members, unless a scope holds the key already: a member's id returned is its key claimed (D-084). */
    static final String CLAIM_KEY = "insert into processed_ids(scope, key_hash, message_id, created_at) select s, sha256(convert_to(k, 'UTF8')), m, c "
            + "from unnest($1::text[], $2::text[], $3::text[], $4::timestamptz[]) as c(s, k, m, c) on conflict do nothing returning message_id";
    /** The message each key names. */
    static final String FIRST_ID = "select p.scope, c.k, p.message_id from processed_ids p join unnest($1::text[], $2::text[]) as c(s, k) "
            + "on p.scope = c.s and p.key_hash = sha256(convert_to(c.k, 'UTF8'))";
    /** Each transaction id's status: committed, aborted, in progress, or null for one too old to be known. */
    static final String XACT_STATUS = "select x, pg_xact_status(x::xid8) from unnest($1::text[]) x";

    private static final System.Logger LOG = System.getLogger(PgIntake.class.getName());

    /** A group of one never waits for a window: its writer never schedules one. */
    private static final Timer NO_WINDOW = (delay, task) -> { throw new IllegalStateException("a group of one has no window"); };

    /**
     * The intake's groups since it was built, for the tests and the bench (CORE-6b): groups taken (a group of one counts), their
     * members and the bytes they weighed, and the members run again alone after their group failed.
     *
     * @since 0.1
     */
    public record GroupStats(long groups, long members, long bytes, long reruns) {}

    /** A save in a group: what it writes, its body made durable (null: another store's), its weight and its answer. */
    private record Member(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, boolean keepInput,
                          Future<?> abandoned, TieredBlobStore.Durable durable, long weight, Promise<String> answer) {

        /** Without a key and its answer decided: dropped, nothing sent for it (a keyed member still claims, to answer a duplicate). */
        boolean dropped() {
            return dedupeKey == null && abandoned.isComplete();
        }

        /** A member is itself: two saves are two members whatever they carry (and nothing deep is hashed). */
        @Override public boolean equals(Object o) {
            return this == o;
        }

        @Override public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /** A key in its scope, ordered by (flow, key): the claim turn's one lock order. */
    private record Scoped(String flow, String key) implements Comparable<Scoped> {
        @Override public int compareTo(Scoped o) {
            int byFlow = flow.compareTo(o.flow);
            return byFlow != 0 ? byFlow : key.compareTo(o.key);
        }
    }

    /** One group's transaction: its members in call order, what its claim turn found, the transaction id its items answered. */
    private static final class Group {
        final List<Member> members;
        final Map<Member, String> firsts = new HashMap<>();   // a key taken: the id its row names
        final Set<Member> gone = new HashSet<>();             // a key taken by a row that is gone
        final List<Member> writing = new ArrayList<>();       // the members writing rows
        final Map<Member, FlowWorker.Slot> slots = new HashMap<>();   // a member writing rows with a hand-off slot reserved
        final Map<Member, WorkQueue.Item> leased = new ConcurrentHashMap<>();   // a member whose item was written leased
        final AtomicReference<String> xid = new AtomicReference<>();

        Group(List<Member> members) { this.members = members; }
    }

    /** A group that rolled back before its commit, carrying the cause out: nothing is answered from its transaction. */
    private static final class RolledBack extends RuntimeException {
        private RolledBack(Throwable cause) {
            super("rolled back before the commit", cause, false, false);
        }
    }

    /** A commit sent with no outcome known (its reply lost), carried out of the transaction: the members stay open by {@link #xid}. */
    private static final class InDoubt extends RuntimeException {
        private final String xid;

        private InDoubt(String xid, Throwable lost) {
            super("the commit of transaction " + xid + " was sent and its reply lost: its outcome is asked by pg_xact_status", lost, false, false);
            this.xid = xid;
        }
    }

    private final Pool pool;
    private final TieredBlobStore blobs;
    private final PgMessageStore messages;
    private final PgWorkQueue queue;
    private final Workers workers;   // null: no hand-off
    private final BatchingWriter<Member> writer;
    /** The members whose group's commit is in doubt, by transaction id. */
    private final Map<String, List<TieredBlobStore.Durable>> inDoubt = new ConcurrentHashMap<>();
    /** The in-doubt ids whose own lookup failed and was logged, until they settle: guarded by {@link #asking}. */
    private final Set<String> warned = new HashSet<>();
    private final ReentrantLock asking = new ReentrantLock();
    private final LongAdder groups = new LongAdder();
    private final LongAdder members = new LongAdder();
    private final LongAdder bytes = new LongAdder();
    private final LongAdder reruns = new LongAdder();

    /** An intake whose every save is a group of one ({@link NodeConfig.IntakeGroup#SINGLE}), handing nothing off. */
    public PgIntake(Pool pool, TieredBlobStore blobs, PgMessageStore messages, PgWorkQueue queue) {
        this(pool, blobs, messages, queue, null, NO_WINDOW, NodeConfig.IntakeGroup.SINGLE);
    }

    /** An intake whose every save is a group of one, handing its items off to the started workers of {@code workers} (D-121 (a)). */
    public PgIntake(Pool pool, TieredBlobStore blobs, PgMessageStore messages, PgWorkQueue queue, Workers workers) {
        this(pool, blobs, messages, queue, Objects.requireNonNull(workers, "workers"), NO_WINDOW, NodeConfig.IntakeGroup.SINGLE);
    }

    /** An intake grouping its saves as {@code group} says, its window on {@code timer}, handing nothing off. */
    public PgIntake(Pool pool, TieredBlobStore blobs, PgMessageStore messages, PgWorkQueue queue, Timer timer, NodeConfig.IntakeGroup group) {
        this(pool, blobs, messages, queue, null, timer, group);
    }

    /**
     * An intake grouping its saves as {@code group} says, its window on {@code timer}, handing its items off to the started workers
     * of {@code workers} (null: none), leased to the node of {@code queue} — a queue of no node hands nothing off.
     */
    public PgIntake(Pool pool, TieredBlobStore blobs, PgMessageStore messages, PgWorkQueue queue, Workers workers, Timer timer, NodeConfig.IntakeGroup group) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.workers = queue.node().isPresent() ? workers : null;
        this.writer = new BatchingWriter<>(this::run, timer, new BatchingWriter.Options(group.max(), group.window(), group.concurrency(),
                group.max() * group.concurrency() * 16), Member::weight, group.bytes());
    }

    /** The groups' totals since this intake was built. */
    public GroupStats groupStats() {
        return new GroupStats(groups.sum(), members.sum(), bytes.sum(), reruns.sum());
    }

    /**
     * The refs of the saves open now (CORE-3 item 6): the CORE-4d sweeper keeps their segments. A group whose commit is in doubt is
     * asked first ({@code pg_xact_status}, blocking — on a virtual thread): committed, each of its members is done as a committed
     * save is (its ref indexed, its local copy gone); aborted or too old to be known, as a rolled-back one (rows it may have pin by
     * themselves); in progress, they stay open. Every in-doubt id is asked in one statement; when it fails, each id is asked on its
     * own (FIX-145) and an id whose own lookup fails stays in doubt — never taken as an abort or a commit — with one WARNING the
     * first time (its id and the failure), none again until it settled. A lookup failing never fails this call: the saves still
     * open are answered, every in-doubt one among them when no lookup could run.
     */
    public Collection<JournalRef> openSaves() {
        if (!inDoubt.isEmpty()) settle();
        return blobs.openSaves();
    }

    private void settle() {
        asking.lock();
        try {
            String[] xids = inDoubt.keySet().toArray(String[]::new);
            AsyncResult<RowSet<Row>> batched = status(xids);
            if (batched.succeeded()) {
                settle(batched.result());
                return;
            }
            for (String xid : xids) {   // the batched lookup failed: one id it cannot answer pins only its own saves
                AsyncResult<RowSet<Row>> own = status(xid);
                if (own.succeeded()) settle(own.result());
                else if (warned.add(xid)) {
                    LOG.log(System.Logger.Level.WARNING, "the commit outcome of transaction " + xid + " cannot be asked (pg_xact_status failed: "
                            + own.cause() + "): its saves stay open and their journal segments kept until a lookup answers", own.cause());
                }
            }
        } finally {
            asking.unlock();
        }
    }

    /** The statuses of {@code xids} — the failure as an outcome, never thrown (a throw while sending included). */
    private AsyncResult<RowSet<Row>> status(String... xids) {
        return Future.<Void>succeededFuture().compose(start -> pool.preparedQuery(XACT_STATUS).execute(Tuple.tuple().addArrayOfString(xids)))
                .transform((AsyncResult<RowSet<Row>> asked) -> Future.succeededFuture(asked)).await();
    }

    /** Each id {@code statuses} answers done, its saves committed or rolled back, unless in progress; its warned mark dropped. */
    private void settle(RowSet<Row> statuses) {
        for (Row r : statuses) {
            String status = r.getString(1);
            if ("in progress".equals(status)) continue;
            String xid = r.getString(0);
            warned.remove(xid);
            for (TieredBlobStore.Durable save : inDoubt.remove(xid)) {
                if ("committed".equals(status)) save.committed();
                else save.rolledBack();
            }
        }
    }

    /**
     * {@code saves} in doubt under {@code xid}, as a commit whose reply was lost leaves the members writing rows ({@link #notCommitted});
     * for the tests, which cannot make Postgres assign an id it refuses to answer for (FIX-145).
     */
    void inDoubt(String xid, List<TieredBlobStore.Durable> saves) {
        inDoubt.put(xid, saves);
    }

    @Override
    public Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, boolean keepInput,
                               Future<?> abandoned) {
        Body body = arrived.body();
        boolean ours = body.store() == blobs;
        // from a completed future on, so a store that throws fails the save instead of escaping it
        return Future.<Void>succeededFuture()
                .compose(start -> ours ? blobs.forced(body.key(), abandoned) : body.store().persist(body.key()).map((TieredBlobStore.Durable) null))
                .compose(durable -> {
                    long weight = PgMessageStore.weight(ingress) + PgMessageStore.utf8(arrived.id()) + PgMessageStore.utf8(flow)
                            + PgMessageStore.utf8(partitionKey) + PgMessageStore.utf8(dedupeKey);
                    Member m = new Member(arrived, ingress, flow, partitionKey, dedupeKey, keepInput, abandoned, durable, weight, Promise.promise());
                    writer.save(m).onFailure(refused -> fail(m, refused));   // a full backlog or a window refused: never handed to a group
                    return m.answer().future();
                });
    }

    /**
     * The writer's sink: {@code batch} as a group, answering each member — never failing, never blocking (the window fires on an
     * event loop). A member without a key whose answer is decided is dropped first, nothing asked for it.
     */
    private Future<Void> run(List<Member> batch) {
        groups.increment();
        members.add(batch.size());
        for (Member m : batch) bytes.add(m.weight());
        return group(batch);
    }

    /** {@code taken} as one transaction on a connection of its own, its decided members dropped before and after the connection came. */
    private Future<Void> group(List<Member> taken) {
        List<Member> undropped = undropped(taken);
        if (undropped.isEmpty()) return Future.succeededFuture();
        return pool.getConnection().transform(got -> {
            if (got.failed()) return failed(undropped, got.cause());
            SqlConnection conn = got.result();
            Group g = new Group(undropped(undropped));
            if (g.members.isEmpty()) return conn.close().otherwiseEmpty();
            return Future.<Void>succeededFuture().compose(start -> transaction(conn, g))   // a throw too ends with the close
                    .eventually(conn::close)
                    .transform(outcome -> {   // the commit or the rollback acknowledged: the members answered, out of the open saves first
                        if (outcome.succeeded()) return answered(g);
                        if (outcome.cause() instanceof RolledBack rolledBack) {
                            Future<Void> rerun = failed(g.members, rolledBack.getCause());   // answered (or run again) first
                            cancelSlots(g, false);   // nothing of the group landed; a member run again alone reserves anew
                            return rerun;
                        }
                        notCommitted(g, outcome.cause());
                        return Future.succeededFuture();
                    });
        });
    }

    /** The members not {@link Member#dropped}; each dropped one fails as abandoned, out of the open saves. */
    private List<Member> undropped(List<Member> members) {
        List<Member> kept = new ArrayList<>(members.size());
        for (Member m : members) {
            if (m.dropped()) fail(m, abandoned(m));
            else kept.add(m);
        }
        return kept;
    }

    /**
     * {@code g} in one transaction on {@code conn}: committed when a member writes rows and none is abandoned at the check, rolled
     * back with a success when no member writes rows, else rolled back and failed {@link RolledBack} with the cause. A commit the
     * server refused with an error reply of severity ERROR (a known abort: the session survives it) fails with its own cause; one
     * whose reply never came, or answered FATAL or PANIC (the session ends; the commit may have landed), fails with {@link InDoubt}
     * once the transaction id is known (a member naming a record), else with its own failure.
     */
    private Future<Void> transaction(SqlConnection conn, Group g) {
        return conn.begin().transform(began -> {
            if (began.failed()) return Future.<Void>failedFuture(new RolledBack(began.cause()));
            return Future.<Void>succeededFuture().compose(start -> claim(conn, g))
                    .compose(claimed -> writes(conn, g))
                    .transform(done -> {
                        if (done.failed()) return began.result().rollback().transform(rolled -> Future.<Void>failedFuture(new RolledBack(done.cause())));
                        if (!done.result()) return began.result().rollback().otherwiseEmpty();   // nothing written: the duplicates answer
                        return began.result().commit().recover(lost -> Future.failedFuture(refused(lost) || g.xid.get() == null ? lost   // refused: a known abort
                                : new InDoubt(g.xid.get(), lost)));
                    });
        });
    }

    /**
     * The rows turn over the members writing rows — false, nothing sent, when none does: their records' inserts, then each one's item
     * and — {@code keepInput}, a body of this store — kept ref, each sent before any reply; then the abandoned check on each. Fails
     * with the first failed statement's cause in that order, or with the first member found abandoned.
     */
    private Future<Boolean> writes(SqlClient tx, Group g) {
        for (Member m : g.members) if (!g.firsts.containsKey(m) && !g.gone.contains(m)) g.writing.add(m);
        if (g.writing.isEmpty()) return Future.succeededFuture(false);
        List<Future<?>> replies = new ArrayList<>(2 + 2 * g.writing.size());
        List<Map.Entry<String, String>> keys = new ArrayList<>();
        for (Member m : g.writing) {
            if (m.partitionKey() != null) keys.add(Map.entry(m.flow(), m.partitionKey()));
            if (workers != null) workers.reserve(m.flow()).ifPresent(slot -> g.slots.put(m, slot));   // just before its insert (D-121 A1)
        }
        replies.add(queue.lockKeys(tx, keys));   // the keys' locks before any keyed insert reads its key (D-121 A3); none without a key
        replies.add(messages.insert(tx, g.writing.stream().map(Member::ingress).toList()));
        for (Member m : g.writing) {
            replies.add(enqueue(tx, m, g));
            if (m.keepInput() && m.durable() != null) replies.add(m.durable().keepIn(tx));
        }
        return Future.join(replies).compose(written -> {
            for (Member m : g.writing) if (m.abandoned().isComplete()) return Future.failedFuture(abandoned(m));
            return Future.succeededFuture(true);
        });
    }

    /**
     * The item: naming the record forced for it (its insert answering the transaction's id, into {@code g.xid}); taking the kept ref of
     * a body durable already; naming nothing for another store's body. With a slot reserved for {@code m}, written leased to the
     * queue's node for the slot's lease when it heads its key, into {@code g.leased}.
     */
    private Future<Void> enqueue(SqlClient tx, Member m, Group g) {
        String id = m.arrived().id();
        TieredBlobStore.Durable durable = m.durable();
        FlowWorker.Slot slot = g.slots.get(m);
        String holder = slot == null ? null : queue.node().orElseThrow();
        java.time.Duration lease = slot == null ? null : slot.lease();
        if (durable == null) return queue.enqueue(tx, id, m.flow(), 1, m.partitionKey(), holder, lease).map(item -> leased(g, m, item));
        if (durable.ref() == null) {
            return queue.enqueue(tx, id, m.flow(), 1, m.partitionKey(), m.arrived().body().key(), holder, lease).map(item -> leased(g, m, item));
        }
        return queue.enqueue(tx, id, m.flow(), 1, m.partitionKey(), durable.node(), durable.ref(), holder, lease).map(written -> {
            g.xid.set(written.xid());
            return leased(g, m, written.leased());
        });
    }

    /** {@code m}'s item, if written leased, kept for its slot. */
    private static Void leased(Group g, Member m, java.util.Optional<WorkQueue.Item> item) {
        item.ifPresent(i -> g.leased.put(m, i));
        return null;
    }

    /**
     * Once {@code g} committed and its members answered (D-121 A4): each member's reserved slot runs its item written leased, or is
     * given back (its key busy: the key's holder hands it on) — each call {@link #isolated}; each flow with a member saved without
     * a slot has its worker woken, once.
     */
    private void handOff(Group g) {
        Set<String> spilled = new java.util.LinkedHashSet<>();
        for (Member m : g.writing) {
            FlowWorker.Slot slot = g.slots.get(m);
            WorkQueue.Item item = g.leased.get(m);
            if (slot == null) spilled.add(m.flow());
            else if (item != null) isolated(m, "run", () -> slot.run(item));
            else isolated(m, "give back", () -> slot.cancel(false));
        }
        if (workers != null) spilled.forEach(workers::wake);
    }

    /**
     * Every slot of {@code g} given back, each call {@link #isolated}: its group did not commit; {@code mayHaveLanded}, its commit
     * is in doubt.
     */
    private static void cancelSlots(Group g, boolean mayHaveLanded) {
        g.slots.forEach((m, slot) -> isolated(m, "give back", () -> slot.cancel(mayHaveLanded)));
    }

    /**
     * {@code call} on {@code m}'s slot, after the bookkeeping it sits beside: a throw is logged at WARNING and changes no answer nor
     * skips another slot; an item written leased stays to its lease, claimed at its end.
     */
    private static void isolated(Member m, String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException | Error thrown) {
            LOG.log(System.Logger.Level.WARNING, "the hand-off slot of message " + m.arrived().id() + " of flow " + m.flow() + " failed to "
                    + what + "; an item written leased is claimed at its lease end: " + thrown, thrown);
        }
    }

    /**
     * The claim turn — nothing to do without a keyed member: the keys' rows for the first member in call order of each key, and the
     * read of the ids the keys' rows name, sent together sorted by (flow, key), answered once both replied. A member is claimed when
     * the insert returned its id as its key's first member; any other keyed member's key is taken: into {@code g.firsts} with the
     * id the read found, or {@code g.gone} when it found none.
     */
    private static Future<Void> claim(SqlClient tx, Group g) {
        TreeMap<Scoped, Member> firstOf = new TreeMap<>();
        for (Member m : g.members) if (m.dedupeKey() != null) firstOf.putIfAbsent(new Scoped(m.flow(), m.dedupeKey()), m);
        if (firstOf.isEmpty()) return Future.succeededFuture();
        int n = firstOf.size(), i = 0;
        String[] scopes = new String[n], keys = new String[n], ids = new String[n];
        OffsetDateTime[] created = new OffsetDateTime[n];
        for (Map.Entry<Scoped, Member> e : firstOf.entrySet()) {
            scopes[i] = e.getKey().flow();
            keys[i] = e.getKey().key();
            ids[i] = e.getValue().arrived().id();
            created[i++] = e.getValue().ingress().startedAt().atOffset(ZoneOffset.UTC);
        }
        Future<RowSet<Row>> inserted = tx.preparedQuery(CLAIM_KEY).execute(Tuple.wrap(new Object[] {scopes, keys, ids, created}));
        Future<RowSet<Row>> first = tx.preparedQuery(FIRST_ID).execute(Tuple.wrap(new Object[] {scopes, keys}));
        return Future.join(inserted, first).map(replied -> {
            Set<String> claimed = new HashSet<>();
            for (Row r : inserted.result()) claimed.add(r.getString(0));
            Map<Scoped, String> named = new HashMap<>();
            for (Row r : first.result()) named.put(new Scoped(r.getString(0), r.getString(1)), r.getString(2));
            for (Member m : g.members) {
                if (m.dedupeKey() == null) continue;
                Scoped key = new Scoped(m.flow(), m.dedupeKey());
                if (firstOf.get(key) == m && claimed.contains(m.arrived().id())) continue;
                String id = named.get(key);
                if (id == null) g.gone.add(m);
                else g.firsts.put(m, id);
            }
            return null;
        });
    }

    /**
     * {@code g} done (committed, or rolled back with no member writing rows): each member writing rows committed and answering its
     * own id; each with its key taken out of the open saves, its body evicted, answering the id its key's row names; each whose
     * key's row is gone failed; then the {@linkplain #handOff hand-off}. Completes once every member is answered.
     */
    private Future<Void> answered(Group g) {
        for (Member m : g.writing) if (m.durable() != null) m.durable().committed();   // the ref indexed, the local copy gone: then the runs read it
        List<Future<String>> answers = new ArrayList<>(g.members.size());
        for (Member m : g.members) {
            String first = g.firsts.get(m);
            if (first != null) {
                rolledBack(m);
                blobs.evict(m.arrived().body().key()).map(evicted -> first).onComplete(m.answer()::tryComplete, m.answer()::tryFail);
            } else if (g.gone.contains(m)) {
                fail(m, new IllegalStateException("the dedupe key of " + m.arrived().id() + " is taken in flow " + m.flow()
                        + " by a row that is gone (retention): no message to name; not saved"));
            } else {
                m.answer().tryComplete(m.arrived().id());
            }
            answers.add(m.answer().future());
        }
        handOff(g);   // after the answers: a slot call never decides one
        return Future.join(answers).<Void>mapEmpty().otherwiseEmpty();
    }

    /**
     * {@code group} rolled back before its commit with {@code cause}: a group of one fails with it; in a larger one each member
     * {@link Member#dropped} fails as abandoned and every other runs again alone, one after the other, each in its own transaction.
     */
    private Future<Void> failed(List<Member> group, Throwable cause) {
        if (group.size() == 1) {
            fail(group.getFirst(), cause);
            return Future.succeededFuture();
        }
        Future<Void> next = Future.succeededFuture();
        for (Member m : group) {
            if (m.dropped()) {
                fail(m, abandoned(m));
                continue;
            }
            reruns.increment();
            next = next.compose(previous -> group(List.of(m)));
        }
        return next;
    }

    /**
     * {@code g}'s commit failed with {@code cause}: every member fails with it; with the reply lost ({@link InDoubt}) the members
     * writing rows stay open by its transaction id, the rest — and every member of a commit refused or lost before an id was known —
     * out of the open saves. Then every slot is given back ({@link #cancelSlots}) and, the commit not refused, each flow with a
     * member saved without a slot has its worker woken.
     */
    private void notCommitted(Group g, Throwable cause) {
        List<Member> open = List.of();
        if (cause instanceof InDoubt lost) {
            open = g.writing;
            List<TieredBlobStore.Durable> durables = new ArrayList<>();
            for (Member m : open) if (m.durable() != null) durables.add(m.durable());
            inDoubt(lost.xid, durables);
        }
        for (Member m : g.members) {
            if (!open.contains(m)) rolledBack(m);
            m.answer().tryFail(cause);
        }
        boolean mayHaveLanded = !refused(cause);   // the doubt registered and every member answered: then the slots
        cancelSlots(g, mayHaveLanded);   // in doubt: each slot's worker claims the row at its lease end, if it landed
        if (mayHaveLanded && workers != null) {
            for (Member m : g.writing) if (!g.slots.containsKey(m)) workers.wake(m.flow());   // a plain row that may have landed
        }
    }

    /** A commit refused for sure: an error reply of severity ERROR, after which the session goes on — never FATAL or PANIC. */
    private static boolean refused(Throwable failure) {
        return failure instanceof PgException reply && "ERROR".equals(reply.getSeverity());
    }

    /** {@code m} out of the open saves, then failed with {@code cause}. */
    private static void fail(Member m, Throwable cause) {
        rolledBack(m);
        m.answer().tryFail(cause);
    }

    private static void rolledBack(Member m) {
        if (m.durable() != null) m.durable().rolledBack();
    }

    private static IllegalStateException abandoned(Member m) {
        return new IllegalStateException("the save of " + m.arrived().id() + " was abandoned before its commit: its answer was decided; rolled back");
    }
}
