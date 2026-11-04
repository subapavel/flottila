package io.flottila.api;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c3 (D-105, CORE-3 "Review r2 additions"): a body an operator gave up with {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} reads as
 * "body lost by ACCEPT_LOSS" — a {@link BodyLostException} naming the key. It is not the miss signal (D-096's
 * {@link BlobNotFoundException}: nothing stored, never written or removed by retention), so {@link BlobStore#exists}'s default,
 * which answers by a read, fails with it instead of answering false.
 *
 * <p>The API assumed (CORE-4c3, test-author): {@code public BodyLostException(BlobKey key)}, {@code key()}, an unchecked
 * exception.
 */
class BodyLostExceptionTest {

    static final BlobKey KEY = BlobKey.body("m-given-up", 0);

    @Test
    void itNamesTheKeyAndSaysWhyAndIsNoMissSignal() {
        BodyLostException e = new BodyLostException(KEY);
        assertEquals(KEY, e.key());
        assertTrue(e.getMessage().contains("body lost by ACCEPT_LOSS"), e.getMessage());
        assertTrue(e.getMessage().contains(KEY.toString()), "names the key: " + e.getMessage());
        assertTrue(((Object) e) instanceof RuntimeException, "unchecked: a store's future fails with it");
        assertFalse(((Object) e) instanceof BlobNotFoundException, "not the miss signal: the body was given up, not removed");
        BlobKey attachment = BlobKey.attachment("m-given-up", 3, "invoice.pdf");
        assertEquals(attachment, new BodyLostException(attachment).key(), "an attachment's key as given");
    }

    @Test
    void existsByDefaultFailsWithItRatherThanAnsweringFalse() {
        BodyLostException lost = new BodyLostException(KEY);
        BlobStore store = new BlobStore() {
            @Override public long treeLimit() { return 1024; }
            @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) { throw new UnsupportedOperationException(); }
            @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) { throw new UnsupportedOperationException(); }
            @Override public ReadStream<Buffer> open(BlobKey key) { throw new UnsupportedOperationException(); }
            @Override public Future<Buffer> read(BlobKey key) { return Future.failedFuture(lost); }
            @Override public Future<Void> persist(BlobKey key) { throw new UnsupportedOperationException(); }
        };

        Future<Boolean> exists = store.exists(KEY);

        assertTrue(exists.failed(), "a lost body is neither there nor absent: " + exists);
        assertSame(lost, exists.cause());
    }
}
