package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.Objects;
import java.util.Optional;

/**
 * The Postgres tier of {@link TieredBlobStore} (D-058, D-061): the {@code bodies} table ({@code V6__bodies.sql}), keyed by
 * message id, step and part. A body goes in with one autocommit insert, {@code on conflict do nothing} — the first durable
 * bytes win for a key — whose future completes once the insert has committed (the pool answers on the server's
 * ReadyForQuery, after the statement's implicit transaction). It reads a body back by key. It never deletes: only the
 * retention job does (P2-07). Its bound is finite: at least one byte, at most 64 MiB, one body being one {@code bytea}.
 *
 * @since 0.1
 */
public final class PgBodies {

    /** The largest durable bound: {@code FLOTTILA_BLOB_DURABLE_MAX} allows no more either. */
    static final long MAX_BOUND = 64L << 20;

    private static final String INSERT = "insert into bodies (message_id, step, part, bytes) values ($1, $2, $3, $4) on conflict do nothing";
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

    /** Inserts the body unless a row for {@code key} exists (then nothing changes); completes once the insert has committed. */
    Future<Void> insert(BlobKey key, Buffer bytes) {
        return pool.preparedQuery(INSERT).execute(Tuple.of(key.messageId(), key.step(), key.part(), bytes)).mapEmpty();
    }

    /** The bytes stored under {@code key}, or empty. */
    Future<Optional<Buffer>> select(BlobKey key) {
        return pool.preparedQuery(SELECT).execute(of(key)).map(rows -> {
            for (Row r : rows) return Optional.of(r.getBuffer(0));
            return Optional.empty();
        });
    }

    /** Whether a row exists for {@code key}. */
    Future<Boolean> exists(BlobKey key) {
        return pool.preparedQuery(EXISTS).execute(of(key)).map(rows -> rows.size() > 0);
    }

    private static Tuple of(BlobKey key) { return Tuple.of(key.messageId(), key.step(), key.part()); }
}
