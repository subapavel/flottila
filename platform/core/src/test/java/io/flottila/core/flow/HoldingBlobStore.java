package io.flottila.core.flow;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link BlobStore} over the testkit's {@link InMemoryBlobStore} whose {@link #persist} the test decides on. Bodies it writes
 * are its own ({@code body.store() == this}), so a delivery that persists "in the body's own store" reaches it. Not holding,
 * {@code persist} goes straight to the inner store. After {@link #hold()} every {@code persist} is kept pending — nothing
 * reaches the inner store — until {@link #release()} persists the held keys in the inner store and completes each future
 * with its outcome, {@link #fail(Throwable)} fails them, or {@link #answer(BlobKey, boolean)} settles one. {@link #throwNext}
 * makes the next {@code persist} throw instead of returning a future; after {@link #refuse(Throwable)} every {@code persist}
 * answers at once with a future failed by that cause (a store that cannot make the body durable: Postgres down, a body above
 * its bound). {@link #persists()} lists every key {@code persist} was called with — held, thrown, refused, failed or passed
 * alike — and {@link #persisted(BlobKey)} says whether the inner store made it durable.
 */
public final class HoldingBlobStore implements BlobStore {

    private final InMemoryBlobStore inner;
    private final Map<BlobKey, Promise<Void>> held = new LinkedHashMap<>();   // guarded by this
    private final List<BlobKey> persists = new ArrayList<>();                 // guarded by this
    private boolean holding;                                                  // guarded by this
    private RuntimeException throwNext;                                       // guarded by this
    private Throwable refusal;                                                // guarded by this

    public HoldingBlobStore(InMemoryBlobStore inner) {
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    /** From now on every {@code persist} waits for {@link #release()}, {@link #fail(Throwable)} or {@link #answer}. */
    public synchronized HoldingBlobStore hold() {
        holding = true;
        return this;
    }

    /** Persists waiting right now. */
    public synchronized int held() { return held.size(); }

    /** Stops holding; every held key is persisted in the inner store and its future completes as the inner store's does. */
    public void release() {
        for (Map.Entry<BlobKey, Promise<Void>> h : drain().entrySet()) inner.persist(h.getKey()).onComplete(h.getValue());
    }

    /** Stops holding; every held persist fails with {@code cause}, nothing is made durable. */
    public void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        for (Promise<Void> p : drain().values()) p.fail(cause);
    }

    /** Settles the held persist of {@code key} (quiet when none is held): {@code ok} persists it in the inner store, else fails it. */
    public void answer(BlobKey key, boolean ok) {
        Promise<Void> p;
        synchronized (this) {
            p = held.remove(key);
        }
        if (p == null) return;
        if (ok) inner.persist(key).onComplete(p);
        else p.fail(new IllegalStateException("persist failed for '" + key.messageId() + "'"));
    }

    private synchronized Map<BlobKey, Promise<Void>> drain() {
        holding = false;
        Map<BlobKey, Promise<Void>> out = new LinkedHashMap<>(held);
        held.clear();
        return out;
    }

    /** The next {@code persist} throws {@code failure} synchronously — no future — once; then persists behave as before. */
    public synchronized HoldingBlobStore throwNext(RuntimeException failure) {
        throwNext = Objects.requireNonNull(failure, "failure");
        return this;
    }

    /** From now on every {@code persist} fails at once with {@code cause} — the same instance each time — and makes nothing durable. */
    public synchronized HoldingBlobStore refuse(Throwable cause) {
        refusal = Objects.requireNonNull(cause, "cause");
        return this;
    }

    /** Every key {@code persist} was called with, in call order. */
    public synchronized List<BlobKey> persists() { return List.copyOf(persists); }

    /** Whether the inner store made {@code key} durable. */
    public boolean persisted(BlobKey key) { return inner.persisted(key); }

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
        synchronized (this) {
            persists.add(key);
            if (throwNext != null) {
                RuntimeException failure = throwNext;
                throwNext = null;
                throw failure;
            }
            if (refusal != null) return Future.failedFuture(refusal);
            if (holding) {
                Promise<Void> p = Promise.promise();
                held.put(key, p);
                return p.future();
            }
        }
        return inner.persist(key);
    }
}
