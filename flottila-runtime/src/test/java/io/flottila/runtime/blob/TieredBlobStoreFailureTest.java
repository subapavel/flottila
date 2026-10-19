package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.BodyTooLargeException;
import io.flottila.testkit.BlobStoreContract;
import io.flottila.testkit.ChunkedReadStream;
import io.flottila.testkit.GeneratedReadStream;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the tiers do when the world misbehaves: unwritable spool, failing S3, sources that ignore pause, spill by budget. */
class TieredBlobStoreFailureTest {

    static TieredBlobStore store(Path spool, long tier, long budget) { return TieredBlobStoreTest.store(spool, tier, budget); }

    @Test
    void anUnwritableSpoolFailsTheWriteWholeAndStreamedAndStoresNothing(@TempDir Path dir) throws Exception {
        Path notADir = dir.resolve("spool-is-a-file");
        Files.writeString(notADir, "in the way");
        TieredBlobStore s = store(notADir, 0, 1 << 20);
        Future<Body> whole = s.write(BlobKey.body("m-1", 0), Buffer.buffer("x"), 100);
        assertTrue(BlobStoreContract.awaitOutcome(whole).failed());
        assertInstanceOf(java.io.IOException.class, whole.cause());
        Future<Body> streamed = s.write(BlobKey.body("m-2", 0), new ChunkedReadStream(List.of("a", "b")), 100);
        assertTrue(BlobStoreContract.awaitOutcome(streamed).failed());
        assertInstanceOf(java.io.IOException.class, streamed.cause());
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-2", 0)));
        assertEquals(0, s.memoryInUse());
        assertThrows(IllegalArgumentException.class, () -> s.open(BlobKey.body("m-2", 0)));
    }

