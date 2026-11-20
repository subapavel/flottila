package dev.rail8.runtime.queue;

import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobNotFoundException;
import dev.rail8.api.BodyLostException;
import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.Parked;
import dev.rail8.runtime.pg.PgTx;
import dev.rail8.runtime.store.PgDlq;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The operator's acts over Postgres, on the rows of a {@link PgWorkQueue} and its {@code dlq} table. Each act is one transaction:
 * the key's lock first for a keyed message ({@link PgWorkQueue#lockKeys}, as every keyed insert and delete takes it), then the
 * message's DLQ row, locked, so a second act on the message waits for the first and then finds that row gone; then the
 * message's work rows of its flow — under its key when it has one (the index {@code work_flow_key_seq}), by id and flow when it
 * has none — decide the act, each statement reading after the locks. The DLQ row is deleted and the audit row written in one
 * statement, so neither stands without the other. An act that answers {@link Unsettled} or {@link NotParked} writes nothing.
 *
 * <p>A retry of a stopped message updates its row in place; one without a work row inserts a row at the tail of its key from
 * the kept ref of the body its DLQ row names ({@code body_key}), as the intake's copy does: its own ingress body
 * ({@code <id>/0/body}), or for a callee of a call between flows its caller's body, kept under the caller's key. A cancel deletes the message's stopped rows,
 * lost or not, and then reads the body node of its key's next row for {@link Done#node}. Audit details: a retry's flow,
 * attempt, partition key, the seq of the row that runs and the remote address; a cancel's flow, step, step name, the park's
 * instant, the partition key, the body's hash ({@code <algorithm>:<hex>}) and size, the reason when given, the remote address,
 * the failure's text cut to {@link #ERROR_MAX} and whether it was cut ({@code errorCut}).
 *
 * <p>An end of a message that is not parked marks its oldest work row: the flow read off the message's arrival record (its
 * {@code messages} row at step 0), then that row of the flow locked; the audit row is written and the row marked with it
 * ({@code work.cancel_audit}, a waiting row made due now) in one statement. Its audit detail: the flow, the step, the partition
 * key, the row's seq, the reason when given and the remote address. The node holding the row's live lease ({@code locked_by},
 * a worker's id being its node's) is the one {@link Stopping} names.
 *
 * @since 0.1
 */
public final class PgFailedMessages implements FailedMessages {

    /**
     * The DLQ row a retry acts on, locked: message $1 of flow $2 parked by its worker at step 1 after attempt $3 (prop
     * {@code attempt}). Answers its columns.
     */
    private static final String PARKED_AFTER = "select " + PgDlq.COLUMNS + " from dlq where id = $1 and flow = $2 and step = 1 and props->>'attempt' = $3::int::text for update";

    /** The flow and key of message $1's DLQ row, read before any lock: a cancel takes that key's lock before it locks the row. */
    private static final String KEY_OF = "select flow, partition_key from dlq where id = $1";

    /** Message $1's DLQ row, locked. */
    private static final String PARKED = "select " + PgDlq.COLUMNS + " from dlq where id = $1 for update";

    /** Message $1's work rows of flow $2 in seq order: under key $3 when one is given, by id and flow when none is. */
    private static final String ROWS = """
            select seq, stopped_at is not null as stopped, lost_at is not null as lost from work where flow = $2 and id = $1 and $3::text is null
            union all
            select seq, stopped_at is not null, lost_at is not null from work where flow = $2 and partition_key = $3::text and id = $1
            order by seq""";

    /**
     * A stopped row handed back in place: its seq kept, so it still heads its key; due at once, unleased (the next hand-out counts
     * no doubt), its hold and stop cleared, its own bound one past its attempts. Not a row whose body is lost. Answers its body node.
     */
    private static final String IN_PLACE = "update work set max_attempts = attempts + 1, held_at = null, stopped_at = null, locked_by = null,"
            + " locked_until = null, available_at = now() where seq = $1 and stopped_at is not null and lost_at is null returning body_node";

