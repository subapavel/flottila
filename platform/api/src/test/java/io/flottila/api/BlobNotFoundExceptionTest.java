package io.flottila.api;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-07b1 (D-096): the miss signal of a {@link BlobStore} — a key with nothing stored, never written or removed by retention —
 * is a {@link BlobNotFoundException} naming the key, an {@link IllegalArgumentException} (D-061's contract holds) and no
 * {@link Fault}; and {@link BlobStore#exists}'s default answers by a read: true when it succeeds, false when it fails with the
 * miss signal, failed with any other failure (a plain {@link IllegalArgumentException} included: only the miss signal is "absent").
 */
class BlobNotFoundExceptionTest {

    static final BlobKey KEY = BlobKey.body("m-gone", 2);

    @Test
    void itNamesTheKeyAndIsAnIllegalArgumentButNoFault() {
        BlobNotFoundException e = new BlobNotFoundException(KEY);
        assertEquals(KEY, e.key());
        assertEquals("no object stored under " + KEY, e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e, "D-061: a key with nothing stored is the caller's IllegalArgumentException");
        assertFalse(((Object) e) instanceof Fault, "not a Fault: whose fault a missing body is depends on who asked");
        BlobKey attachment = BlobKey.attachment("m-gone", 2, "invoice.pdf");
        assertEquals(attachment, new BlobNotFoundException(attachment).key(), "an attachment's key as given");
        assertEquals("no object stored under " + attachment, new BlobNotFoundException(attachment).getMessage());
    }

    /** A store whose read answers what {@code answer} gives for the key; everything else fails the case. Records every call. */
    static final class ReadOnly implements BlobStore {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Function<BlobKey, Future<Buffer>> answer;

        ReadOnly(Function<BlobKey, Future<Buffer>> answer) { this.answer = answer; }

        @Override public long treeLimit() { return 4096; }
        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) { throw new AssertionError("no write: " + key); }
        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> in, long maxSize) { throw new AssertionError("no write: " + key); }
        @Override public ReadStream<Buffer> open(BlobKey key) { throw new AssertionError("no open: " + key); }
        @Override public Future<Buffer> read(BlobKey key) {
            calls.add("read " + key);
            return answer.apply(key);
        }
        @Override public Future<Void> persist(BlobKey key) { throw new AssertionError("no persist: " + key); }
    }

    @Test
    void theDefaultExistsIsTrueForAReadThatSucceedsAndFalseForTheMissSignal() {
        ReadOnly stored = new ReadOnly(k -> Future.succeededFuture(Buffer.buffer("<invoice/>")));
        Future<Boolean> yes = stored.exists(KEY);
        assertTrue(yes.succeeded(), String.valueOf(yes.cause()));
        assertEquals(Boolean.TRUE, yes.result());
        ReadOnly empty = new ReadOnly(k -> Future.succeededFuture(Buffer.buffer()));
        assertEquals(Boolean.TRUE, empty.exists(KEY).result(), "an empty body is a body: it exists");
        ReadOnly missing = new ReadOnly(k -> Future.failedFuture(new BlobNotFoundException(k)));
        Future<Boolean> no = missing.exists(KEY);
        assertTrue(no.succeeded(), "the miss signal is an answer, not a failure: " + no.cause());
        assertEquals(Boolean.FALSE, no.result());
        assertEquals(List.of("read " + KEY), missing.calls, "the default asks the store by a read of that key");
    }

    @Test
    void theDefaultExistsFailsWithEveryOtherFailureOfTheRead() {
        IllegalStateException down = new IllegalStateException("postgres down");
        Future<Boolean> failed = new ReadOnly(k -> Future.failedFuture(down)).exists(KEY);
        assertTrue(failed.failed(), "a store that cannot answer is no 'absent': " + failed);
        assertSame(down, failed.cause());
        IllegalArgumentException plain = new IllegalArgumentException("not the miss signal");
        Future<Boolean> notTheSignal = new ReadOnly(k -> Future.failedFuture(plain)).exists(KEY);
        assertTrue(notTheSignal.failed(), "only a BlobNotFoundException is 'absent', not any IllegalArgumentException: " + notTheSignal);
        assertSame(plain, notTheSignal.cause());
    }

    @Test
    void wholePassesTheMissSignalThrough() {
        ReadOnly missing = new ReadOnly(k -> Future.failedFuture(new BlobNotFoundException(k)));
        Body body = Body.of(missing, KEY, Hasher.sha256().hashOf(Buffer.buffer("x")), 1);
        BlobNotFoundException e = assertThrows(BlobNotFoundException.class, body::whole);
        assertEquals(KEY, e.key());
    }
}
