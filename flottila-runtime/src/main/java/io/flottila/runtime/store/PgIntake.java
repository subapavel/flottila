package io.flottila.runtime.store;

import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.core.flow.Intake;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.queue.PgWorkQueue;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;

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
 * @since 0.1
 */
public final class PgIntake implements Intake {

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
    public Future<Void> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, BooleanSupplier abandoned) {
        Body body = arrived.body();
        boolean ours = body.store() == blobs;
        // from a completed future on, so a store or a statement that throws fails the save instead of escaping it
        return Future.<Void>succeededFuture()
                .compose(start -> ours ? Future.<Void>succeededFuture() : body.store().persist(body.key()))
                .compose(elsewhere -> pool.withTransaction(tx -> (ours ? blobs.persistIn(tx, body.key()) : Future.<Runnable>succeededFuture(() -> { }))
                        .compose(release -> messages.insert(tx, ingress)
                                .compose(recorded -> queue.enqueue(tx, arrived.id(), flow, 1, partitionKey))
                                .compose(queued -> abandoned.getAsBoolean()
                                        ? Future.<Runnable>failedFuture(new IllegalStateException("the save of " + arrived.id()
                                                + " was abandoned before its commit: its answer was decided; rolled back"))
                                        : Future.succeededFuture(release)))))
                .map(release -> {
                    release.run();   // committed: the body's local copy goes before the save's future completes
                    return null;
                });
    }
}