    /**
     * A new row at the tail of key $3 (none: unkeyed) of flow $2 for message $1 from the kept ref of body key ($5, $6, $7), held for
     * key share with its segment row: step 1, handed out $4 times, its own bound $4 + 1, due at once, unleased. Answers the rows
     * inserted, the row's seq and body node, and whether an accepted loss gave the ref up.
     */
    private static final String AT_TAIL = """
            with src as (select r.node, r.segment, r.position, r.length from body_refs r
                           join journal_segments s on s.node = r.node and s.segment = r.segment
                          where r.message_id = $5::text and r.step = $6::int and r.part = $7::text and r.lost_at is null for key share),
            ins as (insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length, attempts, max_attempts)
                    select $1::text, $2::text, 1, $3::text, node, segment, position, length, $4::int, $4::int + 1 from src
                    returning seq, body_node)
            select (select count(*) from ins), (select seq from ins), (select body_node from ins),
                   exists (select 1 from body_refs where message_id = $5::text and step = $6::int and part = $7::text and lost_at is not null)""";

    /**
     * Message $1's oldest work row of the flow its arrival record names, locked: whether it is held (a stopped row is held too) or
     * lost, the cancel it is marked with, and its holder when its lease is live.
     */
    private static final String OLDEST_ROW = "select seq, flow, step, partition_key, held_at is not null as held, lost_at is not null as lost, cancel_audit,"
            + " case when locked_until > now() then locked_by end as holder"
            + " from work where flow = (select flow from messages where id = $1 and step = 0 limit 1) and id = $1 order by seq limit 1 for update";

    /**
     * The end audited and row $4 marked with it in one statement, a waiting row made due now: actor $2, detail $3, subject
     * {@code message/<id>}. Answers the audit row's id and instant.
     */
    private static final String MARK = "with a as (insert into audit(at, actor, action, subject, detail)"
            + " values (now(), $2, 'message.cancel', 'message/' || $1::text, $3::jsonb) returning id, at),"
            + " m as (update work set cancel_audit = (select id from a), available_at = least(available_at, now()) where seq = $4 returning seq)"
            + " select a.id, a.at, (select count(*) from m) from a";

    /** The audit row $1 of the cancel a row is marked with already. */
    private static final String FIRST = "select at, actor, detail->>'reason' as reason from audit where id = $1";

    /** The stopped rows among $1 deleted. */
    private static final String DELETE_STOPPED = "delete from work where seq = any($1::bigint[]) and stopped_at is not null";

    /**
     * Message $1's DLQ row deleted and the act audited in one statement: actor $2, action $3, detail $4, subject
     * {@code message/<id>}. Answers the audit row's id and instant.
     */
    private static final String SETTLE = "with gone as (delete from dlq where id = $1 returning id)"
            + " insert into audit(at, actor, action, subject, detail) select now(), $2, $3, 'message/' || g.id, $4::jsonb from gone g returning id, at";

    private final Pool pool;
    private final PgWorkQueue queue;

