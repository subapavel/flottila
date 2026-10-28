package io.flottila.runtime.queue;

import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
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
 * on the {@code work} table. A row is claimable when it is not held, its lease is free, it is the
 * oldest row of its partition key (held rows included) and that key holds no live lease; the next
 * row of a key becomes eligible only after the previous one is completed (deleted) or its lease
 * expired — never while it is held. Leases are measured by the database clock. Every hand-out
 * increments the row's {@code attempts}, the holder's fencing token for {@link #renew}, {@link #hold},
 * {@link #complete} and the fast path (D-055, {@code V4__work_attempts}; Q-1, D-074): each is guarded by the one
 * {@code CURRENT} condition, so a held row is never deleted. The same-key fast path completes and claims
 * the next row of the key in one transaction (ARCHITECTURE §7). {@link #held} pages the held rows on V5's {@code work_held}
 * index; {@link #release} clears a hold and its lease and keeps the attempts (D-067).
 *
 * @since 0.1
 */
public final class PgWorkQueue implements WorkQueue {

    private static final String ENQUEUE = "insert into work(id, flow, step, partition_key) values ($1, $2, $3, $4)";

    private static final String ELIGIBLE = """
            select w1.seq from work w1
            where w1.flow = $2
              and w1.available_at <= now()
              and w1.held_at is null
              and (w1.locked_until is null or w1.locked_until < now())
              and (w1.partition_key is null or (
                    not exists (select 1 from work w2 where w2.flow = $2
                                  and w2.partition_key = w1.partition_key and w2.locked_until >= now())
                and not exists (select 1 from work w3 where w3.flow = $2
                                  and w3.partition_key = w1.partition_key and w3.seq < w1.seq)))
            """;

    private static final String TAKE = "update work w set locked_by = $1, locked_until = now() + make_interval(secs => $4), attempts = w.attempts + 1";

    private static final String ITEM = " returning seq, id, flow, step, partition_key, attempts";

    /** Handed out in seq order, as {@code InMemoryWorkQueue} does: an UPDATE's RETURNING has no order of its own. */
    private static final String CLAIM = "with taken as (" + TAKE + " where w.seq in (" + ELIGIBLE + " order by w1.seq limit $3 for update skip locked)"
            + ITEM + ") select * from taken order by seq";

    /** The fast path: same rule, restricted to one key, one row. Note the parameters: $3 is the key here, the int limit in CLAIM. */
    private static final String CLAIM_NEXT_OF_KEY = TAKE
            + " where w.seq in (" + ELIGIBLE + " and w1.partition_key = $3 order by w1.seq limit 1 for update skip locked)" + ITEM;

    /**
     * The holder's claim is the row's current one (D-055, Q-1 / D-074): it exists, is not held and was last handed out with
     * these attempts, at least 1 — a row never handed out has attempts 0 (V4's default) and matches no token.
     */
    private static final String CURRENT = " where seq = $1 and attempts = $2 and attempts > 0 and held_at is null";

    /** Only the current holder's row; the flow and key for the fast path's next-of-key claim. */
    private static final String COMPLETE = "delete from work" + CURRENT + " returning flow, partition_key";

    private static final String RENEW = "update work set locked_until = now() + make_interval(secs => $3)" + CURRENT;

    private static final String HOLD = "update work set held_at = now()" + CURRENT;

    /** Every flow's held rows after a seq, in seq order, on the partial index {@code work_held} (V5); $2 is the limit plus one. */
    private static final String HELD = "select seq, id, flow, step, partition_key, attempts, held_at from work"
            + " where held_at is not null and seq > $1 order by seq limit $2";

    /** Only a held row: the hold and the lease cleared, the attempts kept. */
    private static final String RELEASE = "update work set held_at = null, locked_by = null, locked_until = null where seq = $1 and held_at is not null";

    /** Any row of the flow, held or not: a completed row is deleted ({@link #COMPLETE}). On the index {@code work_flow_seq}. */
    private static final String HAS_ITEMS = "select exists(select 1 from work where flow = $1)";

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

    @Override public Future<Boolean> complete(long seq, int attempts) {
        return pool.preparedQuery(COMPLETE).execute(Tuple.of(seq, attempts)).map(rows -> rows.rowCount() == 1);
    }

    /** One transaction: the guarded delete, then — only when it removed the row and the row had a key — the next-of-key claim. */
    @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
        if (lease.isNegative()) return Future.failedFuture(new IllegalArgumentException("negative lease: " + lease));
        return pool.withTransaction(conn -> conn.preparedQuery(COMPLETE).execute(Tuple.of(seq, attempts)).compose(deleted -> {
            if (deleted.rowCount() == 0) return Future.succeededFuture(Completion.STALE);   // not the holder's: nothing taken either
            Row done = deleted.iterator().next();
            String key = done.getString("partition_key");
            if (key == null) return Future.succeededFuture(Completion.done(Optional.empty()));
            return next(conn, workerId, done.getString("flow"), key, lease).map(Completion::done);
        }));
    }

    @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) {
        return pool.preparedQuery(RENEW).execute(Tuple.of(seq, attempts, seconds(lease))).map(rows -> rows.rowCount() == 1);
    }

    @Override public Future<Boolean> hold(long seq, int attempts) {
        return pool.preparedQuery(HOLD).execute(Tuple.of(seq, attempts)).map(rows -> rows.rowCount() == 1);
    }

    @Override public Future<Page<Held>> held(Optional<String> after, int limit) {
        Page.requireLimit(limit);
        long from = after.map(cursor -> Long.parseLong(Page.position(cursor))).orElse(Long.MIN_VALUE);
        return pool.preparedQuery(HELD).execute(Tuple.of(from, limit + 1)).map(rows -> {
            List<Held> page = new ArrayList<>();
            for (Row r : rows) page.add(new Held(item(r), r.getOffsetDateTime("held_at").toInstant()));
            if (page.size() <= limit) return new Page<>(page, Optional.empty());
            List<Held> full = page.subList(0, limit);
            return new Page<>(full, Optional.of(Page.cursor(Long.toString(full.getLast().item().seq()))));
        });
    }

    @Override public Future<Boolean> release(long seq) {
        return pool.preparedQuery(RELEASE).execute(Tuple.of(seq)).map(rows -> rows.rowCount() == 1);
    }

    @Override public Future<Boolean> hasItems(String flow) {
        Objects.requireNonNull(flow, "flow");
        return pool.preparedQuery(HAS_ITEMS).execute(Tuple.of(flow)).map(rows -> rows.iterator().next().getBoolean(0));
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
        for (Row r : rows) items.add(item(r));
        return items;
    }

    private static Item item(Row r) {
        return new Item(r.getLong("seq"), r.getString("id"), r.getString("flow"), r.getInteger("step"), r.getString("partition_key"), r.getInteger("attempts"));
    }
}
