package dev.rail8.runtime.deploy;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Optional;

/**
 * Where the pool's {@link DeploymentRecord}s, its generation and the audit rows of the deploys live: Postgres on a node
 * ({@link PgDeploymentStore}), memory in dev and tests ({@link InMemoryDeploymentStore}). Records are written only under the
 * pool's lock ({@link #lock}), one holder at a time across the pool, and each write raises the pool's generation by one.
 *
 * @since 0.1
 */
public interface DeploymentStore {

    /**
     * The pool as one read saw it.
     *
     * @param generation how many deploys and undeploys the pool committed
     * @param records every record, sorted by name as {@link String#compareTo} sorts ({@code Deployments} lists its packages so),
     *                whatever the database's collation
     */
    record Snapshot(long generation, List<DeploymentRecord> records) {
        public Snapshot {
            records = List.copyOf(records);
        }
    }

    /** The pool's generation and every record, read in one snapshot. */
    Future<Snapshot> read();

    /**
     * Takes the pool's lock without waiting: empty when another holder has it. The lock is held until {@link PoolLock#close}, or
     * until the store loses it (on Postgres: its connection closed); then every write through it fails.
     */
    Future<Optional<PoolLock>> lock();

    /**
     * Appends the audit row of one deploy: when (the store's clock), {@code actor}, the action {@code deploy}, the subject
     * {@code repository/<repository>} and {@code detail}, which names the commit and the outcome. Never a body or a credential.
     */
    Future<Void> audit(String actor, String repository, JsonObject detail);

    /**
     * The pool's lock, held: the one way to write records. Each write is one transaction that changes one record and raises the
     * generation by one, and only while the generation is the one this lock read or last wrote: otherwise it fails and writes
     * nothing.
     */
    interface PoolLock {

        /** The pool as read once the lock was taken. */
        Snapshot snapshot();

        /** Writes {@code record} as its package's one row (a row the package had is replaced whole); answers the new generation. */
        Future<Long> save(DeploymentRecord record);

        /** Deletes the row of the package {@code name}, and no other; answers the new generation, raised whether a row was there or not. */
        Future<Long> delete(String name);

        /** Frees the lock; never fails. */
        Future<Void> close();
    }
}
