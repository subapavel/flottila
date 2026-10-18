package io.flottila.api;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for the api tests only: bytes in a map, a counter of opened streams. The testkit owns the real fake (P0-04). */
final class InMemoryBlobStore implements BlobStore {

    private final Map<BlobKey, Buffer> blobs = new HashMap<>();
    private final long treeLimit;
    final AtomicInteger opened = new AtomicInteger();
    final AtomicInteger reads = new AtomicInteger();

    InMemoryBlobStore(long treeLimit) { this.treeLimit = treeLimit; }

    @Override public long treeLimit() { return treeLimit; }

    @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) {
        if (whole.length() > maxSize) return Future.failedFuture(new BodyTooLargeException(key, whole.length(), maxSize));
        blobs.put(key, whole.copy());
        return Future.succeededFuture(Body.of(this, key, Hasher.sha256().hashOf(whole), whole.length()));
    }

    @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) {
        Buffer all = Buffer.buffer();
        return Future.<Buffer>future(p -> {
            in.exceptionHandler(p::fail);
            in.handler(all::appendBuffer);
            in.endHandler(v -> p.complete(all));
        }).compose(b -> write(key, b, maxSize));
    }

    @Override public ReadStream<Buffer> open(BlobKey key) {
        opened.incrementAndGet();
        return new BufferReadStream(blobs.get(key));
    }

    @Override public Future<Buffer> read(BlobKey key) {
        reads.incrementAndGet();
        return Future.succeededFuture(blobs.get(key).copy());
    }
}
