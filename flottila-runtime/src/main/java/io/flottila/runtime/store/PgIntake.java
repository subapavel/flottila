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
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The node's {@link Intake} (D-082 restated by D-105, CORE-3 item 6): no row naming a body commits before its record is forced.
 * The ingress body is made durable in the node's journal first ({@link TieredBlobStore#forced}: appended, or its spill
 * persisted, and forced) — before the transaction, so no statement of it is sent while the record is not forced and a force that
 * fails saves nothing; then one Postgres transaction holds the ingress record ({@link PgMessageStore}, outside its batching
 * writer) and the work item naming the record's ref ({@link PgWorkQueue}) and — only when the flow keeps its input
 * ({@code keepInput}) — the body's kept ref ({@code body_refs}); a save that fails or a session that dies before the commit
 * leaves none of them, only bytes in the journal no row references. {@code abandoned} is asked once, after the rows' inserts and
 * just before the commit: true rolls everything back. A body durable already (no local copy) adds no record and no kept ref: its
 * item copies the key's kept ref ({@link PgWorkQueue#enqueue(SqlClient, String, String, int, String, io.flottila.api.BlobKey)}).
 * A body of another store is persisted there first, before the transaction (the node's ingress bodies are this store's), its item
 * naming no ref. Once the commit is acknowledged the body's ref is indexed and its local copy goes, before the save's future
 * completes; a transaction that rolls back leaves it local. The save is an open save ({@link #openSaves}) from its record's
 * reservation until its commit or rollback is acknowledged, or its force failed. Any failure — of the force, a statement, the
 * commit or the body's own store — fails the save; nothing here throws.
 *
 * <p>A save with a dedupe key (D-084) runs one more statement, first in its transaction: the key's row in {@code processed_ids}
 * — scope {@code flow}, the SHA-256 of the key's UTF-8 bytes, the arrival's id, the ingress snapshot's start — {@code on
 * conflict do nothing}. A row inserted is the arrival's: the save goes on and commits it with the rest, so a save that fails or
 * is abandoned leaves the key free. None inserted is a key taken — by a committed save, or by an open one this statement waited
 * for (READ COMMITTED) and that committed: the second statement reads that message's id, the transaction rolls back with
 * nothing of the arrival written, {@code abandoned} is never asked, this store's copy of the arrival's body is evicted (its
 * record, forced already, stays unreferenced in the journal; a body of another store keeps what its persist made durable) and the
 * future succeeds with the first message's id. A taken key whose row is gone when read (the retention job) fails the save.
 * A save without a key runs no extra statement.
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

    private final Pool pool;
    private final TieredBlobStore blobs;
    private final PgMessageStore messages;
    private final PgWorkQueue queue;

    public PgIntake(Pool pool, TieredBlobStore blobs, PgMessageStore messages, PgWorkQueue queue) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    /** The refs of the saves open now (CORE-3 item 6): the CORE-4d sweeper keeps their segments. */
    public Collection<JournalRef> openSaves() {
        return blobs.openSaves();
    }

    @Override
    public Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, boolean keepInput,
                               BooleanSupplier abandoned) {
        Body body = arrived.body();
        boolean ours = body.store() == blobs;
        // from a completed future on, so a store or a statement that throws fails the save instead of escaping it
        return Future.<Void>succeededFuture()
                .compose(start -> ours ? blobs.forced(body.key()) : body.store().persist(body.key()).map((TieredBlobStore.Durable) null))
                .compose(durable -> pool.withTransaction(tx -> claim(tx, arrived.id(), ingress, flow, dedupeKey)
                                .compose(claimed -> messages.insert(tx, ingress))
                                .compose(recorded -> enqueue(tx, arrived, flow, partitionKey, durable))
                                .compose(queued -> keepInput && durable != null ? durable.keepIn(tx) : Future.<Void>succeededFuture())
                                .compose(kept -> abandoned.getAsBoolean()
                                        ? Future.<Void>failedFuture(new IllegalStateException("the save of " + arrived.id()
                                                + " was abandoned before its commit: its answer was decided; rolled back"))
                                        : Future.<Void>succeededFuture()))
                        .transform(outcome -> {   // the commit or the rollback acknowledged: out of the open saves, before the future completes
                            if (durable != null) {
                                if (outcome.succeeded()) durable.committed();   // the ref indexed, the local copy gone
                                else durable.rolledBack();
                            }
                            return outcome.succeeded() ? Future.succeededFuture(arrived.id()) : Future.<String>failedFuture(outcome.cause());
                        }))
                .recover(failure -> failure instanceof Duplicate duplicate ? evicted(arrived, duplicate.first) : Future.failedFuture(failure));
    }

    /** The item: naming the record forced for it; taking the kept ref of a body durable already; naming nothing for another store's body. */
    private Future<Void> enqueue(SqlClient tx, Envelope arrived, String flow, String partitionKey, TieredBlobStore.Durable durable) {
        if (durable == null) return queue.enqueue(tx, arrived.id(), flow, 1, partitionKey);
        if (durable.ref() == null) return queue.enqueue(tx, arrived.id(), flow, 1, partitionKey, arrived.body().key());
        return queue.enqueue(tx, arrived.id(), flow, 1, partitionKey, durable.node(), durable.ref());
    }

    /**
     * The arrival's key row in {@code tx} — nothing to do without a key; a key taken fails with the {@link Duplicate} naming the
     * first message, or, its row gone, with an {@link IllegalStateException}.
     */
    private static Future<Void> claim(SqlClient tx, String id, Snapshot ingress, String flow, String key) {
        if (key == null) return Future.succeededFuture();
        return tx.preparedQuery(CLAIM_KEY).execute(Tuple.of(flow, key, id, ingress.startedAt().atOffset(ZoneOffset.UTC)))
                .compose(inserted -> inserted.rowCount() == 1 ? Future.<Void>succeededFuture() : tx.preparedQuery(FIRST_ID).execute(Tuple.of(flow, key))
                        .compose(first -> first.size() == 0
                                ? Future.failedFuture(new IllegalStateException("the dedupe key of " + id + " is taken in flow " + flow
                                        + " by a row that is gone (retention): no message to name; not saved"))
                                : Future.failedFuture(new Duplicate(first.iterator().next().getString(0)))));
    }

    /**
     * {@code first}, once this store's copy of the duplicate's body is gone: forced before the claim, it is a memory copy or a
     * persisted spill, so its eviction releases memory and unlinks no file.
     */
    private Future<String> evicted(Envelope duplicate, String first) {
        return blobs.evict(duplicate.body().key()).map(evicted -> first);
    }
}
