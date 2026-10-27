package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hasher;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryBlobStoreTest {

    final InMemoryBlobStore store = new InMemoryBlobStore(Hasher.sha256(), 16);
    final BlobKey key = BlobKey.body("m-1", 0);

    @Test
    void writesAndReadsBackWithHashAndSize() {
        Body body = store.write(key, Buffer.buffer("hello"), 100).await();
        assertEquals(5L, body.size());
        assertEquals(Hasher.sha256().hashOf(Buffer.buffer("hello")), body.hash());
        assertEquals("hello", body.whole().toString());
        assertEquals(16L, store.treeLimit());
        assertEquals(1, store.objects(), "one object stored");
    }

    @Test
    void eachOpenIsAFreshColdStreamWithItsOwnBytes() {
        Body body = store.write(key, Buffer.buffer("abc"), 100).await();
        List<String> seen = new ArrayList<>();
        body.open().handler(b -> { b.appendString("-mutated"); seen.add(b.toString()); });
        body.open().handler(b -> seen.add(b.toString()));
        assertEquals(List.of("abc-mutated", "abc"), seen, "a consumer mutating its buffer never changes the stored bytes");
        assertEquals("abc", body.whole().toString());
    }

    @Test
    void wholeWriteAboveMaxSizeFailsAndStoresNothing() {
        Future<Body> f = store.write(key, Buffer.buffer("0123456789"), 9);
        assertTrue(f.failed());
        BodyTooLargeException e = assertInstanceOf(BodyTooLargeException.class, f.cause());
        assertEquals(10L, e.size());
        assertEquals(9L, e.limit());
        assertEquals(key, e.key());
        assertEquals(0, store.objects());
    }

    @Test
    void writesExactlyAtMaxSizeAreAccepted() {
        assertEquals(10L, store.write(key, Buffer.buffer("0123456789"), 10).await().size());
        Body streamed = store.write(BlobKey.body("m-2", 0), new ChunkedReadStream(List.of("01234", "56789")), 10).await();
        assertEquals(10L, streamed.size());
        assertEquals(2, store.objects());
    }

    @Test
    void streamingWriteFailsAsSoonAsTheLimitIsExceededNotAtTheEnd() {
        ChunkedReadStream in = new ChunkedReadStream(List.of("0123", "4567", "89ab", "cdef"));
        Future<Body> f = store.write(key, in, 9);
        assertTrue(f.failed(), "must fail while the stream is still running");
        BodyTooLargeException e = assertInstanceOf(BodyTooLargeException.class, f.cause());
        assertEquals(9L, e.limit());
        assertTrue(e.size() > 9 && e.size() <= 12, "failed on the chunk that crossed the limit, saw " + e.size());
        assertEquals(3, in.delivered(), "the store stopped consuming after the crossing chunk");
        assertEquals(0, store.objects());
    }

    @Test
    void streamingWriteHashesChunkByChunk() {
        Body body = store.write(key, new ChunkedReadStream(List.of("he", "l", "lo")), 100).await();
        assertEquals(Hasher.sha256().hashOf(Buffer.buffer("hello")), body.hash());
        assertEquals(5L, body.size());
        assertEquals("hello", body.whole().toString());
    }

    @Test
    void streamFailurePropagatesAndStoresNothing() {
        ChunkedReadStream in = new ChunkedReadStream(List.of("he"), new IllegalStateException("connection reset"));
        Future<Body> f = store.write(key, in, 100);
        assertTrue(f.failed());
        assertEquals("connection reset", f.cause().getMessage());
        assertEquals(0, store.objects());
    }

    @Test
    void wholeAboveTheTreeLimitIsRefusedButOpenStillWorks() {
        Body body = store.write(key, Buffer.buffer("01234567890123456"), 100).await(); // 17 > tree limit 16
        assertThrows(BodyTooLargeException.class, body::whole);
        List<String> seen = new ArrayList<>();
        body.open().handler(b -> seen.add(b.toString()));
        assertEquals(List.of("01234567890123456"), seen);
    }

    @Test
    void unknownKeyFailsInsteadOfReturningNull() {
        assertTrue(store.read(BlobKey.body("nope", 0)).failed());
        assertThrows(IllegalArgumentException.class, () -> store.open(BlobKey.body("nope", 0)));
        assertFalse(store.contains(BlobKey.body("nope", 0)));
    }

    /** D-061: {@code persisted} answers for exactly the keys a successful persist made durable — not for a stored key, not for a refused one. */
    @Test
    void persistedSaysWhichKeysAPersistMadeDurable() {
        BlobKey other = BlobKey.body("m-2", 0);
        store.write(key, Buffer.buffer("kept"), 100).await();
        store.write(other, Buffer.buffer("stored only"), 100).await();
        assertFalse(store.persisted(key), "stored is not yet durable");
        store.persist(key).await();
        assertTrue(store.persisted(key));
        assertFalse(store.persisted(other), "only the persisted key");
        Future<Void> refused = store.persist(BlobKey.body("nope", 0));
        assertTrue(refused.failed());
        assertInstanceOf(IllegalArgumentException.class, refused.cause());
        assertFalse(store.persisted(BlobKey.body("nope", 0)), "a refused persist makes nothing durable");
    }
}
