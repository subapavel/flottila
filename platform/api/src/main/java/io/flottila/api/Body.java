package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.Objects;

/**
 * The main payload of an {@link Envelope}: a handle to content, never a stream and never a byte
 * array. One final class, no visible variants; where the bytes live is the {@link BlobStore}'s
 * concern. {@link #open()} returns a fresh cold stream any number of times; {@link #whole()}
 * materialises only below the store's tree limit (ARCHITECTURE §5).
 *
 * @since 0.1
 */
public final class Body {

    private final BlobStore store;
    private final BlobKey key;
    private final Hash hash;
    private final long size;

    private Body(BlobStore store, BlobKey key, Hash hash, long size) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
        this.hash = Objects.requireNonNull(hash, "hash");
        if (size < 0) throw new IllegalArgumentException("size < 0");
        this.size = size;
    }

    /** Created by a {@link BlobStore} after it has written and hashed the bytes. */
    public static Body of(BlobStore store, BlobKey key, Hash hash, long size) {
        return new Body(store, key, hash, size);
    }

    /** Content hash, computed on arrival; the identity for dedup and monitoring. */
    public Hash hash() { return hash; }

    /** Size in bytes. */
    public long size() { return size; }

    /** Where the bytes live. */
    public BlobKey key() { return key; }

    /** The store holding the bytes. */
    public BlobStore store() { return store; }

    /** A fresh cold stream of the bytes; call it as often as needed (retry, replay, snapshot). */
    public ReadStream<Buffer> open() {
        return store.open(key);
    }

    /**
     * The whole body in memory — only below {@link BlobStore#treeLimit()}. Blocks the calling
     * (virtual) thread while the store reads.
     *
     * @throws BodyTooLargeException when the body is above the tree limit
     */
    public Buffer whole() {
        long limit = store.treeLimit();
        if (size > limit) throw new BodyTooLargeException(key, size, limit);
        return store.read(key).await();
    }

    @Override public boolean equals(Object o) {
        return o instanceof Body b && key.equals(b.key) && hash.equals(b.hash) && size == b.size;
    }

    @Override public int hashCode() {
        return Objects.hash(key, hash, size);
    }

    @Override public String toString() {
        return "Body[" + key + ", " + hash.algorithm() + ":" + hash.hex() + ", " + size + " bytes]";
    }
}
