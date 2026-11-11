package io.flottila.runtime.queue;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BodyLostException;
import io.flottila.api.Timer;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.batch.OverloadedException;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.journal.JournalRef;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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
 * index; {@link #release} clears a hold and its lease and keeps the attempts (D-067). Each held row, and each lost one {@link #lost}
 * pages, counts the rows of its key behind it ({@code blocks}); {@link #discard} deletes a lost row under its key's lock and audits
 * it in the same statement (D-126).
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
 * <p>Holder liveness (D-101 (1), V14): a worker id is its node's id. A row whose lease ran out is claimable by another node only
 * when its holder has no live {@code nodes} row — released, or none at all (a holder that never joined counts as gone); its own
 * holder claims it back as before, after its join's {@code available_at} (a restarted node's old items wait for the previous
 * incarnation's {@code lease_until}, {@code PgMembership}). The check is one primary-key probe per candidate that has a holder, so the claim
 * keeps FIX-130's bounded plan. A hand-out of a row whose holder left it unsettled counts a doubt ({@code work.doubts}).
 *
 * <p>Local hand-off (D-121 (a), P2-16a): an enqueue given a holder and a lease ({@link #enqueueLeased}, the {@code SqlClient}
 * overloads with {@code holder, lease}) writes the row leased to that holder — attempt 1, {@code locked_until} now + lease — when
 * no other row of its key exists, and answers the leased item; otherwise the row is plain and the answer empty. Every keyed
 * insert and every keyed complete first takes its key's transaction advisory lock ({@link #lockKeys}, class {@code 0x0121},
 * {@code hashtext(flow ‖ U+001F ‖ key)}, keys in hash order; the complete takes it inside its own {@code WHERE}), so a same-key
 * save never reads "no other row" past a completion that has not yet handed the key on (F-30). V15's {@code work_flow_key_seq}
 * answers the key probes.
 *
 * <p>Batched completion (D-122, CORE-7): a queue built with a timer ({@link #PgWorkQueue(Pool, String, TieredBlobStore, Timer)})
 * completes unkeyed rows ({@link #completeUnkeyed}) through one {@link BatchingWriter}: one autocommit {@link #COMPLETE_BATCH} for
 * up to {@value #BATCH_MEMBERS} members, sent at once when no batch is in flight, else gathered behind it (window zero, no timer),
 * {@value #BATCH_IN_FLIGHT} in flight, {@value #BATCH_BACKLOG} waiting; a member that finds no room waits {@link #BATCH_MAX_WAIT} at
 * most, then fails and is counted refused. Each row is fenced by its own attempts token; a member whose row the statement did not
 * delete — re-claimed, held, keyed, never handed out, unknown — answers STALE. A failed statement fails every member with its
 * cause and is not retried (a retry would answer done for a member gone stale meanwhile); each row is left to its lease.
 * {@link #close} refuses later members and answers those handed over. The other constructors complete unkeyed rows by
 * {@link #completeRow}, one statement each.
 *
 * @since 0.1
 */
public final class PgWorkQueue implements WorkQueue {

    /**
     * The per-key lock of every keyed insert and keyed complete (D-121 A3, F-30): a transaction advisory lock of two keys — this
     * class and {@code hashtext(flow U+001F key)} — so a same-key insert waits for the commit of another before its own statement
     * reads the key's rows, and a keyed complete's next-of-key read sees every same-key row committed before it. Taken in hash
     * order, the subselect's sort evaluated before the lock (EXPLAIN VERBOSE, PG 17): two groups never wait for each other in a
     * cycle, colliding hashes included. The argument needs READ COMMITTED (Postgres' default, every transaction here): a statement
     * after the lock takes a snapshot of its own, so it sees what the lock waited for; under REPEATABLE READ or SERIALIZABLE the
     * transaction's first snapshot would hide it.
     */
    static final int KEY_LOCK_CLASS = 0x0121;
    /** The key locks of {@code $1}, each {@code flow U+001F key}, once each, in hash order. */
    static final String KEY_LOCK = "select pg_advisory_xact_lock(" + KEY_LOCK_CLASS + ", h)"
            + " from (select distinct hashtext(k) as h from unnest($1::text[]) as u(k) order by h) s";
    /**
     * The hand-off condition of an insert (D-121 A2) over the holder {@code $h}, the key {@code $4} of flow {@code $2}: a holder,
     * and no other row of the key, held or not (a row without a key always heads it).
     */
    private static String heads(String holder) {
        return holder + "::text is not null and ($4::text is null or not exists (select 1 from work o where o.flow = $2 and o.partition_key = $4))";
    }

    /** The lease columns of a row written leased when {@code leased}, plain otherwise; {@code $lease} in seconds. */
    private static String lease(String leased, String holder, String seconds) {
        return "case when " + leased + " then " + holder + " end, case when " + leased + " then now() + make_interval(secs => " + seconds
                + ") end, (" + leased + ")::int";
    }

    private static final System.Logger LOG = System.getLogger(PgWorkQueue.class.getName());

    /** A plain item, or leased to {@code $5} for {@code $6} seconds when it heads its key; its seq and attempts answered. */
    private static final String ENQUEUE = "insert into work(id, flow, step, partition_key, locked_by, locked_until, attempts) "
            + "select $1, $2, $3, $4, " + lease("l", "$5::text", "$6") + " from (select " + heads("$5") + " as l) h returning seq, attempts";
    /**
     * An item naming its body's record (the intake's, CORE-3 item 6), leased to {@code $9} for {@code $10} seconds when it heads its
     * key, answering its seq, attempts and transaction's id (CORE-4d: an in-doubt commit's).
     */
    private static final String ENQUEUE_REF = "insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length, "
            + "locked_by, locked_until, attempts) select $1, $2, $3, $4, $5, $6, $7, $8, " + lease("l", "$9::text", "$10")
            + " from (select " + heads("$9") + " as l) h returning seq, attempts, pg_current_xact_id()::text as xid";
    /**
     * An item taking its source's ref: the key's ref row and its segment row held for key share, the item inserted from them —
     * leased to {@code $8} for {@code $9} seconds when it heads its key and the ref lies in the holder's journal (a node claims only
     * its own bodies, D-105); answering, fifth, the ref's node when it is not the queue's node {@code $10} (D-121 (b) B4 (iii)).
     */
    private static final String ENQUEUE_FROM = """
            with src as (select r.node, r.segment, r.position, r.length from body_refs r
                           join journal_segments s on s.node = r.node and s.segment = r.segment
                          where r.message_id = $5 and r.step = $6 and r.part = $7 and r.lost_at is null for key share),
            ins as (insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length, locked_by, locked_until, attempts)
                    select $1, $2, $3, $4, node, segment, position, length, %s
                      from (select src.*, %s and src.node = $8 as l from src) h
                    returning seq, attempts)
            select (select count(*) from src),
                   exists (select 1 from body_refs where message_id = $5 and step = $6 and part = $7 and lost_at is not null),
                   (select seq from ins), (select attempts from ins),
                   (select node from src where node is distinct from $10::text)""".formatted(lease("l", "$8::text", "$9"), heads("$8"));

    private static final String ELIGIBLE = """
            select w1.seq from work w1
            where w1.flow = $2
              and w1.available_at <= now()
              and w1.held_at is null
              and w1.lost_at is null
              and (w1.locked_until is null or w1.locked_until < now())
              and ($5::text is null or w1.body_node is null or w1.body_node = $5)
              and (w1.locked_by is null or w1.locked_by = $1
                   or not exists (select 1 from nodes n where n.node = w1.locked_by and n.lease_until is not null))
              and (w1.partition_key is null or (
                    not exists (select 1 from work w2 where w2.flow = $2
                                  and w2.partition_key = w1.partition_key and w2.locked_until >= now())
                and not exists (select 1 from work w3 where w3.flow = $2
                                  and w3.partition_key = w1.partition_key and w3.seq < w1.seq)))
            """;

    /** A hand-out of a row an earlier holder left unsettled ({@code locked_by} still set) counts a doubt (D-101); RELEASE clears the holder. */
    private static final String TAKE = "update work w set locked_by = $1, locked_until = now() + make_interval(secs => $4), attempts = w.attempts + 1,"
            + " doubts = w.doubts + (w.locked_by is not null)::int";

    private static final String ITEM = " returning seq, id, flow, step, partition_key, attempts, doubts, body_node, body_segment, body_position, body_length";

    /**
     * Handed out in seq order, as {@code InMemoryWorkQueue} does: an UPDATE's RETURNING has no order of its own. The UPDATE takes
     * the rows the locking subselect chose by {@code seq = any(array(..))}, an init plan whose array is the primary key's index
     * condition in the generic plan and the custom one alike; {@code seq in (..)} let the generic plan of the cached statement
     * join the subselect to a Seq Scan of {@code work}, O(queue depth) per claim (FIX-130, F-27 addendum).
     */
    private static final String CLAIM = "with taken as (" + TAKE + " where w.seq = any(array(" + ELIGIBLE
            + " order by w1.seq limit $3 for update skip locked))" + ITEM + ") select * from taken order by seq";

    /**
     * The node of the key's next row for the fast path that took nothing (D-121 (b) B4 (i)): the oldest row of key {@code $3} of flow
     * {@code $2}, when it is neither held nor lost and its body lies in another journal than the queue's node {@code $5}'s (null for
     * a queue of any node, which takes every body). The statement's snapshot is the one the take read: the completed row is gone.
     */
    private static final String NEXT_NODE = "(select case when o.held_at is null and o.lost_at is null and o.body_node <> $5::text then o.body_node end"
            + " from work o where o.flow = $2 and o.partition_key = $3 order by o.seq limit 1)";

    /**
     * The fast path: same rule and the same {@code any(array(..))} take, restricted to one key, one row — always one row answered:
     * the item taken (null columns: none) and {@link #NEXT_NODE}. Note the parameters: $3 is the key here, the int limit in CLAIM.
     */
    private static final String CLAIM_NEXT_OF_KEY = "with taken as (" + TAKE
            + " where w.seq = any(array(" + ELIGIBLE + " and w1.partition_key = $3 order by w1.seq limit 1 for update skip locked))" + ITEM + ")"
            + " select t.*, " + NEXT_NODE + " as next_node from (select 1) one left join taken t on true";

    /**
     * The holder's claim is the row's current one (D-055, Q-1 / D-074): it exists, is not held and was last handed out with
     * these attempts, at least 1 — a row never handed out has attempts 0 (V4's default) and matches no token.
     */
    private static final String CURRENT = " where seq = $1 and attempts = $2 and attempts > 0 and held_at is null";

    /**
     * Only the current holder's row; the flow and key for the fast path's next-of-key claim. A keyed row is deleted under its key's
     * lock (D-121 A3, F-30), taken in the statement itself: a same-key insert in flight commits first (and the next-of-key read or
     * claim after this statement sees its row), or waits for this statement's transaction and then finds the key free. Under READ
     * COMMITTED only (see {@link #KEY_LOCK_CLASS}): the next-of-key read is a later statement, with a snapshot taken after the lock.
     */
    private static final String COMPLETE = "delete from work" + CURRENT + " and (partition_key is null or (select true from pg_advisory_xact_lock("
            + KEY_LOCK_CLASS + ", hashtext(flow || chr(31) || partition_key)))) returning id, flow, partition_key";

    /**
     * {@link #NEXT_NODE} for a plain keyed complete, a statement of its own after the delete committed (a snapshot taken after the
     * key's lock was released, so a same-key row committed before it is seen, D-121 A3): $1 the flow, $2 the key, $3 the queue's
     * node; always one row, null when no row of the key lies elsewhere claimable.
     */
    private static final String NEXT_NODE_AFTER = "select (select case when o.held_at is null and o.lost_at is null and o.body_node <> $3::text"
            + " then o.body_node end from work o where o.flow = $1 and o.partition_key = $2 order by o.seq limit 1)";

    /** Members of one {@link #COMPLETE_BATCH} at most (D-122 C3). */
    static final int BATCH_MEMBERS = 256;
    /** Batch statements in flight at most. */
    static final int BATCH_IN_FLIGHT = 2;
    /** Members waiting for a statement at most. */
    static final int BATCH_BACKLOG = 8192;
    /** How long a member waits for room in a full backlog before it fails, refused. */
    static final Duration BATCH_MAX_WAIT = Duration.ofSeconds(5);

    /**
     * The batched completion of unkeyed rows (D-122 C2), autocommit: each pair ({@code $1[i]}, {@code $2[i]}) deletes its row only
     * while it is the current holder's ({@link #CURRENT}'s condition) and the row has no key; the rows deleted are answered.
     */
    private static final String COMPLETE_BATCH = "delete from work w using unnest($1::bigint[], $2::int[]) c(seq, attempts)"
            + " where w.seq = c.seq and w.attempts = c.attempts and w.attempts > 0 and w.held_at is null and w.partition_key is null"
            + " returning w.seq, w.attempts, w.id";

    private static final String RENEW = "update work set locked_until = now() + make_interval(secs => $3)" + CURRENT;

    private static final String HOLD = "update work set held_at = now()" + CURRENT;

    /**
     * The rows of {@code w}'s key in its flow behind it (D-126, the barrier made visible), on the index {@code work_flow_key_seq}
     * (V15); 0 for a row without a key ({@code =} on null matches nothing).
     */
    private static final String BLOCKS = "(select count(*) from work o where o.flow = w.flow and o.partition_key = w.partition_key and o.seq > w.seq) as blocks";

    /** Every flow's held rows after a seq, in seq order, on the partial index {@code work_held} (V5); $2 is the limit plus one. */
    private static final String HELD = "select seq, id, flow, step, partition_key, attempts, doubts, held_at, " + BLOCKS + " from work w"
            + " where held_at is not null and seq > $1 order by seq limit $2";

    /** Every flow's lost rows after a seq, in seq order (V13's {@code lost_at}), on the partial index {@code work_lost} (V16); $2 is the limit plus one. */
    private static final String LOST = "select seq, id, flow, step, partition_key, attempts, doubts, lost_at, " + BLOCKS + " from work w"
            + " where lost_at is not null and seq > $1 order by seq limit $2";

    /**
     * Only a lost row (D-126 L3), a keyed one under its key's lock taken in the statement, as {@link #COMPLETE} takes it (D-121 A3),
     * deleted and audited in one statement — the audit row cannot outlive the delete nor the delete go unaudited: $1 seq, $2 actor,
     * $3 remote. The flow and key the next-of-key read needs are answered from the audit row's detail.
     */
    private static final String DISCARD = "with gone as (delete from work where seq = $1 and lost_at is not null and (partition_key is null or"
            + " (select true from pg_advisory_xact_lock(" + KEY_LOCK_CLASS + ", hashtext(flow || chr(31) || partition_key))))"
            + " returning seq, id, flow, partition_key, lost_at)"
            + " insert into audit(at, actor, action, subject, detail) select now(), $2, 'work.discard', 'work/' || g.seq, jsonb_build_object("
            + "'flow', g.flow, 'messageId', g.id, 'partitionKey', g.partition_key, 'lostAt', g.lost_at, 'remote', $3::text) from gone g"
            + " returning id, detail->>'flow' as flow, detail->>'partitionKey' as partition_key";

    /**
     * The body node of the oldest row left of key $2 of flow $1, read after the delete under the key's lock (a same-key row committed
     * before the lock is seen); always one row, null when the key has no row left or it names no node.
     */
    private static final String NEXT_OF_KEY_NODE = "select (select o.body_node from work o where o.flow = $1 and o.partition_key = $2 order by o.seq limit 1)";

    /** Only a held row: the hold and the lease cleared, the attempts kept; its flow and body node answered. */
    private static final String RELEASE = "update work set held_at = null, locked_by = null, locked_until = null where seq = $1 and held_at is not null"
            + " returning flow, body_node";

    /** Any row of the flow, held or not: a completed row is deleted ({@link #COMPLETE}). On the index {@code work_flow_seq}. */
    private static final String HAS_ITEMS = "select exists(select 1 from work where flow = $1)";

    private final Pool pool;
    private final String node;              // null: any node's items
    private final TieredBlobStore store;    // null: no index to tell
    private final BatchingWriter<Member> batch;   // null: unkeyed rows complete one statement each
    private final AtomicLong statements = new AtomicLong();
    private final AtomicLong members = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    /** One completion handed to the batch: the row, its holder's token, and its answer. */
    private record Member(long seq, int attempts, Promise<Completion> answer) {}

    /**
     * What the batched completion did since the queue was built (D-122): batch statements sent, members sent in them, members
     * answered STALE (keyed and unknown rows included), members refused after waiting {@link #BATCH_MAX_WAIT} for room.
     *
     * @since 0.1
     */
    public record Completions(long statements, long members, long stale, long refused) {}

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
        this.batch = null;
    }

    /**
     * {@link #PgWorkQueue(Pool, String, TieredBlobStore)} ({@code store} null: no index to tell) completing unkeyed rows in batches
     * (D-122), {@code timer} bounding a member's wait for room.
     */
    public PgWorkQueue(Pool pool, String node, TieredBlobStore store, Timer timer) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.node = Objects.requireNonNull(node, "node");
        this.store = store;
        this.batch = new BatchingWriter<>(this::completeBatch, timer,
                new BatchingWriter.Options(BATCH_MEMBERS, Duration.ZERO, BATCH_IN_FLIGHT, BATCH_BACKLOG, BATCH_MAX_WAIT));
    }

    /**
     * What an insert of an item answered (D-121 A2): the id of the transaction it ran in ({@code pg_current_xact_id()}, as text) and
     * the item as handed out when it was written leased, else empty.
     *
     * @since 0.1
     */
    public record Enqueued(String xid, Optional<Item> leased) {
        public Enqueued {
            Objects.requireNonNull(xid, "xid");
            Objects.requireNonNull(leased, "leased");
        }
    }

    /** A plain row; a keyed one in a transaction of its own under its key's lock ({@link #lockKeys}). */
    @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        return enqueueLeased(messageId, flow, step, partitionKey, null, null).mapEmpty();
    }

    /** {@code workerId} null: a plain row. A keyed row is written in a transaction of its own under its key's lock ({@link #lockKeys}). */
    @Override public Future<Optional<Item>> enqueueLeased(String messageId, String flow, int step, String partitionKey, String workerId, Duration lease) {
        if (partitionKey == null) return enqueue(pool, messageId, flow, step, null, workerId, lease);
        return pool.withTransaction(tx -> {
            Future<Void> locked = lockKeys(tx, List.of(Map.entry(flow, partitionKey)));   // sent before the insert: one round trip
            Future<Optional<Item>> inserted = enqueue(tx, messageId, flow, step, partitionKey, workerId, lease);
            return locked.compose(held -> inserted);
        });
    }

    /**
     * Takes the key lock (D-121 A3) of each ({@code flow}, {@code key}) of {@code flowKeys} on {@code tx}, once each and in one order
     * whatever the order given, until {@code tx} ends: sent before a keyed insert of the transaction, so the insert reads its key's
     * rows only once every other same-key insert or keyed complete in flight committed. Nothing is sent for none.
     */
    public Future<Void> lockKeys(SqlClient tx, Collection<Map.Entry<String, String>> flowKeys) {
        if (flowKeys.isEmpty()) return Future.succeededFuture();
        String[] names = flowKeys.stream().map(k -> k.getKey() + '\u001F' + k.getValue()).toArray(String[]::new);
        return tx.preparedQuery(KEY_LOCK).execute(Tuple.of(names)).mapEmpty();
    }

    /**
     * {@link #enqueueLeased} on {@code client}, inside the caller's transaction, visible once it commits (D-082): a keyed row under its
     * key's lock, which the caller took on {@code client} before ({@link #lockKeys}). {@code holder} null: a plain row.
     */
    public Future<Optional<Item>> enqueue(SqlClient client, String messageId, String flow, int step, String partitionKey, String holder, Duration lease) {
        return client.preparedQuery(ENQUEUE).execute(Tuple.of(messageId, flow, step, partitionKey, holder, seconds(holder, lease)))
                .map(rows -> leased(rows.iterator().next(), messageId, flow, step, partitionKey));
    }

    /**
     * The item on {@code client} naming its body's record {@code body} in the journal of {@code bodyNode} (the intake's, D-105), written
     * leased to {@code holder} for {@code lease} when it heads its key (D-121 A2; {@code holder} null: a plain row), under the key's
     * lock the caller took ({@link #lockKeys}). Answers the id of the transaction it ran in ({@code pg_current_xact_id()}, as text: a
     * commit whose reply is lost asks {@code pg_xact_status} of it, CORE-3 item 6) and the item when it was written leased.
     */
    public Future<Enqueued> enqueue(SqlClient client, String messageId, String flow, int step, String partitionKey, String bodyNode, JournalRef body,
                                    String holder, Duration lease) {
        return client.preparedQuery(ENQUEUE_REF).execute(Tuple.wrap(new Object[] {messageId, flow, step, partitionKey, bodyNode, body.segment(),
                body.position(), body.length(), holder, seconds(holder, lease)})).map(rows -> {
                    Row r = rows.iterator().next();
                    return new Enqueued(r.getString("xid"), leased(r, messageId, flow, step, partitionKey));
                });
    }

    /**
     * The item on {@code client} taking the ref of {@code body}'s kept row (a replay's input, a DLQ retry's parked body): one
     * statement holding that row and its segment row for key share; without them it fails {@link BlobNotFoundException} naming
     * {@code body} and inserts nothing — {@link BodyLostException} when an accepted loss gave that row up ({@code lost_at}).
     */
    public Future<Void> enqueue(SqlClient client, String messageId, String flow, int step, String partitionKey, BlobKey body) {
        return enqueue(client, messageId, flow, step, partitionKey, body, null, null).mapEmpty();
    }

    /**
     * {@link #enqueue(SqlClient, String, String, int, String, BlobKey)} written leased to {@code holder} for {@code lease} when it heads
     * its key and the ref lies in the holder's journal (D-121 A2; {@code holder} null: a plain row), under the key's lock the caller
     * took ({@link #lockKeys}). Answers the item when it was written leased, and the ref's node when it is not this queue's.
     */
    public Future<Copied> enqueue(SqlClient client, String messageId, String flow, int step, String partitionKey, BlobKey body, String holder,
                                  Duration lease) {
        return client.preparedQuery(ENQUEUE_FROM).execute(Tuple.wrap(new Object[] {messageId, flow, step, partitionKey, body.messageId(), body.step(),
                body.part(), holder, seconds(holder, lease), node})).compose(rows -> {
                    Row r = rows.iterator().next();
                    if (r.getLong(0) == 1) {
                        Integer attempts = r.getInteger(3);
                        return Future.succeededFuture(new Copied(attempts == 1
                                ? Optional.of(new Item(r.getLong(2), messageId, flow, step, partitionKey, 1)) : Optional.<Item>empty(),
                                Optional.ofNullable(r.getString(4))));
                    }
                    return Future.failedFuture(r.getBoolean(1) ? new BodyLostException(body) : new BlobNotFoundException(body));
                });
    }

    /**
     * What an insert taking its source's ref answered (D-121 A2, D-121 (b) B4 (iii)): the item as handed out when it was written
     * leased, else empty; and the node whose journal holds the ref when it is not this queue's node — the node that may claim the
     * plain row once its transaction committed.
     *
     * @since 0.1
     */
    public record Copied(Optional<Item> leased, Optional<String> elsewhere) {
        public Copied {
            Objects.requireNonNull(leased, "leased");
            Objects.requireNonNull(elsewhere, "elsewhere");
        }
    }

    /** The lease in seconds, null without a holder. */
    private static Double seconds(String holder, Duration lease) {
        return holder == null ? null : seconds(lease);
    }

    /** The row an insert answered as handed out — its first hand-out — when it was written leased (attempts 1), else empty. */
    private static Optional<Item> leased(Row r, String messageId, String flow, int step, String partitionKey) {
        return r.getInteger("attempts") == 1 ? Optional.of(new Item(r.getLong("seq"), messageId, flow, step, partitionKey, 1)) : Optional.empty();
    }

    /** The node this queue claims for, its hand-offs leased to (D-121 A2); empty for a queue of any node. */
    public Optional<String> node() {
        return Optional.ofNullable(node);
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

    /**
     * The guarded delete, autocommit; for a keyed row then {@link #NEXT_NODE_AFTER}, after the commit. A read that fails after the
     * delete committed names no node (the reconciliation covers it) and is logged at WARNING: the completion stands.
     */
    @Override public Future<Completion> completeRow(long seq, int attempts) {
        return pool.preparedQuery(COMPLETE).execute(Tuple.of(seq, attempts)).compose(rows -> {
            if (rows.rowCount() == 0) return Future.succeededFuture(Completion.STALE);
            Row done = rows.iterator().next();
            completed(done);
            String key = done.getString("partition_key");
            if (key == null) return Future.succeededFuture(Completion.done(Optional.empty()));
            return pool.preparedQuery(NEXT_NODE_AFTER).execute(Tuple.of(done.getString("flow"), key, node))
                    .map(next -> new Completion(true, Optional.empty(), Optional.ofNullable(next.iterator().next().getString(0))))
                    .otherwise(failed -> {
                        LOG.log(System.Logger.Level.WARNING, "work item " + seq + " of flow " + done.getString("flow") + " completed; reading "
                                + "the node of its key's next row failed, its reconciliation claim finds it: " + failed, failed);
                        return Completion.done(Optional.empty());
                    });
        });
    }

    /**
     * Batched (a queue built with a timer): the row joins the next {@link #COMPLETE_BATCH}, answered once it committed — done for a
     * row it deleted, STALE for any other (a keyed row included); failed with the statement's cause, with
     * {@link OverloadedException} when no room came within {@link #BATCH_MAX_WAIT}, with IllegalStateException after {@link #close}.
     * Otherwise {@link #completeRow}.
     */
    @Override public Future<Completion> completeUnkeyed(long seq, int attempts) {
        if (batch == null) return completeRow(seq, attempts);
        Member m = new Member(seq, attempts, Promise.promise());
        batch.saveWaiting(m).onFailure(failed -> {
            if (failed instanceof OverloadedException) refused.incrementAndGet();
            m.answer().tryFail(failed);
        });
        return m.answer().future();
    }

    /** The batched completion's counts so far; all zero for a queue that completes one statement each. */
    public Completions completions() {
        return new Completions(statements.get(), members.get(), stale.get(), refused.get());
    }

    /**
     * Refuses further batched completions and completes once every member handed over before was answered (never failing);
     * a queue that completes one statement each has nothing to wait for.
     */
    public Future<Void> close() {
        if (batch == null) return Future.succeededFuture();
        batch.close();
        return batch.flush().otherwiseEmpty();
    }

    /**
     * One batch statement for {@code batch}: every member answered before the statement's future completes — done once for each
     * row deleted (a second member of the same row and token is STALE), STALE otherwise; a failed statement answers nothing here
     * (the writer fails each member with its cause).
     */
    private Future<Void> completeBatch(List<Member> batch) {
        Long[] seqs = new Long[batch.size()];
        Integer[] tokens = new Integer[batch.size()];
        for (int i = 0; i < seqs.length; i++) {
            seqs[i] = batch.get(i).seq();
            tokens[i] = batch.get(i).attempts();
        }
        statements.incrementAndGet();
        members.addAndGet(seqs.length);
        return pool.preparedQuery(COMPLETE_BATCH).execute(Tuple.tuple().addArrayOfLong(seqs).addArrayOfInteger(tokens)).map(rows -> {
            Map<Long, Integer> deleted = new HashMap<>();
            for (Row done : rows) {
                completed(done);
                deleted.put(done.getLong("seq"), done.getInteger("attempts"));
            }
            for (Member m : batch) {
                if (deleted.remove(m.seq(), m.attempts())) {
                    m.answer().tryComplete(Completion.done(Optional.empty()));
                } else {
                    stale.incrementAndGet();
                    m.answer().tryComplete(Completion.STALE);
                }
            }
            return null;
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
            return next(conn, workerId, done.getString("flow"), key, lease);
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
            for (Row r : rows) page.add(new Held(item(r), r.getOffsetDateTime("held_at").toInstant(), r.getLong("blocks")));
            return page(page, limit, Held::item);
        });
    }

    @Override public Future<Page<Lost>> lost(Optional<String> after, int limit) {
        Page.requireLimit(limit);
        long from = after.map(cursor -> Long.parseLong(Page.position(cursor))).orElse(Long.MIN_VALUE);
        return pool.preparedQuery(LOST).execute(Tuple.of(from, limit + 1)).map(rows -> {
            List<Lost> page = new ArrayList<>();
            for (Row r : rows) page.add(new Lost(item(r), r.getOffsetDateTime("lost_at").toInstant(), r.getLong("blocks")));
            return page(page, limit, Lost::item);
        });
    }

    /** {@code rows} read with {@code limit} plus one: the first {@code limit} and, when one more came, the cursor after the last. */
    private static <T> Page<T> page(List<T> rows, int limit, Function<T, Item> item) {
        if (rows.size() <= limit) return new Page<>(rows, Optional.empty());
        List<T> full = rows.subList(0, limit);
        return new Page<>(full, Optional.of(Page.cursor(Long.toString(item.apply(full.getLast()).seq()))));
    }

    /**
     * {@link #DISCARD} and, for a keyed row, {@link #NEXT_OF_KEY_NODE} in one transaction: a failed audit insert fails the
     * statement, so nothing is deleted. Nothing lost under {@code seq}: the transaction commits empty and nothing is audited.
     */
    @Override public Future<Optional<Discarded>> discard(long seq, String actor, String remote) {
        return pool.withTransaction(tx -> Future.succeededFuture().compose(start -> tx.preparedQuery(DISCARD).execute(Tuple.of(seq, actor, remote))).compose(rows -> {
            if (rows.rowCount() == 0) return Future.succeededFuture(Optional.<Discarded>empty());
            Row audited = rows.iterator().next();
            long auditId = audited.getLong("id");
            String flow = audited.getString("flow");
            String key = audited.getString("partition_key");
            if (key == null) return Future.succeededFuture(Optional.of(new Discarded(auditId, flow, Optional.empty())));
            return tx.preparedQuery(NEXT_OF_KEY_NODE).execute(Tuple.of(flow, key))
                    .map(next -> Optional.of(new Discarded(auditId, flow, Optional.ofNullable(next.iterator().next().getString(0)))));
        }));
    }

    @Override public Future<Boolean> release(long seq) {
        return releaseRow(seq).map(Optional::isPresent);
    }

    /** The released row's flow and body node (none: a pre-V12 row, its body in {@code bodies}). */
    @Override public Future<Optional<Released>> releaseRow(long seq) {
        return pool.preparedQuery(RELEASE).execute(Tuple.of(seq)).map(rows -> rows.rowCount() == 0 ? Optional.<Released>empty()
                : Optional.of(released(rows.iterator().next())));
    }

    private static Released released(Row r) {
        return new Released(Optional.of(r.getString("flow")), Optional.ofNullable(r.getString("body_node")));
    }

    @Override public Future<Boolean> hasItems(String flow) {
        Objects.requireNonNull(flow, "flow");
        return pool.preparedQuery(HAS_ITEMS).execute(Tuple.of(flow)).map(rows -> rows.iterator().next().getBoolean(0));
    }

    /** The next-of-key take: the item taken, or none and the node of the key's next row when that row lies elsewhere ({@link #NEXT_NODE}). */
    private Future<Completion> next(SqlClient conn, String workerId, String flow, String key, Duration lease) {
        return conn.preparedQuery(CLAIM_NEXT_OF_KEY).execute(Tuple.of(workerId, flow, key, seconds(lease), node)).map(rows -> {
            Row r = rows.iterator().next();
            if (r.getLong("seq") == null) return new Completion(true, Optional.empty(), Optional.ofNullable(r.getString("next_node")));
            return Completion.done(Optional.of(located(r)));
        });
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
        for (Row r : rows) items.add(located(r));
        return items;
    }

    /** The item claimed in {@code r}; the store, if any, told where its input body lies. */
    private Item located(Row r) {
        Item item = item(r);
        String bodyNode = r.getString("body_node");
        if (store != null && bodyNode != null) {
            store.located(BlobKey.body(item.messageId(), 0), bodyNode,
                    new JournalRef(r.getLong("body_segment"), r.getLong("body_position"), r.getLong("body_length")));
        }
        return item;
    }

    private static Item item(Row r) {
        return new Item(r.getLong("seq"), r.getString("id"), r.getString("flow"), r.getInteger("step"), r.getString("partition_key"), r.getInteger("attempts"),
                r.getInteger("doubts"));
    }
}
