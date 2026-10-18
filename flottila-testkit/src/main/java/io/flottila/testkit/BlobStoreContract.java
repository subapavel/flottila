package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract every {@link BlobStore} must honour — the in-memory testkit store and every runtime tier
 * run the same cases (CLAUDE.md: real and fake pass the same suite). Subclasses supply a fresh store per
 * test; {@link #bigSize()} is the streamed pass-through size (raise it for a tier that spills or
 * uploads). Blocking waits happen on the calling thread, which a subclass may keep virtual.
 *
 * @since 0.1
 */
public abstract class BlobStoreContract {

    public static final long TREE_LIMIT = 4096;

    /** A fresh, empty store whose {@link BlobStore#treeLimit()} is {@link #TREE_LIMIT}. */
    protected abstract BlobStore store();

    protected Hasher hasher() { return Hasher.sha256(); }

    /** Size of the streamed pass-through body; well above the tree limit and any memory tier. */
    protected long bigSize() { return 3L * 1024 * 1024; }

    protected static BlobKey key(String id, int step) { return BlobKey.body(id, step); }

    @Test
    public void aWholeBufferRoundTripsWithItsHashAndSize() {
        BlobStore s = store();
        Buffer bytes = Buffer.buffer("<invoice id='4711'/>");
        Body body = await(s.write(key("m-1", 0), bytes, TREE_LIMIT));
        assertEquals(hasher().hashOf(bytes), body.hash());
        assertEquals(bytes.length(), body.size());
        assertEquals(key("m-1", 0), body.key());
        assertEquals(bytes, body.whole());
        assertEquals(bytes, await(s.read(key("m-1", 0))));
    }

    @Test
    public void aStreamedBodyIsHashedWhileItIsWrittenAndReadsBackIntact() {
        BlobStore s = store();
        ReadStream<Buffer> in = new ChunkedReadStream(List.of("<a>", "bbb", "</a>"));
        Body body = await(s.write(key("m-2", 1), in, TREE_LIMIT));
        assertEquals(hasher().hashOf(Buffer.buffer("<a>bbb</a>")), body.hash(), "the hash covers every chunk in order");
        assertEquals(10, body.size());
        assertEquals("<a>bbb</a>", await(collect(s.open(key("m-2", 1)))).toString());
    }

    @Test
    public void openIsAFreshColdStreamEveryTime() {
        BlobStore s = store();
        Body body = await(s.write(key("m-3", 0), Buffer.buffer("twice"), TREE_LIMIT));
        Buffer first = await(collect(body.open()));
        Buffer second = await(collect(body.open()));
        assertEquals("twice", first.toString());
        assertEquals(first, second, "a second open yields the same bytes from the start");
    }

    @Test
    public void wholeAboveTheTreeLimitThrowsWhileOpenStillStreams() {
        BlobStore s = store();
        long size = TREE_LIMIT + 1;
        Body body = await(s.write(key("m-4", 0), new GeneratedReadStream(size, 1000), size));
        BodyTooLargeException e = assertThrows(BodyTooLargeException.class, body::whole);
        assertEquals(size, e.size());
        assertEquals(TREE_LIMIT, e.limit());
        assertEquals(GeneratedReadStream.expectedHash(hasher(), size), await(hashOf(body.open())));
    }

    @Test
    public void aBufferAboveTheFlowsMaxSizeIsRefusedAndNothingIsStored() {
        BlobStore s = store();
        Future<Body> f = s.write(key("m-5", 0), Buffer.buffer(new byte[101]), 100);
        assertTrue(awaitOutcome(f).failed());
        assertInstanceOf(BodyTooLargeException.class, f.cause());
        assertTrue(awaitOutcome(s.read(key("m-5", 0))).failed(), "nothing stored under the refused key");
    }

    @Test
    public void aStreamAboveTheFlowsMaxSizeIsRefusedAndNothingIsStored() {
        BlobStore s = store();
        Future<Body> f = s.write(key("m-6", 0), new GeneratedReadStream(5000, 512), 4000);
        assertTrue(awaitOutcome(f).failed());
        assertInstanceOf(BodyTooLargeException.class, f.cause());
        assertTrue(awaitOutcome(s.read(key("m-6", 0))).failed(), "nothing stored under the refused key");
    }