    @Test
    void aSourceThatFailsAfterTheSpillAbortsTheSinkAndLeavesNoFile(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 10, 1 << 20);
        RuntimeException boom = new RuntimeException("reset after 3 chunks");
        Future<Body> f = s.write(BlobKey.body("m-1", 0), new ChunkedReadStream(List.of("aaaaaa", "bbbbbb", "cccccc"), boom), 1 << 20);
        assertTrue(BlobStoreContract.awaitOutcome(f).failed());
        assertEquals(boom, f.cause());
        assertTrue(TieredBlobStoreTest.spoolFiles(dir).isEmpty(), "no .blob and no .part left behind");
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-1", 0)));
    }

    @Test
    void theBudgetSpillsAStreamThatTheTierAloneWouldHaveKept(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 50_000, 60_000);
        BlobStoreContract.await(s.write(BlobKey.body("m-0", 0), Buffer.buffer(new byte[20_000]), 100_000));   // 20 000 of the budget taken
        Body body = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), new GeneratedReadStream(45_000, 10_000), 100_000));
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(body.key()), "45 000 fits the tier, but 20 000 + 45 000 does not fit the budget");
        assertEquals(20_000, s.memoryInUse(), "the spilled stream gave its reservation back; the first body keeps its own");
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, 45_000), body.hash());
    }

    @Test
    void aSourceThatIgnoresPauseCannotMakeAFailedWriteSucceedOrFailTwice(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 1 << 20, 1 << 20);
        RudeStream rude = new RudeStream();
        Future<Body> f = s.write(BlobKey.body("m-1", 0), rude, 10);
        rude.data.handle(Buffer.buffer(new byte[8]));
        rude.data.handle(Buffer.buffer(new byte[8]));     // 16 > 10: fails here
        assertTrue(f.failed());
        assertInstanceOf(BodyTooLargeException.class, f.cause());
        rude.data.handle(Buffer.buffer(new byte[8]));     // ignored
        rude.failure.handle(new RuntimeException("late"));// ignored: the first failure stands
        rude.end.handle(null);                            // ignored
        assertInstanceOf(BodyTooLargeException.class, f.cause());
        assertEquals(1, rude.pauses.get(), "paused once on failure, not again for every ignored chunk");
        assertEquals(0, s.memoryInUse());
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-1", 0)));
    }

    @Test
    void aSourceThatKeepsTalkingAfterTheEndIsIgnored(@TempDir Path dir) {
        TieredBlobStore s = store(dir, 1 << 20, 1 << 20);
        RudeStream rude = new RudeStream();
        Future<Body> f = s.write(BlobKey.body("m-1", 0), rude, 100);
        rude.data.handle(Buffer.buffer("done"));
        rude.end.handle(null);
        assertTrue(f.succeeded());
        rude.data.handle(Buffer.buffer("late"));             // ignored
        rude.end.handle(null);                               // ignored
        rude.failure.handle(new RuntimeException("late"));   // ignored
        assertEquals(4, f.result().size());
        assertEquals("done", BlobStoreContract.await(s.read(BlobKey.body("m-1", 0))).toString());
    }

    @Test
    void aSlowSinkPausesTheSourceAtTheHighWaterMarkAndResumesItBelowHalf(@TempDir Path dir) {
        TieredBlobStore s = new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1 << 20, dir, TieredBlobStoreTest.key(), null, false), TieredBlobStoreTest.HASHER, 100);
        CountingStream source = new CountingStream(new GeneratedReadStream(5000, 60));
        Body body = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), source, 10_000));
        assertEquals(5000, body.size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, 5000), body.hash());
        assertTrue(source.pauses.get() >= 1, "60-byte chunks against a 100-byte high-water mark: the source was paused");
        assertEquals(source.pauses.get(), source.resumes.get(), "every pause was followed by a resume: " + source.pauses + " / " + source.resumes);
        assertTrue(s.peakQueuedBytes() <= 100 + 60, "the queue never held more than the high-water mark plus one chunk — a resume that undid the store's own pause would let the whole body in: " + s.peakQueuedBytes());
        assertThrows(IllegalArgumentException.class, () -> new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1 << 20, dir, TieredBlobStoreTest.key(), null, false), TieredBlobStoreTest.HASHER, 1));
    }

    @Test
    void aSynchronousSourcePausedWhileAttachingIsResumedExactlyOnceAndTheWriteCompletes(@TempDir Path dir) {
        // a synchronous source delivers inside handler(): it hits the high-water mark before start() has returned. Until the attach
        // completed only the attach thread may resume it (the sink thread clears the flag and leaves the resume to whoever comes
        // second), so no resume ever undoes a pause and no pause is left without its resume — whichever way the two threads interleave.
        for (int round = 0; round < 20; round++) {
            TieredBlobStore s = new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1 << 20, dir, TieredBlobStoreTest.key(), null, false), TieredBlobStoreTest.HASHER, 100);
            CountingStream source = new CountingStream(new GeneratedReadStream(5000, 60));
            Body body = BlobStoreContract.await(s.write(BlobKey.body("m-" + round, 0), source, 10_000));
            assertEquals(5000, body.size());
            assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, 5000), body.hash());
            assertEquals(source.pauses.get(), source.resumes.get(), "round " + round + ": every pause matched by one resume: " + source.pauses + " / " + source.resumes);
            assertTrue(s.peakQueuedBytes() <= 100 + 60, "round " + round + ": the queue stayed under the high-water mark plus one chunk: " + s.peakQueuedBytes());
        }
    }

    @Test
    void aPauseThatLandsAfterTheSinkAlreadyResumedIsNotLostForever(@TempDir Path dir) {
        // a socket-like source: handed over paused, it starts delivering on the attach thread's resume — after the attach, so the
        // sink thread may resume it. The source decides to pause, is descheduled, the sink drains and "resumes" a stream that is
        // not paused yet, then the pause lands: without the re-check nobody would ever resume it and the write would hang.
        TieredBlobStore s = new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1 << 20, dir, TieredBlobStoreTest.key(), null, false), TieredBlobStoreTest.HASHER, 100);
        LatePauseStream source = new LatePauseStream(new GeneratedReadStream(5000, 60));
        source.pause();
        Body body = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), source, 10_000));
        assertEquals(5000, body.size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, 5000), body.hash());
        assertTrue(source.lateLandings.get() >= 1, "the race was produced at least once: " + source.lateLandings);
    }

    @Test
    void aPauseEnteredOnlyAfterTheSinkAlreadyResumedIsResumedOnceMoreByTheReCheck(@TempDir Path dir) {
        // the other side of the window: enqueue() has marked the source paused and released the monitor, the attach thread is
        // descheduled before it even enters pause(); the sink drains and resumes a source that is not paused yet. Staged, not raced:
        // the wrapper holds the attach thread at the top of pause() until it has seen the sink's resume, then lets the pause land.
        TieredBlobStore s = new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1 << 20, dir, TieredBlobStoreTest.key(), null, false), TieredBlobStoreTest.HASHER, 100);
        LatePauseStream source = new LatePauseStream(new GeneratedReadStream(5000, 60), true);
        source.pause();
        Body body = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), source, 10_000));
        assertEquals(5000, body.size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, 5000), body.hash());
        assertEquals(1, source.lateLandings.get(), "exactly the first pause after the attach was held: " + source.lateLandings);
        List<LatePauseStream.Event> events = List.copyOf(source.events);
        int landed = events.indexOf(new LatePauseStream.Event("landed", true));
        assertTrue(landed > 0, "the late pause landed: " + events);
        assertTrue(events.subList(0, landed).contains(new LatePauseStream.Event("resume", false)),
                "the sink's resume arrived before the pause was even entered: " + events);
        List<String> attachAfterLanding = new java.util.ArrayList<>();
        for (LatePauseStream.Event e : events.subList(landed + 1, events.size())) {
            if (!e.byAttach()) continue;
            if (e.kind().equals("pause")) break;
            attachAfterLanding.add(e.kind());
        }
        assertEquals(List.of("resume"), attachAfterLanding,
                "the re-check resumed the source exactly once after the late pause landed, before the attach thread paused it again: " + events);
    }

    @Test
    void anEvictionThatCannotDeleteReportsIt(@TempDir Path dir) throws Exception {
        TieredBlobStore s = store(dir, 0, 1 << 20);
        Body body = BlobStoreContract.await(s.write(BlobKey.body("m-1", 0), Buffer.buffer("bytes"), 4096));
        Path file = TieredBlobStoreTest.spoolFiles(dir).get(0);
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("child"), "blocks the delete");
        Future<Void> evicted = s.evict(body.key());
        assertTrue(BlobStoreContract.awaitOutcome(evicted).failed());
        assertInstanceOf(java.io.IOException.class, evicted.cause());
    }

    @Test
    void aFailingS3FailsWholeAndMultipartWritesAndAbortsQuietly(@TempDir Path dir) {
        AtomicInteger aborts = new AtomicInteger();
        S3Client broken = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "serviceName": return "s3";
                case "close": return null;
                case "createMultipartUpload": return software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse.builder().uploadId("u-1").build();
                case "abortMultipartUpload": aborts.incrementAndGet(); throw new IllegalStateException("abort also fails");
                default: throw new IllegalStateException("S3 down: " + method.getName());
            }
        });
        TieredBlobStore s = new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1 << 20, dir, TieredBlobStoreTest.key(),
                new TieredBlobStore.S3Target(broken, "b", "p"), true), TieredBlobStoreTest.HASHER);
        Future<Body> whole = s.write(BlobKey.body("m-1", 0), Buffer.buffer("small"), 4096);
        assertTrue(BlobStoreContract.awaitOutcome(whole).failed());
        assertTrue(whole.cause().getMessage().contains("putObject"), String.valueOf(whole.cause()));
        long size = 6L << 20;   // above one part: createMultipartUpload succeeds, uploadPart fails, abort is attempted
        Future<Body> multipart = s.write(BlobKey.body("m-2", 0), new GeneratedReadStream(size, 65536), size);
        assertTrue(BlobStoreContract.awaitOutcome(multipart).failed());
        assertTrue(multipart.cause().getMessage().contains("uploadPart"), String.valueOf(multipart.cause()));
        assertEquals(1, aborts.get(), "the multipart upload was aborted even though the abort itself failed");
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-2", 0)));
        assertEquals("p/m-2/0/body", s.objectKey(BlobKey.body("m-2", 0)));
    }

    /**
     * A source whose first pause() after the handler was attached lands only after the sink has called resume() for it. With
     * {@code enterPauseAfterSinkResume} the attach thread is also held at the top of that pause(), before it marks anything, until
     * the sink's resume has been seen — the ordering where the sink's resume precedes the pause altogether.
     */
    static final class LatePauseStream implements ReadStream<Buffer> {
        record Event(String kind, boolean byAttach) {}
        final ReadStream<Buffer> inner;
        final boolean enterPauseAfterSinkResume;
        final java.util.concurrent.CountDownLatch resumedOnce = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch sinkResumeSeen = new java.util.concurrent.CountDownLatch(1);   // staging only
        final AtomicInteger lateLandings = new AtomicInteger();
        final java.util.Queue<Event> events = new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile Thread attachThread;
        volatile boolean attached;
        volatile boolean delayed;
        LatePauseStream(ReadStream<Buffer> inner) { this(inner, false); }
        LatePauseStream(ReadStream<Buffer> inner, boolean enterPauseAfterSinkResume) { this.inner = inner; this.enterPauseAfterSinkResume = enterPauseAfterSinkResume; }
        private boolean onAttachThread() { return Thread.currentThread() == attachThread; }
        private static void await(java.util.concurrent.CountDownLatch latch, String failure) {
            try { if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError(failure); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        }
        @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { inner.exceptionHandler(h); return this; }
        @Override public ReadStream<Buffer> handler(Handler<Buffer> h) { attachThread = Thread.currentThread(); inner.handler(h); attached = true; return this; }
        @Override public ReadStream<Buffer> pause() {
            if (attached && !delayed) {
                if (enterPauseAfterSinkResume && onAttachThread()) await(sinkResumeSeen, "the sink's resume was never seen before the pause");
                delayed = true;
                await(resumedOnce, "the sink never resumed");
                lateLandings.incrementAndGet();
                inner.pause();
                events.add(new Event("landed", onAttachThread()));
                return this;
            }
            events.add(new Event("pause", onAttachThread()));
            inner.pause();
            return this;
        }
        @Override public ReadStream<Buffer> resume() {
            boolean bySink = attached && !onAttachThread();
            events.add(new Event("resume", !bySink));
            if (bySink) {   // the sink's resume opens the latch whichever side of pause() it lands; start()'s handover resume (attach thread) never does
                sinkResumeSeen.countDown();
                resumedOnce.countDown();
            }
            inner.resume();
            return this;
        }
        @Override public ReadStream<Buffer> fetch(long amount) { inner.fetch(amount); return this; }
        @Override public ReadStream<Buffer> endHandler(Handler<Void> h) { inner.endHandler(h); return this; }
    }

    /** Counts pause / resume calls on the way to a real source. */
    static final class CountingStream implements ReadStream<Buffer> {
        final ReadStream<Buffer> inner; final AtomicInteger pauses = new AtomicInteger(); final AtomicInteger resumes = new AtomicInteger();
        CountingStream(ReadStream<Buffer> inner) { this.inner = inner; }
        @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { inner.exceptionHandler(h); return this; }
        @Override public ReadStream<Buffer> handler(Handler<Buffer> h) { inner.handler(h); return this; }
        @Override public ReadStream<Buffer> pause() { pauses.incrementAndGet(); inner.pause(); return this; }
        @Override public ReadStream<Buffer> resume() { resumes.incrementAndGet(); inner.resume(); return this; }
        @Override public ReadStream<Buffer> fetch(long amount) { inner.fetch(amount); return this; }
        @Override public ReadStream<Buffer> endHandler(Handler<Void> h) { inner.endHandler(h); return this; }
    }

    /** A source that never honours pause and lets the test push chunks by hand. */
    static final class RudeStream implements ReadStream<Buffer> {
        Handler<Buffer> data; Handler<Throwable> failure; Handler<Void> end; final AtomicInteger pauses = new AtomicInteger();
        @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { failure = h; return this; }
        @Override public ReadStream<Buffer> handler(Handler<Buffer> h) { data = h; return this; }
        @Override public ReadStream<Buffer> pause() { pauses.incrementAndGet(); return this; }
        @Override public ReadStream<Buffer> resume() { return this; }
        @Override public ReadStream<Buffer> fetch(long amount) { return this; }
        @Override public ReadStream<Buffer> endHandler(Handler<Void> h) { end = h; return this; }
    }
}