    /** The acts on the rows of {@code queue} and the DLQ, over {@code pool}, the queue's. */
    public PgFailedMessages(Pool pool, PgWorkQueue queue) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    /**
     * {@link FailedMessages#retry}: without the kept ref of the body the DLQ row names, a retry that would add a row fails
     * {@link BlobNotFoundException} ({@link BodyLostException} when an accepted loss gave it up) and the transaction rolls back.
     */
    @Override
    public Future<Act> retry(String messageId, String flow, String partitionKey, int attempt, Actor by) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(by, "by");
        return PgTx.inTransaction(pool, tx -> locked(tx, flow, partitionKey)
                .compose(held -> tx.preparedQuery(PARKED_AFTER).execute(Tuple.of(messageId, flow, attempt)))
                .compose(parked -> {
                    if (parked.rowCount() == 0) return Future.<Act>succeededFuture(NotParked.INSTANCE);
                    return tx.preparedQuery(ROWS).execute(Tuple.of(messageId, flow, partitionKey)).compose(rows -> {
                        List<Row> mine = list(rows);
                        if (mine.isEmpty()) return atTail(tx, messageId, flow, partitionKey, attempt, by, PgDlq.parked(parked.iterator().next()).bodyKey());
                        Row only = mine.get(0);
                        if (mine.size() > 1 || !only.getBoolean("stopped") || only.getBoolean("lost")) {
                            return Future.<Act>succeededFuture(new Unsettled(only.getLong("seq")));
                        }
                        long seq = only.getLong("seq");
                        return tx.preparedQuery(IN_PLACE).execute(Tuple.of(seq)).compose(updated -> {
                            if (updated.rowCount() == 0) return Future.<Act>succeededFuture(new Unsettled(seq));   // lost meanwhile
                            return retried(tx, messageId, flow, partitionKey, attempt, by, seq, updated.iterator().next().getString("body_node"));
                        });
                    });
                }));
    }

    /**
     * The retry of a message without a work row: its flow admitted ({@link PgWorkQueue#admit}: a flow the pool no longer runs
     * at-least-once fails {@link NotAdmittedException} and the transaction rolls back), its new row at the tail of its key from the
     * kept ref of {@code body}, then the DLQ row deleted and the act audited.
     */
    private Future<Act> atTail(SqlConnection tx, String messageId, String flow, String partitionKey, int attempt, Actor by, BlobKey body) {
        return queue.admit(tx, List.of(flow)).compose(admitted -> tx.preparedQuery(AT_TAIL)
                .execute(Tuple.of(messageId, flow, partitionKey, attempt, body.messageId(), body.step(), body.part()))).compose(rows -> {
            Row r = rows.iterator().next();
            if (r.getLong(0) == 0) {
                return Future.failedFuture(r.getBoolean(3) ? new BodyLostException(body) : new BlobNotFoundException(body));
            }
            return retried(tx, messageId, flow, partitionKey, attempt, by, r.getLong(1), r.getString(2));
        });
    }

    private Future<Act> retried(SqlConnection tx, String messageId, String flow, String partitionKey, int attempt, Actor by, long seq, String node) {
        JsonObject detail = new JsonObject().put("flow", flow).put("attempt", attempt + 1).put("partitionKey", partitionKey).put("seq", seq)
                .put("remote", by.remote());
        return settle(tx, messageId, by, "message.retry", detail).map(audit -> new Done(new Event(audit.getLong("id"),
                audit.getOffsetDateTime("at").toInstant(), messageId, flow, by.name(), Kind.RETRY, OptionalInt.of(attempt + 1), Optional.empty(),
                Optional.empty()), Optional.ofNullable(node)));
    }

    @Override
    public Future<Act> cancel(String messageId, Actor by, Optional<String> reason) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(by, "by");
        Optional<String> given = FailedMessages.reason(reason);
        return PgTx.inTransaction(pool, tx -> tx.preparedQuery(KEY_OF).execute(Tuple.of(messageId)).compose(keyOf -> {
            if (keyOf.rowCount() == 0) return Future.<Act>succeededFuture(NotParked.INSTANCE);
            Row k = keyOf.iterator().next();
            return locked(tx, k.getString("flow"), k.getString("partition_key"))
                    .compose(held -> tx.preparedQuery(PARKED).execute(Tuple.of(messageId)))
                    .compose(rows -> rows.rowCount() == 0 ? Future.<Act>succeededFuture(NotParked.INSTANCE)
                            : cancel(tx, PgDlq.parked(rows.iterator().next()), by, given));
        }));
    }

    /** A parked message cancelled as {@link #cancel} does; any other has its oldest work row marked. */
    @Override
    public Future<Act> end(String messageId, Actor by, Optional<String> reason) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(by, "by");
        Optional<String> given = FailedMessages.reason(reason);
        return PgTx.inTransaction(pool, tx -> tx.preparedQuery(KEY_OF).execute(Tuple.of(messageId)).compose(keyOf -> {
            if (keyOf.rowCount() == 0) return mark(tx, messageId, by, given);
            Row k = keyOf.iterator().next();
            return locked(tx, k.getString("flow"), k.getString("partition_key"))
                    .compose(held -> tx.preparedQuery(PARKED).execute(Tuple.of(messageId)))
                    .compose(rows -> rows.rowCount() == 0 ? mark(tx, messageId, by, given) : cancel(tx, PgDlq.parked(rows.iterator().next()), by, given));
        }));
    }

    /**
     * The end of message {@code messageId} that is not parked: {@link NotParked} without a work row, {@link Unsettled} for a held or
     * lost one; else its row marked and audited, or for a row marked already its first cancel answered.
     */
    private Future<Act> mark(SqlConnection tx, String messageId, Actor by, Optional<String> reason) {
        return tx.preparedQuery(OLDEST_ROW).execute(Tuple.of(messageId)).compose(rows -> {
            if (rows.rowCount() == 0) return Future.<Act>succeededFuture(NotParked.INSTANCE);
            Row w = rows.iterator().next();
            long seq = w.getLong("seq");
            if (w.getBoolean("held") || w.getBoolean("lost")) return Future.<Act>succeededFuture(new Unsettled(seq));
            String flow = w.getString("flow");
            Optional<String> node = Optional.ofNullable(w.getString("holder"));
            Long first = w.getLong("cancel_audit");
            if (first != null) {
                return tx.preparedQuery(FIRST).execute(Tuple.of(first)).map(audit -> {
                    Row a = audit.iterator().next();
                    return new Stopping(new Event(first, a.getOffsetDateTime("at").toInstant(), messageId, flow, a.getString("actor"), Kind.CANCEL,
                            OptionalInt.empty(), Optional.ofNullable(a.getString("reason")), Optional.empty()), node);
                });
            }
            JsonObject detail = new JsonObject().put("flow", flow).put("step", w.getInteger("step")).put("partitionKey", w.getString("partition_key"))
                    .put("seq", seq).put("reason", reason.orElse(null)).put("remote", by.remote());
            return tx.preparedQuery(MARK).execute(Tuple.of(messageId, by.name(), detail, seq)).map(marked -> {
                Row a = marked.iterator().next();
                return new Stopping(new Event(a.getLong("id"), a.getOffsetDateTime("at").toInstant(), messageId, flow, by.name(), Kind.CANCEL,
                        OptionalInt.empty(), reason, Optional.empty()), node);
            });
        });
    }

    /** The cancel of {@code parked}, its DLQ row locked: refused beside a work row that is not stopped, else its stopped rows deleted. */
    private Future<Act> cancel(SqlConnection tx, Parked parked, Actor by, Optional<String> reason) {
        String key = parked.partitionKey().orElse(null);
        return tx.preparedQuery(ROWS).execute(Tuple.of(parked.messageId(), parked.flow(), key)).compose(rows -> {
            List<Long> stopped = new ArrayList<>();
            for (Row r : rows) {
                if (!r.getBoolean("stopped")) return Future.<Act>succeededFuture(new Unsettled(r.getLong("seq")));
                stopped.add(r.getLong("seq"));
            }
            Future<?> deleted = stopped.isEmpty() ? Future.succeededFuture()
                    : tx.preparedQuery(DELETE_STOPPED).execute(Tuple.of(stopped.toArray(Long[]::new)));
            String error = FailedMessages.cut(parked.error());
            JsonObject detail = new JsonObject().put("flow", parked.flow()).put("step", parked.step()).put("stepName", parked.stepName())
                    .put("parkedAt", parked.parkedAt().toString()).put("partitionKey", key)
                    .put("bodyHash", parked.bodyHash().algorithm() + ":" + parked.bodyHash().hex()).put("bodySize", parked.bodySize())
                    .put("reason", reason.orElse(null)).put("remote", by.remote()).put("error", error).put("errorCut", error.length() < parked.error().length());
            return deleted.compose(gone -> settle(tx, parked.messageId(), by, "message.cancel", detail)).compose(audit -> {
                Event event = new Event(audit.getLong("id"), audit.getOffsetDateTime("at").toInstant(), parked.messageId(), parked.flow(), by.name(),
                        Kind.CANCEL, OptionalInt.empty(), reason, Optional.of(error));
                if (key == null || stopped.isEmpty()) return Future.<Act>succeededFuture(new Done(event, Optional.empty()));
                return tx.preparedQuery(PgWorkQueue.NEXT_OF_KEY_NODE).execute(Tuple.of(parked.flow(), key))
                        .map(next -> new Done(event, Optional.ofNullable(next.iterator().next().getString(0))));
            });
        });
    }

    /** {@link #SETTLE}: the audit row written, the DLQ row the act holds locked deleted. */
    private static Future<Row> settle(SqlConnection tx, String messageId, Actor by, String action, JsonObject detail) {
        return tx.preparedQuery(SETTLE).execute(Tuple.of(messageId, by.name(), action, detail)).map(rows -> rows.iterator().next());
    }

    /** The key's lock on {@code tx} for a keyed message; nothing for one without a key. */
    private Future<Void> locked(SqlConnection tx, String flow, String partitionKey) {
        return partitionKey == null ? Future.succeededFuture() : queue.lockKeys(tx, List.of(Map.entry(flow, partitionKey)));
    }

    private static List<Row> list(RowSet<Row> rows) {
        List<Row> all = new ArrayList<>();
        rows.forEach(all::add);
        return all;
    }
}
