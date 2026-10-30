package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Objects;
import java.util.Optional;

/**
 * The Postgres tier of {@link TieredBlobStore} (D-058, D-061): the {@code bodies} table ({@code V6__bodies.sql}), keyed by
 * message id, step and part. A body goes in with one insert statement on the client the caller gives: the pool — an
 * autocommit statement whose future completes once it has committed (the pool answers on the server's ReadyForQuery, after
 * the statement's implicit transaction) — or a transaction's connection, which commits it with the rest of that transaction
 * (D-082). A key's durable bytes never change (D-061 amended by FIX-35 and D-088): when the insert meets the key's row, the
 * same statement compares that row's bytes with these, byte for byte in Postgres — never by the body's 64-bit hash — under
 * the row lock its {@code on conflict do update} takes, and leaves the row as it was ({@code created_at} included); no
 * second statement, so no delete of the retention job can come between the conflict and the compare.
 * It reads a body back by key. It never deletes: only the retention job does ({@code store.Retention}, P2-07). Its bound is
 * finite: at least one byte, at most 64 MiB, one body being one {@code bytea}.
 *
 * @since 0.1
 */
public final class PgBodies {

    /** The largest durable bound: {@code FLOTTILA_BLOB_DURABLE_MAX} allows no more either. */
    static final long MAX_BOUND = 64L << 20;

    /**
     * One row returned: inserted, or the key's row met holding these bytes (rewritten unchanged under its row lock); none: the
     * row holds other bytes and stays as it was.
     */
    private static final String INSERT = "insert into bodies (message_id, step, part, bytes) values ($1, $2, $3, $4) "
            + "on conflict (message_id, step, part) do update set created_at = bodies.created_at where bodies.bytes = excluded.bytes returning 1";
    private static final String SELECT = "select bytes from bodies where message_id = $1 and step = $2 and part = $3";
    private static final String EXISTS = "select 1 from bodies where message_id = $1 and step = $2 and part = $3";

    private final Pool pool;
    private final long maxBytes;

    /**
     * The tier over {@code pool}, holding bodies of at most {@code maxBytes} bytes.
     *
     * @throws IllegalArgumentException a bound below one byte or above 64 MiB
     */
    public PgBodies(Pool pool, long maxBytes) {
        this.pool = Objects.requireNonNull(pool, "pool");
        if (maxBytes < 1 || maxBytes > MAX_BOUND) {
            throw new IllegalArgumentException("the durable bound must be 1 byte to " + MAX_BOUND + " bytes, was " + maxBytes);
        }
        this.maxBytes = maxBytes;
    }

    /** The largest body this tier takes. */
    long maxBytes() { return maxBytes; }

    /** The tier's pool: the client of a persist that shares no transaction. */
    Pool pool() { return pool; }

    /**
     * Inserts the body on {@code client} unless a row for {@code key} exists, then the row's bytes and age stay as they were;
     * completes once the statement ran — on the pool, once it has committed. {@code true} when the key's row holds these bytes:
     * inserted now, or met holding the same bytes (compared in the same statement, after the conflicting row has committed);
     * {@code false} when the row holds other bytes.
     */
    Future<Boolean> insert(SqlClient client, BlobKey key, Buffer bytes) {
        return client.preparedQuery(INSERT).execute(Tuple.of(key.messageId(), key.step(), key.part(), bytes)).map(rows -> rows.rowCount() == 1);
    }

    /** The bytes stored under {@code key}, or empty. */
    Future<Optional<Buffer>> select(BlobKey key) {
        return pool.preparedQuery(SELECT).execute(of(key)).map(rows -> {
            for (Row r : rows) return Optional.of(r.getBuffer(0));
            return Optional.empty();
        });
    }

    /** Whether a row exists for {@code key}, as {@code client} sees it. */
    Future<Boolean> exists(SqlClient client, BlobKey key) {
        return client.preparedQuery(EXISTS).execute(of(key)).map(rows -> rows.size() > 0);
    }

    private static Tuple of(BlobKey key) { return Tuple.of(key.messageId(), key.step(), key.part()); }
}
