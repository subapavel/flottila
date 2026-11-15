package io.flottila.runtime.pg;

/**
 * The node's Postgres pool did not hand a connection to a transaction (D-133): its wait elapsed, its wait queue was full, or it is
 * closed — whatever the pool failed with is the cause. Nothing of the transaction ran, so the caller may retry. Unchecked.
 *
 * @since 0.1
 */
public final class PoolRefusedException extends RuntimeException {

    /** {@code cause}: the pool's own failure of the acquire. */
    public PoolRefusedException(Throwable cause) {
        super("the Postgres pool refused a connection: " + cause, cause);
    }
}
