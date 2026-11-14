package io.flottila.runtime.pg;

import io.vertx.core.Future;
import io.vertx.core.VertxException;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;

import java.util.function.Function;

/**
 * A transaction on a pooled connection, and the pool's refusal told apart from everything after it (D-133). The acquire is
 * {@code pool.getConnection()}; whatever fails it — before any statement ran — fails the result with a
 * {@link PoolRefusedException}, its cause kept, by where it failed, never by its type or message.
 *
 * @since 0.1
 */
public final class PgTx {

    private PgTx() {
    }

    /**
     * {@code fn} in one transaction on a connection of {@code pool}, as {@link Pool#withTransaction(Function)} runs it: begun, then
     * {@code fn}; committed when its future succeeds, rolled back when it fails or {@code fn} throws; the connection closed in every
     * case once the outcome is known. A refused acquire fails the result with a {@link PoolRefusedException} and {@code fn} never
     * runs; a failure of the begin, of {@code fn}, or of the commit is the result's failure as it was, never wrapped — a failed
     * rollback too leaves {@code fn}'s failure the result's.
     */
    public static <T> Future<T> inTransaction(Pool pool, Function<SqlConnection, Future<T>> fn) {
        return acquire(pool).compose(conn -> conn.begin()
                .compose(tx -> Future.succeededFuture(conn).compose(fn)   // a chain from a completed future: a throw in fn rolls back
                        .compose(result -> tx.commit().map(result),
                                failed -> tx.rollback().transform(rolledBack -> Future.<T>failedFuture(failed))))
                .onComplete(outcome -> conn.close()));
    }

    /** A connection of {@code pool}; whatever fails the acquire fails the result with a {@link PoolRefusedException}, its cause kept. */
    public static Future<SqlConnection> acquire(Pool pool) {
        return pool.getConnection().recover(refused -> Future.failedFuture(new PoolRefusedException(refused)));
    }

    /**
     * The node's Postgres pool refused the caller a connection (D-132, D-133), retryable: a transaction's acquire refused
     * ({@link PoolRefusedException}), its wait queue full ({@link ConnectionPoolTooBusyException}), or a statement on the pool
     * failed with exactly a {@link VertxException} — the pool's wait for a connection elapsed, or the pool is closed because the
     * node stops (by the client's type, never by its message). A subclass of {@link VertxException} (a closed connection, a
     * server's error) is no refusal, and neither is a refusal carried only as a cause.
     */
    public static boolean refusedByThePool(Throwable failed) {
        return failed instanceof PoolRefusedException || failed instanceof ConnectionPoolTooBusyException || failed.getClass() == VertxException.class;
    }
}
