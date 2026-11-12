package io.flottila.runtime.queue;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Parked;
import io.flottila.runtime.store.PgDlq;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * CORE-14b fixture: the Postgres rows a worker's park leaves behind (P6) — the ingress body's kept ref ({@code body_refs} row
 * {@code <id>/0/body} and its {@code journal_segments} row on a node) and the DLQ row, written by the real {@link PgDlq#park}
 * (which admits a row only while that ref exists, D-096).
 *
 * <p>The API assumed (CORE-14b O7, test-author): {@code Optional<String> Parked.partitionKey()} — the item's key the park kept, empty
 * for an unkeyed item and for a row parked before V17 — as the record's last component; the 13-component constructor keeps none.
 */
public final class ParkedRows {

    public static final Instant PARKED_AT = Instant.parse("2026-11-12T10:00:00Z");

    private ParkedRows() {}

    /** The kept ref of {@code messageId}'s ingress body in {@code node}'s journal, segment 1; written once. */
    public static void keepRef(Pool pool, String messageId, String node) {
        pool.preparedQuery("insert into journal_segments(node, segment, copies) values ($1, 1, '{}') on conflict do nothing")
                .execute(Tuple.of(node)).await();
        pool.preparedQuery("insert into body_refs(message_id, step, part, node, segment, position, length) values ($1, 0, 'body', $2, 1, 0, 16)"
                + " on conflict do nothing").execute(Tuple.of(messageId, node)).await();
    }

    /** The DLQ row of {@code messageId} at {@code step} on its ingress body, its props {@code props}, no partition key kept. */
    public static Parked row(String messageId, String flow, int step, Props props) {
        return row(messageId, flow, step, props, Optional.empty());
    }

    /** {@link #row(String, String, int, Props)} keeping {@code partitionKey} (O7, V17 {@code dlq.partition_key}). */
    public static Parked row(String messageId, String flow, int step, Props props, Optional<String> partitionKey) {
        return new Parked(messageId, flow, step, "step-" + step, "partner down (test)", PARKED_AT, Headers.empty(), props, Keys.empty(),
                new Hash("sha256-64", 42L), 16, BlobKey.body(messageId, 0), Map.of(), partitionKey);
    }

    /** The row a worker's park leaves (P6): step 1, prop {@code attempt} = {@code attempt}, no key (an unkeyed flow's item). */
    public static Parked byTheWorker(String messageId, String flow, int attempt) {
        return byTheWorker(messageId, flow, attempt, Optional.empty());
    }

    /** The row a worker's park leaves of an item under {@code partitionKey} (O7). */
    public static Parked byTheWorker(String messageId, String flow, int attempt, Optional<String> partitionKey) {
        return row(messageId, flow, 1, Props.empty().with(Envelope.ATTEMPT, attempt), partitionKey);
    }

    /** {@code p} parked through {@link PgDlq#park}, its body's kept ref on {@code node} written first. */
    public static void park(Pool pool, Parked p, String node) {
        keepRef(pool, p.bodyKey().messageId(), node);
        new PgDlq(pool).park(p).await();
    }

    public static long count(Pool pool, String sql, Object... params) {
        return pool.preparedQuery(sql).execute(Tuple.wrap(params)).await().iterator().next().getLong(0);
    }
}
