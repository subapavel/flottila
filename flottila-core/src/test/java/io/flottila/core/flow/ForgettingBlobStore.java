package io.flottila.core.flow;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P2-07b1 (D-096): a {@link BlobStore} over the testkit's {@link InMemoryBlobStore} whose bytes the test can take away as the
 * retention job does. Bodies it writes are its own ({@code body.store() == this}). A {@link #forget forgotten} key reads as never
 * written: {@code read} and {@code persist} fail with a {@link BlobNotFoundException} naming it, {@code open} throws one,
 * {@code exists} is false. {@link #forgetAfterExists} lets a key be found by the next {@code exists} and forgets it right after
 * (a body removed between a check and the read); {@link #failExists} fails every {@code exists} with that cause (a store that
 * cannot answer). Every call is an event, {@code "<method> <key>"}, in {@link #events()}.
 */
final class ForgettingBlobStore implements BlobStore {

    private final InMemoryBlobStore inner;
    private final Set<BlobKey> forgotten = ConcurrentHashMap.newKeySet();
    private final Set<BlobKey> forgetAfterExists = ConcurrentHashMap.newKeySet();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile Throwable existsFails;

    ForgettingBlobStore(InMemoryBlobStore inner) {
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    /** From now on {@code key} reads as never written. */
    void forget(BlobKey key) { forgotten.add(key); }

    /** The next {@code exists} of {@code key} finds it; the key is forgotten right after that answer. */
    void forgetAfterExists(BlobKey key) { forgetAfterExists.add(key); }

    /** Every {@code exists} from now on fails with {@code cause}. */
    void failExists(Throwable cause) { existsFails = cause; }

    /** Every call so far, {@code "<method> <key>"}, in order. */
    List<String> events() { return List.copyOf(events); }

    /** The events after the first {@code from}. */
    List<String> eventsSince(int from) { return List.copyOf(events.subList(from, events.size())); }

    int eventCount() { return events.size(); }

    @Override public long treeLimit() { return inner.treeLimit(); }

    @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
        events.add("write " + key);
        return inner.write(key, whole, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
    }

    @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
        events.add("write " + key);
        return inner.write(key, in, maxSize).map(b -> Body.of(this, b.key(), b.hash(), b.size()));
    }

    @Override public ReadStream<Buffer> open(BlobKey key) {
        events.add("open " + key);
        if (forgotten.contains(key)) throw new BlobNotFoundException(key);
        return inner.open(key);
    }

    @Override public Future<Buffer> read(BlobKey key) {
        events.add("read " + key);
        if (forgotten.contains(key)) return Future.failedFuture(new BlobNotFoundException(key));
        return inner.read(key);
    }

    @Override public Future<Void> persist(BlobKey key) {
        events.add("persist " + key);
        if (forgotten.contains(key)) return Future.failedFuture(new BlobNotFoundException(key));
        return inner.persist(key);
    }

    @Override public Future<Boolean> exists(BlobKey key) {
        events.add("exists " + key);
        Throwable fails = existsFails;
        if (fails != null) return Future.failedFuture(fails);
        boolean found = !forgotten.contains(key) && inner.contains(key);
        if (forgetAfterExists.remove(key)) forgotten.add(key);
        return Future.succeededFuture(found);
    }
}
