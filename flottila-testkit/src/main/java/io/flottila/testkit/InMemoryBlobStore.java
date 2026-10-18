package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hasher;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link BlobStore} that keeps everything on the heap, for tests. It enforces the same contract as
 * the real stores: it hashes while it writes, fails a write the moment the size limit is crossed
 * and then stores nothing, and hands out a fresh cold stream with its own bytes on every
 * {@link #open(BlobKey)}.
 *
 * @since 0.1
 */
public final class InMemoryBlobStore implements BlobStore {

    private final Hasher hasher;
    private final long treeLimit;
    private final Map<BlobKey, Buffer> objects = new ConcurrentHashMap<>();

    public InMemoryBlobStore(Hasher hasher, long treeLimit) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.treeLimit = treeLimit;
    }

    @Override public long treeLimit() { return treeLimit; }

    @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
        long length = whole.length();
        if (length > maxSize) return Future.failedFuture(new BodyTooLargeException(key, length, maxSize));
        objects.put(key, whole.copy());
        return Future.succeededFuture(Body.of(this, key, hasher.hashOf(whole), length));
    }

    @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
        Promise<Body> result = Promise.promise();
        Hasher.Session hash = hasher.begin();
        Buffer collected = Buffer.buffer();
        in.exceptionHandler(result::tryFail);
        in.endHandler(v -> {
            if (result.future().isComplete()) return;
            objects.put(key, collected);
            result.complete(Body.of(this, key, hash.finish(), collected.length()));
        });
        in.handler(chunk -> {
            if (result.future().isComplete()) return;
            long seen = (long) collected.length() + chunk.length();
            if (seen > maxSize) {
                result.fail(new BodyTooLargeException(key, seen, maxSize));
                in.pause();
                return;
            }
            hash.update(chunk);
            collected.appendBuffer(chunk);
        });
        return result.future();
    }

    @Override public ReadStream<Buffer> open(BlobKey key) {
        Buffer stored = objects.get(key);
        if (stored == null) throw new IllegalArgumentException("no object stored under " + key);
        return new ChunkedReadStream(List.of(stored));
    }

    @Override public Future<Buffer> read(BlobKey key) {
        Buffer stored = objects.get(key);
        if (stored == null) return Future.failedFuture(new IllegalArgumentException("no object stored under " + key));
        return Future.succeededFuture(stored.copy());
    }

    /** Whether an object is stored under {@code key}. */
    public boolean contains(BlobKey key) { return objects.containsKey(key); }

    /** The number of stored objects. */
    public int objects() { return objects.size(); }
}
