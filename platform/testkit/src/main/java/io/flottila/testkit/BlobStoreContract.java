package io.flottila.testkit;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobNotFoundException;
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
    public void aStreamHandedOverPausedIsResumedByTheStore() {
        BlobStore s = store();
        ChunkedReadStream in = new ChunkedReadStream(List.of("<a>", "bbb", "</a>"));
        in.pause();   // a socket whose bytes must not flow before anyone listens
        Body body = await(s.write(key("m-paused", 0), in, TREE_LIMIT));
        assertEquals(10, body.size(), "every chunk arrived although the stream was paused when handed over");
        assertEquals(hasher().hashOf(Buffer.buffer("<a>bbb</a>")), body.hash());
        assertEquals("<a>bbb</a>", await(s.read(body.key())).toString());
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
        GeneratedReadStream in = new GeneratedReadStream(5000, 512);
        Future<Body> f = s.write(key("m-6", 0), in, 4000);
        assertTrue(awaitOutcome(f).failed());
        assertInstanceOf(BodyTooLargeException.class, f.cause());
        assertTrue(awaitOutcome(s.read(key("m-6", 0))).failed(), "nothing stored under the refused key");
        assertEquals(4096, in.delivered(), "the store stopped consuming at the refusing chunk — it never resumes a stream it has itself paused or failed");
    }

    @Test
    public void aFailingStreamFailsTheWriteAndNothingIsStored() {
        BlobStore s = store();
        RuntimeException boom = new RuntimeException("socket reset");
        Future<Body> f = s.write(key("m-7", 0), new ChunkedReadStream(List.of("part", "ial"), boom), TREE_LIMIT);
        assertTrue(awaitOutcome(f).failed());
        assertEquals("socket reset", f.cause().getMessage());
        assertTrue(awaitOutcome(s.read(key("m-7", 0))).failed());
        ChunkedReadStream pausedBoom = new ChunkedReadStream(List.of("part", "ial"), boom);
        pausedBoom.pause();   // handed over paused: the failure lands inside the store's own resume, and the store must not read past it
        Future<Body> g = s.write(key("m-7b", 0), pausedBoom, TREE_LIMIT);
        assertTrue(awaitOutcome(g).failed());
        assertEquals("socket reset", g.cause().getMessage());
        assertTrue(awaitOutcome(s.read(key("m-7b", 0))).failed(), "nothing stored under the key of a stream that failed after the resume");
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

    /**
     * D-096: a key with nothing stored — never written, or removed by retention — fails the read with a
     * {@link BlobNotFoundException} naming that key, whatever is stored under its siblings.
     */
    @Test
    public void readingAnUnknownKeyFailsBlobNotFoundNamingTheKey() {
        BlobStore s = store();
        await(s.write(key("m-known", 0), Buffer.buffer("stored"), TREE_LIMIT));
        for (BlobKey unknown : List.of(key("never", 0), key("m-known", 1), BlobKey.attachment("m-known", 0, "invoice.pdf"))) {
            Future<Buffer> read = awaitOutcome(s.read(unknown));
            assertTrue(read.failed(), "nothing is stored under " + unknown + ": the read fails instead of returning nothing");
            BlobNotFoundException e = assertInstanceOf(BlobNotFoundException.class, read.cause(), "the miss signal: " + read.cause());
            assertEquals(unknown, e.key(), "it names the key read");
            assertEquals("no object stored under " + unknown, e.getMessage());
        }
    }

    /**
     * D-096: opening a key with nothing stored either throws a {@link BlobNotFoundException} naming the key, or returns a stream
     * that fails with one before its first chunk — and never ends as an empty body.
     */
    @Test
    public void openingAnUnknownKeyFailsBlobNotFoundBeforeAnyByte() {
        BlobStore s = store();
        await(s.write(key("m-opened", 0), Buffer.buffer("stored"), TREE_LIMIT));
        for (BlobKey unknown : List.of(key("never-opened", 0), key("m-opened", 1))) {
            BlobNotFoundException e = openFailure(s, unknown);
            assertEquals(unknown, e.key(), "it names the key opened");
            assertEquals("no object stored under " + unknown, e.getMessage());
        }
    }

    /** The miss signal of opening {@code unknown}: thrown by open, or the stream's failure, which no chunk and no end precede. */
    static BlobNotFoundException openFailure(BlobStore s, BlobKey unknown) {
        ReadStream<Buffer> stream;
        try {
            stream = s.open(unknown);
        } catch (BlobNotFoundException thrown) {
            return thrown;
        }
        Promise<Throwable> failed = Promise.promise();
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        stream.exceptionHandler(t -> { seen.add("failure"); failed.tryComplete(t); });
        stream.endHandler(v -> { seen.add("end"); failed.tryComplete(null); });
        stream.handler(chunk -> seen.add("chunk of " + chunk.length() + " bytes"));
        Throwable t = await(failed.future());
        assertEquals(List.of("failure"), seen, "the stream of " + unknown + " fails before any byte and never ends: " + seen);
        return assertInstanceOf(BlobNotFoundException.class, t, "the miss signal: " + t);
    }

    /**
     * D-096: {@code exists} is true for every written key — an empty body too — and false, as an answer and not a failure, for a
     * key with nothing stored: another step, another part, another message.
     */
    @Test
    public void existsIsTrueForAWrittenKeyFalseForAnUnknownOne() {
        BlobStore s = store();
        BlobKey written = key("m-exists", 1);
        BlobKey empty = key("m-exists-empty", 0);
        await(s.write(written, Buffer.buffer("<invoice/>"), TREE_LIMIT));
        await(s.write(empty, Buffer.buffer(), TREE_LIMIT));
        assertEquals(Boolean.TRUE, await(s.exists(written)), "a written key exists");
        assertEquals(Boolean.TRUE, await(s.exists(empty)), "an empty body is a body too");
        for (BlobKey unknown : List.of(key("m-exists", 0), key("m-exists", 2), BlobKey.attachment("m-exists", 1, "invoice.pdf"), key("never-exists", 1))) {
            Future<Boolean> answer = awaitOutcome(s.exists(unknown));
            assertTrue(answer.succeeded(), "a key with nothing stored is an answer, not a failure: " + unknown + " " + answer.cause());
            assertEquals(Boolean.FALSE, answer.result(), "nothing is stored under " + unknown);
        }
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
