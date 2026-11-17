package dev.rail8.runtime.node;

import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobStore;
import dev.rail8.api.Body;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.Objects;

/**
 * The node's blob store as its components see it (CORE-4c2): the components are built, and a mock binding refused, before the
 * node touches Postgres, but the store writes to the body journal, which opens only after the migration. So the
 * components get this store, and {@link #bind} hands it the node's store once the journal is open — before any flow starts, so
 * every body a component writes goes to the node's store and names it ({@link Body#store}). Until then every call but
 * {@link #treeLimit} throws {@link IllegalStateException}: only a component that writes from its own construction meets it.
 */
final class LateBlobStore implements BlobStore {

    private final long treeLimit;
    private volatile BlobStore bound;

    LateBlobStore(long treeLimit) {
        this.treeLimit = treeLimit;
    }

    /** From here on every call is {@code store}'s. */
    void bind(BlobStore store) {
        this.bound = Objects.requireNonNull(store, "store");
    }

    private BlobStore store() {
        BlobStore store = bound;
        if (store == null) {
            throw new IllegalStateException("the node's blob store opens with its journal, after the components are built: a component "
                    + "writes or reads bodies once its flow runs, never while it is built");
        }
        return store;
    }

    @Override
    public long treeLimit() {
        return treeLimit;
    }

    @Override
    public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
        return store().write(key, whole, maxSize);
    }

    @Override
    public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
        return store().write(key, in, maxSize);
    }

    @Override
    public ReadStream<Buffer> open(BlobKey key) {
        return store().open(key);
    }

    @Override
    public Future<Buffer> read(BlobKey key) {
        return store().read(key);
    }

    @Override
    public Future<Boolean> exists(BlobKey key) {
        return store().exists(key);
    }

    @Override
    public Future<Void> persist(BlobKey key) {
        return store().persist(key);
    }
}
