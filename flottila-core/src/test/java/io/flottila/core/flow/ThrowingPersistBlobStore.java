package io.flottila.core.flow;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link BlobStore} over the testkit's {@link InMemoryBlobStore} whose {@code persist} can throw any {@link Throwable}, an
 * {@link Error} or a checked one, as it is — {@link ParkingTest#sneaky} rethrows without wrapping, so no
 * {@code UndeclaredThrowableException} stands in for it. Bodies it writes are its own ({@code body.store() == this}). Until
 * {@link #throwOnPersist} {@code persist} goes to the inner store; every call is noted in {@link #persists()}.
 */
final class ThrowingPersistBlobStore implements BlobStore {

    private final InMemoryBlobStore inner;
    private final List<BlobKey> persists = new CopyOnWriteArrayList<>();
    private volatile Throwable thrown;

    ThrowingPersistBlobStore(InMemoryBlobStore inner) {
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    /** From now on every {@code persist} throws {@code failure} itself, synchronously, with no future. */
    ThrowingPersistBlobStore throwOnPersist(Throwable failure) {
        thrown = Objects.requireNonNull(failure, "failure");
        return this;
    }

    /** Every key {@code persist} was called with, in call order. */
    List<BlobKey> persists() { return List.copyOf(persists); }

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
        Objects.requireNonNull(key, "key");
        persists.add(key);
        Throwable failure = thrown;
        if (failure != null) throw ParkingTest.sneaky(failure);
        return inner.persist(key);
    }
}
