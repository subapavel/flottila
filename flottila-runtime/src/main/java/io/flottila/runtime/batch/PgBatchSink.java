package io.flottila.runtime.batch;

import io.flottila.core.batch.BatchSink;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link BatchSink} that commits one batch as one Postgres transaction. Each {@link Statement}
 * is fixed-shape SQL over {@code unnest} arrays, so the statement text never changes with the batch
 * size and the client's prepared-statement cache applies (ARCHITECTURE §4 "Writer"); statements
 * run in order inside the transaction and a failure rolls the whole batch back.
 *
 * @param <T> the item type
 * @since 0.1
 */
public final class PgBatchSink<T> implements BatchSink<T> {

    /**
     * One statement of the batch: SQL with array parameters and the function that turns the batch
     * into those arrays.
     *
     * @since 0.1
     */
    public record Statement<T>(String sql, Function<List<T>, Tuple> arrays) {
        public Statement {
            Objects.requireNonNull(sql, "sql");
            Objects.requireNonNull(arrays, "arrays");
        }
    }

    private final Pool pool;
    private final List<Statement<T>> statements;

    public PgBatchSink(Pool pool, List<Statement<T>> statements) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.statements = List.copyOf(statements);
        if (this.statements.isEmpty()) throw new IllegalArgumentException("no statements");
    }

    @Override public Future<Void> write(List<T> batch) {
        if (batch.isEmpty()) return Future.succeededFuture();
        return pool.withTransaction(conn -> run(conn, batch, 0));
    }

    private Future<Void> run(SqlConnection conn, List<T> batch, int index) {
        if (index == statements.size()) return Future.succeededFuture();
        Statement<T> s = statements.get(index);
        return conn.preparedQuery(s.sql()).execute(s.arrays().apply(batch))
                .compose(rows -> run(conn, batch, index + 1));
    }
}
