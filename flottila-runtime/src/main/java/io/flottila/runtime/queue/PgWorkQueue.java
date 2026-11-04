package io.flottila.runtime.queue;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BodyLostException;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.journal.JournalRef;
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
 * <p>Body refs (D-105, CORE-3 items 5, 8, 10): an item names its input body's record by {@code body_node, body_segment,
 * body_position, body_length} (null: a pre-V12 item, its body in {@code bodies}). A queue of a node claims — by the claim and
 * by the fast path — only the items whose body lies in that node's journal, or pre-V12 ones (until CORE-5 a body is read on
 * its own node); a queue without a node claims any. A queue given the node's store tells it the ref of every item it claims
 * (its location index, the hot path's reads) and that a completed item's body is read no more. An enqueue that adds no record
 * (a replay's or a DLQ retry's) copies its source's ref — the key's {@code body_refs} row, held with its segment row for key
 * share — in one statement: no source is a {@link BlobNotFoundException}. An item whose body an accepted
 * {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} gave up ({@code lost_at}, V13) is held for good: never claimed, so never run or parked.
 *
 * @since 0.1
 */
public final class PgWorkQueue implements WorkQueue {

    private static final String ENQUEUE = "insert into work(id, flow, step, partition_key) values ($1, $2, $3, $4)";
    /** An item naming its body's record (the intake's, CORE-3 item 6). */
    private static final String ENQUEUE_REF = "insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length) "
            + "values ($1, $2, $3, $4, $5, $6, $7, $8)";
    /** An item taking its source's ref: the key's ref row and its segment row held for key share, the item inserted from them. */
    private static final String ENQUEUE_FROM = """
            with src as (select r.node, r.segment, r.position, r.length from body_refs r
                           join journal_segments s on s.node = r.node and s.segment = r.segment
                          where r.message_id = $5 and r.step = $6 and r.part = $7 and r.lost_at is null for key share),
            ins as (insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length)
                    select $1, $2, $3, $4, node, segment, position, length from src)
            select (select count(*) from src),
                   exists (select 1 from body_refs where message_id = $5 and step = $6 and part = $7 and lost_at is not null)""";

    private static final String ELIGIBLE = """
            select w1.seq from work w1
            where w1.flow = $2
              and w1.available_at <= now()
              and w1.held_at is null
              and w1.lost_at is null
              and (w1.locked_until is null or w1.locked_until < now())
              and ($5::text is null or w1.body_node is null or w1.body_node = $5)
              and (w1.partition_key is null or (
                    not exists (select 1 from work w2 where w2.flow = $2
                                  and w2.partition_key = w1.partition_key and w2.locked_until >= now())
                and not exists (select 1 from work w3 where w3.flow = $2
                                  and w3.partition_key = w1.partition_key and w3.seq < w1.seq)))
            """;

    private static final String TAKE = "update work w set locked_by = $1, locked_until = now() + make_interval(secs => $4), attempts = w.attempts + 1";

    private static final String ITEM = " returning seq, id, flow, step, partition_key, attempts, body_node, body_segment, body_position, body_length";

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
    private static final String COMPLETE = "delete from work" + CURRENT + " returning id, flow, partition_key";

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
    private final String node;              // null: any node's items
    private final TieredBlobStore store;    // null: no index to tell

    /** A queue that claims any item, its body on any node. */
    public PgWorkQueue(Pool pool) {
        this(pool, null, null);
    }

    /** The queue of node {@code node}: it claims the items whose body lies in its journal, and pre-V12 ones. */
    public PgWorkQueue(Pool pool, String node) {
        this(pool, Objects.requireNonNull(node, "node"), null);
    }

    /** {@link #PgWorkQueue(Pool, String)} telling {@code store}, the node's, where each claimed item's body lies. */
    public PgWorkQueue(Pool pool, String node, TieredBlobStore store) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.node = node;
        this.store = store;
    }

    @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        return enqueue(pool, messageId, flow, step, partitionKey);
    }

    /** {@link #enqueue(String, String, int, String)} on {@code client}: inside the caller's transaction, visible once it commits (D-082). */
    public Future<Void> enqueue(SqlClient client, String messageId, String flow, int step, String partitionKey) {
        return client.preparedQuery(ENQUEUE).execute(Tuple.of(messageId, flow, step, partitionKey)).mapEmpty();
    }

    /** The item on {@code client} naming its body's record {@code body} in the journal of {@code bodyNode} (the intake's, D-105). */
    public Future<Void> enqueue(SqlClient client, String messageId, String flow, int step, String partitionKey, String bodyNode, JournalRef body) {
        return client.preparedQuery(ENQUEUE_REF).execute(Tuple.wrap(new Object[] {messageId, flow, step, partitionKey, bodyNode, body.segment(),
                body.position(), body.length()})).mapEmpty();
    }

    /**
     * The item on {@code client} taking the ref of {@code body}'s kept row (a replay's input, a DLQ retry's parked body): one
     * statement holding that row and its segment row for key share; without them it fails {@link BlobNotFoundException} naming
     * {@code body} and inserts nothing — {@link BodyLostException} when an accepted loss gave that row up ({@code lost_at}).
     */
    public Future<Void> enqueue(SqlClient client, String messageId, String flow, int step, String partitionKey, BlobKey body) {
        return client.preparedQuery(ENQUEUE_FROM).execute(Tuple.wrap(new Object[] {messageId, flow, step, partitionKey, body.messageId(), body.step(),
                body.part()})).compose(rows -> {
                    Row r = rows.iterator().next();
                    if (r.getLong(0) == 1) return Future.<Void>succeededFuture();
                    return Future.failedFuture(r.getBoolean(1) ? new BodyLostException(body) : new BlobNotFoundException(body));
                });
    }

    @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        if (lease.isNegative()) return Future.failedFuture(new IllegalArgumentException("negative lease: " + lease));
        return pool.preparedQuery(CLAIM).execute(Tuple.of(workerId, flow, max, seconds(lease), node)).map(this::items);
    }

    @Override public Future<Boolean> complete(long seq, int attempts) {
        return pool.preparedQuery(COMPLETE).execute(Tuple.of(seq, attempts)).map(rows -> {
            for (Row done : rows) completed(done);
            return rows.rowCount() == 1;
        });
    }

    /** One transaction: the guarded delete, then — only when it removed the row and the row had a key — the next-of-key claim. */
    @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
        if (lease.isNegative()) return Future.failedFuture(new IllegalArgumentException("negative lease: " + lease));
        return pool.withTransaction(conn -> conn.preparedQuery(COMPLETE).execute(Tuple.of(seq, attempts)).compose(deleted -> {
            if (deleted.rowCount() == 0) return Future.succeededFuture(Completion.STALE);   // not the holder's: nothing taken either
            Row done = deleted.iterator().next();
            completed(done);
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

    private Future<Optional<Item>> next(SqlClient conn, String workerId, String flow, String key, Duration lease) {
        return conn.preparedQuery(CLAIM_NEXT_OF_KEY).execute(Tuple.of(workerId, flow, key, seconds(lease), node))
                .map(rows -> items(rows).stream().findFirst());
    }

    /** A completed item's input body is read no more on the hot path: out of the store's index. */
    private void completed(Row done) {
        if (store != null) store.forget(BlobKey.body(done.getString("id"), 0));
    }

    private static double seconds(Duration lease) {
        return lease.toMillis() / 1000.0;
    }

    /** The items claimed; the store, if any, told where each one's input body lies. */
    private List<Item> items(RowSet<Row> rows) {
        List<Item> items = new ArrayList<>();
        for (Row r : rows) {
            Item item = item(r);
            String bodyNode = r.getString("body_node");
            if (store != null && bodyNode != null) {
                store.located(BlobKey.body(item.messageId(), 0), bodyNode,
                        new JournalRef(r.getLong("body_segment"), r.getLong("body_position"), r.getLong("body_length")));
            }
            items.add(item);
        }
        return items;
    }

    private static Item item(Row r) {
        return new Item(r.getLong("seq"), r.getString("id"), r.getString("flow"), r.getInteger("step"), r.getString("partition_key"), r.getInteger("attempts"));
    }
}
