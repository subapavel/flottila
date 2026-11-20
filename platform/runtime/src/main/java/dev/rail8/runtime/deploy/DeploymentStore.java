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
     * @param marked the qualified ids of the flows the pool retires, sorted: each was run at-least-once by a record that a later write
     *               replaced with one that does not run it so, or deleted; the work queue still takes messages for it, and its rows are
     *               drained, until {@link #endMarks} ends its mark
     */
    record Snapshot(long generation, List<DeploymentRecord> records, List<String> marked) {
        public Snapshot {
            records = List.copyOf(records);
            marked = List.copyOf(marked);
        }

        /** A pool that retires no flow. */
        public Snapshot(long generation, List<DeploymentRecord> records) {
            this(generation, records, List.of());
        }
    }

    /** The pool's generation, every record and every mark, read in one snapshot. */
    Future<Snapshot> read();

    /**
     * Ends the mark of each retired flow whose work rows are gone, and raises the generation when one ended, in one transaction:
     * only while no holder has the pool's lock, which it takes without waiting, and for each flow only while no write of a message
     * for it is between its admission and its commit (the flow's admission lock, taken without waiting). Does nothing otherwise.
     */
    Future<Void> endMarks();

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

        /**
         * Writes {@code record} as its package's one row (a row the package had is replaced whole); answers the new generation. In
         * the same transaction each flow the replaced row runs at-least-once and {@code record} does not is marked, with a copy of
         * that row, and the mark of each flow {@code record} runs at-least-once ends.
         */
        Future<Long> save(DeploymentRecord record);

        /**
         * Deletes the row of the package {@code name}, and no other, marking each flow it ran at-least-once with a copy of it, in one
         * transaction; answers the new generation, raised whether a row was there or not.
         */
        Future<Long> delete(String name);

        /** Frees the lock; never fails. */
        Future<Void> close();
    }
}
