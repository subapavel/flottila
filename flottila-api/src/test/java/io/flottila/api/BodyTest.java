package io.flottila.api;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BodyTest {

    final InMemoryBlobStore store = new InMemoryBlobStore(10);
    final BlobKey key = BlobKey.body("m-1", 0);

    private Body written(String text) {
        return store.write(key, Buffer.buffer(text), 1_000).await();
    }

    @Test
    void bodyIsAHandleWithHashSizeAndKey() {
        Body body = written("abc");
        assertEquals(3L, body.size());
        assertEquals(Hasher.sha256().hashOf(Buffer.buffer("abc")), body.hash());
        assertEquals(key, body.key());
        assertEquals(0, store.reads.get(), "writing must not read the bytes back");
    }

    @Test
    void openReturnsAFreshColdStreamEveryTime() {
        Body body = written("abc");
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            body.open().handler(b -> seen.add(b.toString()));
        }
        assertEquals(List.of("abc", "abc", "abc"), seen);
        assertEquals(3, store.opened.get(), "every open() asks the store for a new stream");
    }

    @Test
    void wholeMaterialisesBelowTheTreeLimit() {
        Body body = written("abc");
        assertEquals("abc", body.whole().toString());
        assertEquals("abc", body.whole().toString());
        assertEquals(2, store.reads.get(), "the Body does not cache bytes; every whole() reads again");
    }

    @Test
    void wholeAcceptsABodyExactlyAtTheTreeLimit() {
        BlobKey exact = BlobKey.body("m-1", 2);
        Body body = store.write(exact, Buffer.buffer("0123456789"), 1_000).await(); // 10 bytes == treeLimit 10
        assertEquals("0123456789", body.whole().toString());
    }

    @Test
    void emptyBodyIsAValidBody() {
        Body empty = Body.of(store, key, new Hash("x", 0L), 0L);
        assertEquals(0L, empty.size());
    }

    @Test
    void wholeRefusesABodyAboveTheTreeLimit() {
        BlobKey big = BlobKey.body("m-1", 1);
        Body body = store.write(big, Buffer.buffer("0123456789a"), 1_000).await(); // 11 bytes > treeLimit 10
        BodyTooLargeException e = assertThrows(BodyTooLargeException.class, body::whole);
        assertEquals(big, e.key());
        assertEquals(11L, e.size());
        assertEquals(10L, e.limit());
        assertEquals(0, store.reads.get(), "nothing is read when the limit refuses");
    }

    @Test
    void storeRefusesAWriteAboveTheFlowsMaxBodySize() {
        BodyTooLargeException e = assertThrows(BodyTooLargeException.class,
                () -> store.write(key, Buffer.buffer("0123"), 3).await());
        assertEquals(4L, e.size());
        assertEquals(3L, e.limit());
    }

    @Test
    void streamingWriteHashesWhileReading() {
        Body body = store.write(key, new BufferReadStream(Buffer.buffer("abc")), 1_000).await();
        assertEquals(Hasher.sha256().hashOf(Buffer.buffer("abc")), body.hash());
        assertEquals(3L, body.size());
    }

    @Test
    void bodyEqualityIsByKeyHashAndSize() {
        Body a = Body.of(store, key, new Hash("x", 1L), 3L);
        Body b = Body.of(store, key, new Hash("x", 1L), 3L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        Body other = Body.of(store, BlobKey.body("m-2", 0), new Hash("x", 1L), 3L);
        assertNotEquals(a, other);
        assertNotEquals(a.hashCode(), other.hashCode(), "different keys hash differently");
        assertSame(store, a.store());
    }
}
