package io.flottila.api;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * Where the bytes of a {@link Body} live. Implementations tier memory, encrypted local spool and
 * S3 behind this interface (runtime, P1-01); tests use an in-memory one (testkit). Every write
 * carries the flow's finite maximum body size and fails with {@link BodyTooLargeException} above
 * it; a store hashes while it writes so the {@link Body} it returns is complete. Deletion is a
 * retention job by partition, never on the hot path, and therefore not part of this contract.
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

    /** A fresh cold stream of the stored bytes; may be called any number of times. */
    ReadStream<Buffer> open(BlobKey key);

    /**
     * The whole stored object. Runtime-internal: steps and components go through {@link Body#whole()},
     * which enforces {@link #treeLimit()}; nothing else may call this.
     */
    Future<Buffer> read(BlobKey key);
}
