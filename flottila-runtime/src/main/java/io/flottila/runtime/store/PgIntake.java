package io.flottila.runtime.store;

import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.core.flow.Intake;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.queue.PgWorkQueue;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.ZoneOffset;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The node's {@link Intake} (D-082): the ingress body, the ingress record and the work item commit in one Postgres
 * transaction — body ({@link TieredBlobStore#persistIn}), then record ({@link PgMessageStore}, outside its batching writer),
 * then item ({@link PgWorkQueue}), the item last — so a save that fails or a session that dies before the commit leaves none
 * of them. {@code abandoned} is asked once, after the item's insert and just before the commit: true rolls everything back. A
 * body already durable inserts nothing; a body of another store is persisted there first, before the transaction (the node's
 * ingress bodies are this store's). Once the commit is done, the body's local copy goes, before the save's future completes;
 * a transaction that rolls back leaves it local. Any failure — of a statement, the commit or the body's own store — fails the
 * save; nothing here throws.
 *
 * <p>A save with a dedupe key (D-084) runs one more statement, first in its transaction: the key's row in {@code processed_ids}
 * — scope {@code flow}, the SHA-256 of the key's UTF-8 bytes, the arrival's id, the ingress snapshot's start — {@code on
 * conflict do nothing}. A row inserted is the arrival's: the save goes on and commits it with the rest, so a save that fails or
 * is abandoned leaves the key free. None inserted is a key taken — by a committed save, or by an open one this statement waited
 * for (READ COMMITTED) and that committed: the second statement reads that message's id, the transaction rolls back with
 * nothing of the arrival written, {@code abandoned} is never asked, this store's copy of the arrival's body is evicted (a
 * failed eviction is logged, never the save's failure; a body of another store keeps what its persist made durable) and the
 * future succeeds with the first message's id. A taken key whose row is gone when read (the retention job) fails the save.
 * A save without a key runs no extra statement.
 *
 * @since 0.1
 */
public final class PgIntake implements Intake {

    private static final System.Logger LOG = System.getLogger(PgIntake.class.getName());

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

    @Override
    public Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, BooleanSupplier abandoned) {
        Body body = arrived.body();
        boolean ours = body.store() == blobs;
        // from a completed future on, so a store or a statement that throws fails the save instead of escaping it
        return Future.<Void>succeededFuture()
                .compose(start -> ours ? Future.<Void>succeededFuture() : body.store().persist(body.key()))
                .compose(elsewhere -> pool.withTransaction(tx -> claim(tx, arrived.id(), ingress, flow, dedupeKey)
                        .compose(claimed -> ours ? blobs.persistIn(tx, body.key()) : Future.<Runnable>succeededFuture(() -> { }))
                        .compose(release -> messages.insert(tx, ingress)
                                .compose(recorded -> queue.enqueue(tx, arrived.id(), flow, 1, partitionKey))
                                .compose(queued -> abandoned.getAsBoolean()
                                        ? Future.<Runnable>failedFuture(new IllegalStateException("the save of " + arrived.id()
                                                + " was abandoned before its commit: its answer was decided; rolled back"))
                                        : Future.succeededFuture(release)))))
                .map(release -> {
                    release.run();   // committed: the body's local copy goes before the save's future completes
                    return arrived.id();
                })
                .recover(failure -> failure instanceof Duplicate duplicate ? evicted(arrived, duplicate.first) : Future.failedFuture(failure));
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

    /** {@code first}, once this store's copy of the duplicate's body is gone — or its eviction failed, which is logged. */
    private Future<String> evicted(Envelope duplicate, String first) {
        return blobs.evict(duplicate.body().key()).transform(evict -> {
            if (evict.failed()) {
                LOG.log(System.Logger.Level.WARNING, "the local body of " + duplicate.id() + ", a duplicate of message " + first
                        + ", was not evicted", evict.cause());
            }
            return Future.succeededFuture(first);
        });
    }
}
