package io.flottila.runtime.store;

import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.core.flow.Intake;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.journal.JournalRef;
import io.flottila.runtime.queue.PgWorkQueue;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The node's {@link Intake} (D-082 restated by D-105, CORE-3 item 6): no row naming a body commits before its record is forced.
 * The ingress body is made durable in the node's journal first ({@link TieredBlobStore#forced}: appended, or its spill
 * persisted, and forced) — before the transaction, so no statement of it is sent while the record is not forced and a force that
 * fails saves nothing; then one Postgres transaction holds the ingress record ({@link PgMessageStore}, outside its batching
 * writer) and the work item naming the record's ref ({@link PgWorkQueue}) and — only when the flow keeps its input
 * ({@code keepInput}) — the body's kept ref ({@code body_refs}); a save that fails or a session that dies before the commit
 * leaves none of them, only bytes in the journal no row references. {@code abandoned} — the delivery's answer — is asked once,
 * after the rows' inserts and just before the commit: complete rolls everything back; its completion also ends the wait of the
 * body's record for room under {@code FLOTTILA_JOURNAL_PENDING_BYTES} ({@link TieredBlobStore#forced(io.flottila.api.BlobKey,
 * Future)}: retryable, nothing reserved). The transaction's statements go in turns on its one connection (D-109), a turn's
 * statements sent together and the next turn sent only once every reply of this one came: BEGIN alone; the rows — the record's
 * inserts, the item, and the kept ref when the input is kept — together; then, after the {@code abandoned} check, COMMIT alone. A
 * failed statement of a turn fails the save with its own cause (the first failed in the turn's order, never a later statement's
 * aborted transaction) and the transaction rolls back. A body durable already (no local copy) adds no record and no kept ref: its
 * item copies the key's kept ref ({@link PgWorkQueue#enqueue(SqlClient, String, String, int, String, io.flottila.api.BlobKey)}).
 * A body of another store is persisted there first, before the transaction (the node's ingress bodies are this store's), its item
 * naming no ref. Once the commit is acknowledged the body's ref is indexed and its local copy goes, before the save's future
 * completes; a transaction that rolls back leaves it local. The save is an open save ({@link #openSaves}) from its record's
 * reservation until its commit or rollback is acknowledged, or its force failed. A commit sent whose reply never came (the
 * connection lost) fails the save but leaves it open, by the transaction id its item's insert answered, until
 * {@code pg_xact_status} of that id is known — asked at each {@link #openSaves} — or the node restarts. Any failure — of the force, a statement, the
 * commit or the body's own store — fails the save; nothing here throws.
 *
 * <p>A save with a dedupe key (D-084) runs two more statements, in a turn of their own after BEGIN: the key's row in {@code processed_ids}
 * — scope {@code flow}, the SHA-256 of the key's UTF-8 bytes, the arrival's id, the ingress snapshot's start — {@code on
 * conflict do nothing}. A row inserted is the arrival's: the save goes on and commits it with the rest, so a save that fails or
 * is abandoned leaves the key free. None inserted is a key taken — by a committed save, or by an open one this statement waited
 * for (READ COMMITTED) and that committed: the second statement, sent with the first and run after it, reads the id the key's row
 * names (the arrival's own when it claimed the key), the transaction rolls back with
 * nothing of the arrival written, {@code abandoned} is never asked, this store's copy of the arrival's body is evicted (its
 * record, forced already, stays unreferenced in the journal; a body of another store keeps what its persist made durable) and the
 * future succeeds with the first message's id. A taken key whose row is gone when read (the retention job) fails the save.
 * A save without a key runs no extra statement and no extra turn.
 *
 * @since 0.1
 */
public final class PgIntake implements Intake {

    /** The key's row for the arrival, unless the scope holds the key already: no row returned is a key taken (D-084). */
    static final String CLAIM_KEY = "insert into processed_ids(scope, key_hash, message_id, created_at) "
            + "values ($1, sha256(convert_to($2::text, 'UTF8')), $3, $4) on conflict do nothing returning message_id";
    /** The message a taken key names. */
    static final String FIRST_ID = "select message_id from processed_ids where scope = $1 and key_hash = sha256(convert_to($2::text, 'UTF8'))";

    /** A taken key, carried out of the transaction to roll it back: the save answers {@link #first}. */
    private static final class Duplicate extends RuntimeException {
        private final String first;

        private Duplicate(String first) {
            super("a duplicate of " + first, null, false, false);
            this.first = first;
        }
    }

    /** A commit sent with no outcome known (its reply lost), carried out of the transaction: the save stays open by {@link #xid}. */
    private static final class InDoubt extends RuntimeException {
        private final String xid;

        private InDoubt(String xid, Throwable lost) {
            super("the commit of transaction " + xid + " was sent and its reply lost: its outcome is asked by pg_xact_status", lost, false, false);
            this.xid = xid;
        }
    }

    /** Each transaction id's status: committed, aborted, in progress, or null for one too old to be known. */
    static final String XACT_STATUS = "select x, pg_xact_status(x::xid8) from unnest($1::text[]) x";

    private final Pool pool;
    private final TieredBlobStore blobs;
    private final PgMessageStore messages;
    private final PgWorkQueue queue;
    /** The saves whose commit is in doubt, by transaction id. */
    private final Map<String, TieredBlobStore.Durable> inDoubt = new ConcurrentHashMap<>();
    private final ReentrantLock asking = new ReentrantLock();

    public PgIntake(Pool pool, TieredBlobStore blobs, PgMessageStore messages, PgWorkQueue queue) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    /**
     * The refs of the saves open now (CORE-3 item 6): the CORE-4d sweeper keeps their segments. A save whose commit is in doubt is
     * asked first ({@code pg_xact_status}, blocking — on a virtual thread): committed, it is done as a committed save is (its ref
     * indexed, its local copy gone); aborted or too old to be known, as a rolled-back one (rows it may have pin by themselves);
     * in progress, it stays open.
     */
    public Collection<JournalRef> openSaves() {
        if (!inDoubt.isEmpty()) settle();
        return blobs.openSaves();
    }

    private void settle() {
        asking.lock();
        try {
            String[] xids = inDoubt.keySet().toArray(String[]::new);
            for (Row r : pool.preparedQuery(XACT_STATUS).execute(Tuple.tuple().addArrayOfString(xids)).await()) {
                String status = r.getString(1);
                if ("in progress".equals(status)) continue;
                TieredBlobStore.Durable save = inDoubt.remove(r.getString(0));
                if ("committed".equals(status)) save.committed();
                else save.rolledBack();
            }
        } finally {
            asking.unlock();
        }
    }

    @Override
    public Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, boolean keepInput,
                               Future<?> abandoned) {
        Body body = arrived.body();
        boolean ours = body.store() == blobs;
        // from a completed future on, so a store or a statement that throws fails the save instead of escaping it
        return Future.<Void>succeededFuture()
                .compose(start -> ours ? blobs.forced(body.key(), abandoned) : body.store().persist(body.key()).map((TieredBlobStore.Durable) null))
                .compose(durable -> {
                    AtomicReference<String> xid = new AtomicReference<>();   // the item's transaction, once its insert answered it
                    return transaction(xid, tx -> claim(tx, arrived.id(), ingress, flow, dedupeKey)
                                    .compose(claimed -> rows(tx, arrived, ingress, flow, partitionKey, keepInput, durable, xid))
                                    .compose(written -> abandoned.isComplete()
                                            ? Future.<Void>failedFuture(new IllegalStateException("the save of " + arrived.id()
                                                    + " was abandoned before its commit: its answer was decided; rolled back"))
                                            : Future.<Void>succeededFuture()))
                            .transform(outcome -> {   // the commit or the rollback acknowledged: out of the open saves, before the future completes
                                if (durable != null) {
                                    if (outcome.succeeded()) durable.committed();   // the ref indexed, the local copy gone
                                    else if (outcome.cause() instanceof InDoubt lost) inDoubt.put(lost.xid, durable);   // open until known
                                    else durable.rolledBack();
                                }
                                return outcome.succeeded() ? Future.succeededFuture(arrived.id()) : Future.<String>failedFuture(outcome.cause());
                            });
                })
                .recover(failure -> failure instanceof Duplicate duplicate ? evicted(arrived, duplicate.first) : Future.failedFuture(failure));
    }

    /**
     * {@code work} in one transaction on a connection of its own: committed when it succeeded, else rolled back and failed with its
     * cause. A commit whose reply never came fails {@link InDoubt} once {@code xid} is known (a save naming a record), else with the
     * commit's own failure.
     */
    private Future<Void> transaction(AtomicReference<String> xid, Function<SqlConnection, Future<Void>> work) {
        return pool.getConnection().compose(conn -> conn.begin()
                .compose(tx -> Future.<Void>succeededFuture().compose(start -> work.apply(conn))
                        .transform(done -> done.succeeded()
                                ? tx.commit().recover(lost -> Future.failedFuture(xid.get() == null ? lost : new InDoubt(xid.get(), lost)))
                                : tx.rollback().transform(rolled -> Future.<Void>failedFuture(done.cause()))))
                .eventually(conn::close));
    }

    /**
     * The rows turn: the ingress record's inserts, the item, and — {@code keepInput}, a body of this store — the body's kept ref,
     * each sent before any reply; answers once every reply came, failed with the first failed statement's cause in that order.
     */
    private Future<Void> rows(SqlClient tx, Envelope arrived, Snapshot ingress, String flow, String partitionKey, boolean keepInput,
                              TieredBlobStore.Durable durable, AtomicReference<String> xid) {
        Future<Void> recorded = messages.insert(tx, ingress);
        Future<Void> queued = enqueue(tx, arrived, flow, partitionKey, durable, xid);
        Future<Void> kept = keepInput && durable != null ? durable.keepIn(tx) : Future.succeededFuture();
        return Future.join(recorded, queued, kept).mapEmpty();
    }

    /**
     * The item: naming the record forced for it (its insert answering the transaction's id, into {@code xid}); taking the kept ref of
     * a body durable already; naming nothing for another store's body.
     */
    private Future<Void> enqueue(SqlClient tx, Envelope arrived, String flow, String partitionKey, TieredBlobStore.Durable durable,
                                 AtomicReference<String> xid) {
        if (durable == null) return queue.enqueue(tx, arrived.id(), flow, 1, partitionKey);
        if (durable.ref() == null) return queue.enqueue(tx, arrived.id(), flow, 1, partitionKey, arrived.body().key());
        return queue.enqueue(tx, arrived.id(), flow, 1, partitionKey, durable.node(), durable.ref()).map(id -> {
            xid.set(id);
            return null;
        });
    }

    /**
     * The arrival's key row in {@code tx} — nothing to do without a key: the claim and the read of the id the key's row names, sent
     * together (one turn), answered once both replied. The claim's row inserted is the key claimed; a key taken fails with the
     * {@link Duplicate} naming the message the read found, or, its row gone, with an {@link IllegalStateException}.
     */
    private static Future<Void> claim(SqlClient tx, String id, Snapshot ingress, String flow, String key) {
        if (key == null) return Future.succeededFuture();
        Future<RowSet<Row>> inserted = tx.preparedQuery(CLAIM_KEY).execute(Tuple.of(flow, key, id, ingress.startedAt().atOffset(ZoneOffset.UTC)));
        Future<RowSet<Row>> first = tx.preparedQuery(FIRST_ID).execute(Tuple.of(flow, key));
        return Future.join(inserted, first).compose(replied -> {
            if (inserted.result().rowCount() == 1) return Future.<Void>succeededFuture();
            if (first.result().size() == 0) return Future.failedFuture(new IllegalStateException("the dedupe key of " + id + " is taken in flow "
                    + flow + " by a row that is gone (retention): no message to name; not saved"));
            return Future.failedFuture(new Duplicate(first.result().iterator().next().getString(0)));
        });
    }

    /**
     * {@code first}, once this store's copy of the duplicate's body is gone: forced before the claim, it is a memory copy or a
     * persisted spill, so its eviction releases memory and unlinks no file.
     */
    private Future<String> evicted(Envelope duplicate, String first) {
        return blobs.evict(duplicate.body().key()).map(evicted -> first);
    }
}
