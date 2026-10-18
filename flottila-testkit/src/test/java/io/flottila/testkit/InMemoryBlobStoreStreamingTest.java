package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hasher;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Streaming-write edge cases and the stored-bytes isolation the main store tests leave open. */
class InMemoryBlobStoreStreamingTest {

    final InMemoryBlobStore store = new InMemoryBlobStore(Hasher.sha256(), 1024);
    final BlobKey key = BlobKey.body("m-1", 0);

    @Test
    void aStreamWhoseLastChunkCrossesTheLimitFailsOnceAndStoresNothing() {
        Future<?> f = store.write(key, new ChunkedReadStream(List.of("01234", "56789")), 9);
        assertInstanceOf(BodyTooLargeException.class, f.cause());
        assertEquals(10L, ((BodyTooLargeException) f.cause()).size());
        assertEquals(0, store.objects());
    }

    @Test
    void aStreamExactlyAtTheLimitIsAccepted() {
        assertEquals(9L, store.write(key, new ChunkedReadStream(List.of("01234", "5678")), 9).await().size());
        assertTrue(store.contains(key));
    }

    @Test
    void anEmptyStreamStoresAnEmptyObject() {
        assertEquals(0L, store.write(key, new ChunkedReadStream(List.of()), 9).await().size());
        assertEquals("", store.read(key).await().toString());
    }

    @Test
    void readReturnsACopyAndWritingCopiesTheInput() {
        Buffer source = Buffer.buffer("abc");
        store.write(key, source, 100).await();
        source.appendString("-changed");
        store.read(key).await().appendString("-changed");
        assertEquals("abc", store.read(key).await().toString());
    }
}
