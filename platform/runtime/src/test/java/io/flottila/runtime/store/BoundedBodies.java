package io.flottila.runtime.store;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2-08a test fixture: the testkit's {@link InMemoryBlobStore} with a durable bound, as {@link BlobStore#persist} documents one —
 * a stored body above {@code bound} bytes fails its persist with {@link BodyTooLargeException} and nothing is made durable;
 * every other persist is the inner store's (FIX-35: other bytes under a durable key are refused there). Bodies it writes are
 * its own ({@code body.store() == this}). {@link #durable} answers the bytes a successful persist made durable under a key —
 * the first, as the inner store keeps them.
 */
final class BoundedBodies implements BlobStore {

    private final InMemoryBlobStore inner;
    private final long bound;
    private final Map<BlobKey, Buffer> durable = new ConcurrentHashMap<>();

    BoundedBodies(InMemoryBlobStore inner, long bound) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.bound = bound;
    }

    /** The bytes durable under {@code key}, or empty. */
    Optional<Buffer> durable(BlobKey key) {
        return Optional.ofNullable(durable.get(key));
    }

    @Override public long treeLimit() { return inner.treeLimit(); }

    @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
        return inner.write(key, whole, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
    }

    @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
        return inner.write(key, in, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
    }

    @Override public ReadStream<Buffer> open(BlobKey key) { return inner.open(key); }

    @Override public Future<Buffer> read(BlobKey key) { return inner.read(key); }

    @Override public Future<Void> persist(BlobKey key) {
        if (!inner.contains(key)) return inner.persist(key);   // the inner store's refusal of a key with nothing stored
        return inner.read(key).compose(bytes -> {
            if (bytes.length() > bound) return Future.failedFuture(new BodyTooLargeException(key, bytes.length(), bound));
            return inner.persist(key).onSuccess(v -> durable.putIfAbsent(key, bytes.copy()));
        });
    }
}
