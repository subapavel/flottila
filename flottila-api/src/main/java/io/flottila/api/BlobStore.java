package io.flottila.api;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * Where the bytes of a {@link Body} live. Implementations tier memory, encrypted local spool and
 * S3 behind this interface (runtime, P1-01); tests use an in-memory one (testkit). Every write
 * carries the flow's finite maximum body size and fails with {@link BodyTooLargeException} above
 * it; a store hashes while it writes so the {@link Body} it returns is complete. Deletion belongs to the retention job, by
 * age (D-088, F-20), never on the hot path, and is not part of this contract; a removed key reads as never written: for a key with
 * nothing stored, {@link #read}, {@link #open} and {@link #persist} on a store with a durable tier fail with a
 * {@link BlobNotFoundException} naming it (without a durable tier {@code persist} fails with an
 * {@link IllegalStateException} first), and {@link #exists} answers false (D-096).
 *
 * @since 0.1
 */
public interface BlobStore {

    /** The largest body {@link Body#whole()} may materialise; larger bodies are stream-only. */
    long treeLimit();

    /** Stores a body that is already in memory. Fails with {@link BodyTooLargeException} above {@code maxSize}. */
    Future<Body> write(BlobKey key, Buffer whole, long maxSize);

    /**
     * Streams a body in while hashing. Fails with {@link BodyTooLargeException} once more than {@code maxSize} bytes arrived.
     * The stream may be handed over paused (a socket whose bytes must not flow before anyone listens): the store resumes it
     * once its handlers are attached — and never resumes a stream it has itself paused (back-pressure) or failed (the limit).
     */
    Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize);

    /**
     * A fresh cold stream of the stored bytes; may be called any number of times. For a key with nothing stored it throws a
     * {@link BlobNotFoundException} or returns a stream that fails with one before its first chunk.
     */
    ReadStream<Buffer> open(BlobKey key);

    /**
     * The whole stored object. Runtime-internal: steps and components go through {@link Body#whole()},
     * which enforces {@link #treeLimit()}, and the default {@link #exists}; nothing else may call this. Fails with a {@link BlobNotFoundException} for a key
     * with nothing stored.
     */
    Future<Buffer> read(BlobKey key);

    /**
     * Whether anything is stored under {@code key} (D-096): true, or false — an answer, not a failure — for a key with nothing
     * stored, never written or removed by retention; any other failure fails the future. This default reads the whole object,
     * unbounded by {@link #treeLimit()} — an accepted limit, so a store should override it with an answer that does not
     * fetch the bytes; every shipped store does (D-096).
     */
    default Future<Boolean> exists(BlobKey key) {
        return read(key).transform(read -> {
            if (read.succeeded()) return Future.succeededFuture(Boolean.TRUE);
            if (read.cause() instanceof BlobNotFoundException) return Future.succeededFuture(Boolean.FALSE);
            return Future.failedFuture(read.cause());
        });
    }

    /**
     * Makes the stored body durable (D-058, D-061): the future completes once the bytes outlive the process and every node
     * can read them by {@code key} — for a database tier, once the insert has committed. A key's durable bytes never change
     * (D-061 amended by FIX-35): persisting a key that is already durable with the same bytes completes again, quietly; with
     * other bytes — a key written again since — it fails with an {@link IllegalStateException} naming the key, nothing is
     * persisted and the body stays as written. Never deletes anything. Every failure is a failed future:
     * {@link BodyTooLargeException} above the store's durable bound (nothing is written, the body stays where it was),
     * {@link BlobNotFoundException} (an {@link IllegalArgumentException}) for a key with nothing stored, {@link IllegalStateException} for other bytes under a
     * durable key and when the store has no durable tier. An at-least-once delivery calls it on the ingress body before it
     * answers Accepted.
     */
    Future<Void> persist(BlobKey key);
}