    @Test
    public void aFailingStreamFailsTheWriteAndNothingIsStored() {
        BlobStore s = store();
        RuntimeException boom = new RuntimeException("socket reset");
        Future<Body> f = s.write(key("m-7", 0), new ChunkedReadStream(List.of("part", "ial"), boom), TREE_LIMIT);
        assertTrue(awaitOutcome(f).failed());
        assertEquals("socket reset", f.cause().getMessage());
        assertTrue(awaitOutcome(s.read(key("m-7", 0))).failed());
    }

    @Test
    public void objectsAreKeyedByMessageIdAndStepNotByContent() {
        BlobStore s = store();
        Buffer same = Buffer.buffer("same bytes");
        Body a = await(s.write(key("m-8", 0), same, TREE_LIMIT));
        Body b = await(s.write(key("m-8", 1), same, TREE_LIMIT));
        Body c = await(s.write(key("m-9", 0), same, TREE_LIMIT));
        assertEquals(a.hash(), b.hash());
        assertEquals(a.hash(), c.hash());
        assertEquals(same, await(s.read(key("m-8", 0))));
        assertEquals(same, await(s.read(key("m-8", 1))));
        assertEquals(same, await(s.read(key("m-9", 0))));
        assertFalse(a.equals(b), "distinct objects: the key is part of the identity");
    }

    @Test
    public void anEmptyBodyIsABodyToo() {
        BlobStore s = store();
        Body body = await(s.write(key("m-10", 0), Buffer.buffer(), TREE_LIMIT));
        assertEquals(0, body.size());
        assertEquals(hasher().hashOf(Buffer.buffer()), body.hash());
        assertEquals(0, body.whole().length());
        assertEquals(0, await(collect(body.open())).length());
    }

    @Test
    public void readingAnUnknownKeyFailsInsteadOfReturningNothing() {
        BlobStore s = store();
        assertTrue(awaitOutcome(s.read(key("never", 0))).failed());
    }

    @Test
    public void aBigStreamPassesThroughIntact() {
        BlobStore s = store();
        long size = bigSize();
        Body body = await(s.write(key("m-11", 0), new GeneratedReadStream(size, 65536), size));
        assertEquals(size, body.size());
        Hash expected = GeneratedReadStream.expectedHash(hasher(), size);
        assertEquals(expected, body.hash(), "hashed while writing");
        assertEquals(expected, await(hashOf(s.open(key("m-11", 0)))), "read back byte for byte");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Hashes a stream chunk by chunk; the test's own observation of what the store hands back. */
    protected Future<Hash> hashOf(ReadStream<Buffer> stream) {
        Promise<Hash> p = Promise.promise();
        Hasher.Session session = hasher().begin();
        stream.exceptionHandler(p::tryFail);
        stream.endHandler(v -> p.tryComplete(session.finish()));
        stream.handler(session::update);
        return p.future();
    }

    public static Future<Buffer> collect(ReadStream<Buffer> stream) {
        Promise<Buffer> p = Promise.promise();
        Buffer all = Buffer.buffer();
        stream.exceptionHandler(p::tryFail);
        stream.endHandler(v -> p.tryComplete(all));
        stream.handler(all::appendBuffer);
        return p.future();
    }

    public static <T> T await(Future<T> f) {
        Future<T> done = awaitOutcome(f);
        if (done.failed()) throw new AssertionError("future failed: " + done.cause(), done.cause());
        return done.result();
    }

    /** Waits (bounded, 60 s) for completion without throwing, so a test can assert on failure. */
    public static <T> Future<T> awaitOutcome(Future<T> f) {
        return awaitOutcome(f, 60);
    }

    static <T> Future<T> awaitOutcome(Future<T> f, long seconds) {
        try {
            f.await(seconds, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
            // the outcome is read from the future itself; a timeout shows as "not complete" below
        }
        assertTrue(f.isComplete(), "future did not complete within " + seconds + " s");
        return f;
    }
}
