package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Postgres {@link WorkQueue} (ported from proto-m1): {@code SELECT … FOR UPDATE SKIP LOCKED}
 * on the {@code work} table. A row is claimable when its lease is free, it is the oldest row of its
 * partition key and that key holds no live lease; the next row of a key becomes eligible only after
 * the previous one is completed (deleted) or its lease expired. Leases are measured by the
 * database clock. The same-key fast path completes and claims the next row of the key in one
 * transaction (ARCHITECTURE §7).
 *
 * @since 0.1
 */
public final class PgWorkQueue implements WorkQueue {

    private static final String ENQUEUE = "insert into work(id, flow, step, partition_key) values ($1, $2, $3, $4)";

    private static final String ELIGIBLE = """
            select w1.seq from work w1
            where w1.flow = $2
              and w1.available_at <= now()
              and (w1.locked_until is null or w1.locked_until < now())
              and (w1.partition_key is null or (
                    not exists (select 1 from work w2 where w2.flow = $2
                                  and w2.partition_key = w1.partition_key and w2.locked_until >= now())
                and not exists (select 1 from work w3 where w3.flow = $2
                                  and w3.partition_key = w1.partition_key and w3.seq < w1.seq)))
            """;

    private static final String CLAIM = "update work w set locked_by = $1, locked_until = now() + make_interval(secs => $4)"
            + " where w.seq in (" + ELIGIBLE + " order by w1.seq limit $3 for update skip locked)"
            + " returning seq, id, flow, step, partition_key";

    /** The fast path: same rule, restricted to one key, one row. Note the parameters: $3 is the key here, the int limit in CLAIM. */
    private static final String CLAIM_NEXT_OF_KEY = "update work w set locked_by = $1, locked_until = now() + make_interval(secs => $4)"
            + " where w.seq in (" + ELIGIBLE + " and w1.partition_key = $3 order by w1.seq limit 1 for update skip locked)"
            + " returning seq, id, flow, step, partition_key";

    private static final String COMPLETE = "delete from work where seq = $1 returning flow, partition_key";

    private final Pool pool;

    public PgWorkQueue(Pool pool) {
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        return pool.preparedQuery(ENQUEUE).execute(Tuple.of(messageId, flow, step, partitionKey)).mapEmpty();
    }

    @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        if (lease.isNegative()) return Future.failedFuture(new IllegalArgumentException("negative lease: " + lease));
        return pool.preparedQuery(CLAIM).execute(Tuple.of(workerId, flow, max, seconds(lease))).map(PgWorkQueue::items);
    }

    @Override public Future<Void> complete(long seq) {
        return pool.preparedQuery(COMPLETE).execute(Tuple.of(seq)).mapEmpty();
    }

    @Override public Future<Optional<Item>> completeAndTakeNext(long seq, String workerId, Duration lease) {
        if (lease.isNegative()) return Future.failedFuture(new IllegalArgumentException("negative lease: " + lease));
        return pool.withTransaction(conn -> conn.preparedQuery(COMPLETE).execute(Tuple.of(seq)).compose(deleted -> {
            if (deleted.rowCount() == 0) return Future.succeededFuture(Optional.empty());
            Row done = deleted.iterator().next();
            String key = done.getString("partition_key");
            if (key == null) return Future.succeededFuture(Optional.empty());
            return next(conn, workerId, done.getString("flow"), key, lease);
        }));
    }

    private static Future<Optional<Item>> next(SqlClient conn, String workerId, String flow, String key, Duration lease) {
        return conn.preparedQuery(CLAIM_NEXT_OF_KEY).execute(Tuple.of(workerId, flow, key, seconds(lease)))
                .map(rows -> items(rows).stream().findFirst());
    }

    private static double seconds(Duration lease) {
        return lease.toMillis() / 1000.0;
    }

    private static List<Item> items(RowSet<Row> rows) {
        List<Item> items = new ArrayList<>();
        for (Row r : rows) items.add(new Item(r.getLong("seq"), r.getString("id"), r.getString("flow"), r.getInteger("step"), r.getString("partition_key")));
        return items;
    }
}
