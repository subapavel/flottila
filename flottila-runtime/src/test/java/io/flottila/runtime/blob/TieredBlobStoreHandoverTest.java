package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.testkit.BlobStoreContract;
import io.flottila.testkit.BlockedPlatformThreadDetector;
import io.flottila.testkit.GeneratedReadStream;
import io.flottila.testkit.NoBlockedPlatformThreads;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming write's handover (attach), pause and resume choreography (D-050, ARCHITECTURE §5 bodies, §11 testing):
 * every interleaving is fixed by the test, never by the scheduler. The source is a test-controlled stream that acts
 * synchronously inside the calls the store makes on it; where the sink thread must wait, the test's own spool key
 * holds it at the sink's open ({@link GatedKey}); where the source must wait for the sink, it watches the spool
 * directory. Only observable behaviour is asserted: the write's outcome, the stored bytes, the pause / resume calls.
 */
@ExtendWith(NoBlockedPlatformThreads.class)
class TieredBlobStoreHandoverTest {

    private static final long MARK = 100;   // the store's high-water mark for these tests (package seam, as in TieredBlobStoreFailureTest)

    /** No memory tier: the first non-empty chunk spills and starts the sink, as SpoolTierContractTest configures it. */
    private static TieredBlobStore store(Path spool, SecretKey key) {
        return new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1 << 20, spool, key, null, false), TieredBlobStoreTest.HASHER, MARK);
    }

    private static Buffer bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 17 + 3);
        return Buffer.buffer(b);
    }

    @Test
    @Timeout(10)
    void aFailureLandingInsideTheHandoverResumeIsNotConsumedPast(@TempDir Path dir) {
        TieredBlobStore s = store(dir, TieredBlobStoreTest.key());
        RuntimeException boom = new RuntimeException("reset inside the handover resume");
        ScriptedStream source = new ScriptedStream(true, false);   // handed over paused, like a socket the caller paused
        source.onResume = src -> {
            if (src.resumes.get() > 1) return;              // only the first (handover) resume misbehaves
            src.push(Buffer.buffer("before"));              // spills (no memory tier) and starts the sink
            src.failNow(boom);
            src.push(Buffer.buffer("after the failure"));   // a rude source keeps talking: never consumed
        };
        Future<Body> f = s.write(BlobKey.body("m-1", 0), source, 4096);
        assertTrue(BlobStoreContract.awaitOutcome(f).failed(), "the write fails");
        assertSame(boom, f.cause(), "with the source's own cause");
        assertEquals(1, source.resumes.get(), "the handover resume and no other: nothing resumes a failed source " + source.calls);
        List<String> afterFailure = source.callsAfter("fail");
        assertFalse(afterFailure.contains("resume"), "no resume after the failure: " + source.calls);
        assertTrue(afterFailure.contains("pause"), "the failed source is left paused: " + source.calls);
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-1", 0)), "nothing stored, neither before nor after the failure");
        assertTrue(TieredBlobStoreTest.spoolFiles(dir).isEmpty(), "the sink aborted: no .blob, no .part");
        assertEquals(0, s.memoryInUse());
    }

    @Test
    @Timeout(10)
    void aFailureLandingBeforeTheHandoverResumeLeavesTheSourcePaused(@TempDir Path dir) {
        TieredBlobStore s = store(dir, TieredBlobStoreTest.key());
        RuntimeException boom = new RuntimeException("reset as the handover resume is issued");
        ScriptedStream source = new ScriptedStream(true, false);   // handed over paused, nothing scripted
        // the failure lands inside resume() before the resume takes effect: fail()'s own pause is then undone by the
        // resume itself, so only the store's re-check after its handover resume can leave the source paused
        source.beforeResume = src -> {
            if (src.resumes.get() == 1) src.failNow(boom);
        };
        Future<Body> f = s.write(BlobKey.body("m-1", 0), source, 4096);
        assertTrue(BlobStoreContract.awaitOutcome(f).failed(), "the write fails");
        assertSame(boom, f.cause(), "with the source's own cause");
        assertEquals(1, source.resumes.get(), "the handover resume and no other: " + source.calls);
        List<String> afterFailure = source.callsAfter("fail");
        assertTrue(afterFailure.contains("pause"), "the failed source is paused: " + source.calls);
        assertFalse(afterFailure.contains("resume"), "no resume after the failure: " + source.calls);
        List<String> flowCalls = source.calls.stream().filter(c -> c.equals("pause") || c.equals("resume")).toList();
        assertEquals("pause", flowCalls.get(flowCalls.size() - 1), "the last flow-control call is a pause: " + source.calls);
        assertTrue(source.paused, "the source ends paused, after its handover resume took effect: " + source.calls);
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-1", 0)), "nothing stored");
        assertTrue(TieredBlobStoreTest.spoolFiles(dir).isEmpty(), "never spilled: no spool file");
        assertEquals(0, s.memoryInUse());
    }

    @Test
    @Timeout(10)
    void aChunkArrivingInsideTheSinkFailurePauseIsDropped(@TempDir Path dir) {
        TieredBlobStore s = store(dir, new UnencodableAesKey());   // the sink fails at the cipher's open, on the sink thread
        ScriptedStream source = new ScriptedStream(false, false);   // handed over flowing
        // the sink's catch pauses the source; a rude source delivers one more chunk, at the high-water mark, inside that pause
        source.onPause = src -> {
            if (src.pauses.get() == 1) src.push(bytes((int) MARK));
        };
        Future<Body> f = s.write(BlobKey.body("m-1", 0), source, 4096);
        assertEquals(1, source.resumes.get(), "the handover resume, before any chunk: " + source.calls);
        source.push(bytes(10));   // spills (no memory tier), starts the sink, below the high-water mark: enqueue does not pause
        assertTrue(BlobStoreContract.awaitOutcome(f).failed(), "the write fails");
        assertTrue(f.cause() instanceof IOException, "with the sink's open failure: " + f.cause());
        assertTrue(chainNames(f.cause(), "master key"), "the cause names the master key: " + f.cause() + " / " + f.cause().getCause());
        assertEquals(1, source.pauses.get(), "only the sink's failure pause: the chunk inside it was dropped, never queued: " + source.calls);
        assertEquals(1, source.resumes.get(), "nothing resumes a failed source: " + source.calls);
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-1", 0)), "nothing stored");
        assertEquals(0, s.memoryInUse());
        assertTrue(TieredBlobStoreTest.spoolFiles(dir).stream().noneMatch(p -> p.getFileName().toString().endsWith(".blob")),
                "no committed spool file: " + TieredBlobStoreTest.spoolFiles(dir));
    }

    private static boolean chainNames(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) if (c.getMessage() != null && c.getMessage().contains(text)) return true;
        return false;
    }

    /** An HSM-style spool key (as SpoolCipherTest's): it names AES but will not hand out its material. */
    private static final class UnencodableAesKey implements SecretKey {
        @Override public String getAlgorithm() { return "AES"; }
        @Override public String getFormat() { return null; }
        @Override public byte[] getEncoded() { return null; }
    }

    @Test
    @Timeout(10)
    void aSourceThatFailsWhileTheSinkIsIdleFailsTheWrite(@TempDir Path dir) throws InterruptedException {
        GatedKey gate = new GatedKey();
        TieredBlobStore s = store(dir, gate);

        // handed over, no chunk delivered, then the failure
        RuntimeException early = new RuntimeException("reset before the first byte");
        ScriptedStream silent = new ScriptedStream(false, false);
        Future<Body> a = s.write(BlobKey.body("m-1", 0), silent, 4096);
        assertFalse(a.isComplete(), "nothing delivered, nothing ended: the write is still open");
        silent.failNow(early);
        assertTrue(BlobStoreContract.awaitOutcome(a).failed());
        assertSame(early, a.cause());
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-1", 0)));

        // one chunk at the high-water mark: paused while the sink is held at its open; the sink then takes the chunk and
        // resumes the source (its queue is empty, it waits for more), and only then the source fails
        RuntimeException boom = new RuntimeException("reset while the sink waits");
        ScriptedStream source = new ScriptedStream(true, false);
        Future<Body> b = s.write(BlobKey.body("m-2", 0), source, 4096);
        assertEquals(1, source.resumes.get(), "handed over paused: the handover resume " + source.calls);
        source.push(bytes((int) MARK + 20));
        assertEquals(1, source.pauses.get(), "the queue reached the high-water mark: the source was paused " + source.calls);
        assertEquals(1, source.resumes.get(), "the sink has not drained yet: no resume beyond the handover " + source.calls);
        gate.open();
        assertTrue(source.awaitResumes(2, 5), "the sink drained the only chunk and resumed the source: " + source.calls);
        source.failNow(boom);
        assertTrue(BlobStoreContract.awaitOutcome(b).failed(), "the idle sink saw the failure");
        assertSame(boom, b.cause());
        assertEquals(2, source.resumes.get(), "the handover resume and the sink's, nothing after: " + source.calls);
        assertFalse(source.callsAfter("fail").contains("resume"), "no resume after the failure: " + source.calls);
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-2", 0)));
        assertTrue(TieredBlobStoreTest.spoolFiles(dir).isEmpty(), "the sink thread aborted its file before failing the write: no .part left");
        assertEquals(0, s.memoryInUse());
    }

    @Test
    @Timeout(10)
    void aSourceThatEndsWhileTheSinkIsIdleCompletesTheWrite(@TempDir Path dir) throws InterruptedException {
        GatedKey gate = new GatedKey();
        TieredBlobStore s = store(dir, gate);

        // handed over, no chunk delivered, then the end: BlobStoreContract.anEmptyBodyIsABodyToo
        ScriptedStream silent = new ScriptedStream(false, false);
        Future<Body> a = s.write(BlobKey.body("m-1", 0), silent, 4096);
        assertFalse(a.isComplete(), "nothing delivered, nothing ended: the write is still open");
        silent.endNow();
        Body empty = BlobStoreContract.await(a);
        assertEquals(0, empty.size());
        assertEquals(TieredBlobStoreTest.HASHER.hashOf(Buffer.buffer()), empty.hash());
        assertEquals(0, empty.whole().length());
        assertEquals(0, BlobStoreContract.await(BlobStoreContract.collect(empty.open())).length());

        // one chunk at the high-water mark, drained by the sink, which then waits for more; the source ends
        Buffer chunk = bytes((int) MARK + 20);
        ScriptedStream source = new ScriptedStream(true, false);
        Future<Body> b = s.write(BlobKey.body("m-2", 0), source, 4096);
        assertEquals(1, source.resumes.get(), "handed over paused: the handover resume " + source.calls);
        source.push(chunk);
        assertEquals(1, source.pauses.get(), source.calls::toString);
        assertEquals(1, source.resumes.get(), "the sink has not drained yet: no resume beyond the handover " + source.calls);
        gate.open();
        assertTrue(source.awaitResumes(2, 5), "the sink drained the only chunk and resumed the source: " + source.calls);
        source.endNow();
        Body body = BlobStoreContract.await(b);
        assertEquals(chunk.length(), body.size());
        assertEquals(TieredBlobStoreTest.HASHER.hashOf(chunk), body.hash());
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), s.location(body.key()));
        assertEquals(chunk, BlobStoreContract.await(s.read(body.key())), "read back byte for byte");
        assertEquals(1, source.pauses.get(), source.calls::toString);
        assertEquals(2, source.resumes.get(), "the handover resume, then one resume for the one pause: " + source.calls);
    }

    @Test
    @Timeout(10)
    void theSinkResumesASourceItPausedExactlyOnceAfterTheAttach(@TempDir Path dir) {
        GatedKey gate = new GatedKey();
        TieredBlobStore s = store(dir, gate);
        // a synchronous source delivers inside handler(): 60-byte chunks pass the 100-byte mark before the attach returns,
        // while the sink is held at its open — so the only party that can resume it is the sink, after the attach
        TieredBlobStoreFailureTest.CountingStream source = new TieredBlobStoreFailureTest.CountingStream(new GeneratedReadStream(5000, 60));
        Future<Body> f = s.write(BlobKey.body("m-1", 0), source, 10_000);
        assertEquals(1, source.pauses.get(), "paused at the high-water mark inside handler()");
        assertEquals(0, source.resumes.get(), "the attach does not undo the store's own pause");
        assertFalse(f.isComplete());
        gate.open();
        Body body = BlobStoreContract.await(f);
        assertEquals(5000, body.size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, 5000), body.hash());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, 5000), BlobStoreContract.await(new TieredBlobStoreTest.Reader().hashOf(s.open(body.key()))),
                "read back byte for byte");
        assertTrue(source.resumes.get() >= 1, "the sink resumed the source it paused");
        assertEquals(source.pauses.get(), source.resumes.get(), "resumed exactly once per pause: " + source.pauses + " / " + source.resumes);
    }

    @Test
    @Timeout(10)
    void aPauseAndADrainRacingBeforeTheAttachResumeExactlyOnce(@TempDir Path dir, BlockedPlatformThreadDetector detector) throws Exception {
        TieredBlobStore s = store(dir, TieredBlobStoreTest.key());
        // the first chunk is one cipher segment and a byte: the sink stores (seals) the segment as soon as it takes the chunk
        Buffer first = bytes(SpoolCipher.SEGMENT + 1);
        Buffer tail = Buffer.buffer("tail");
        long sealed = SpoolCipher.SALT_BYTES + 5L + SpoolCipher.SEGMENT + SpoolCipher.TAG_BYTES;   // salt, one segment header, ciphertext, tag
        Path messageDir = Files.createDirectories(dir.resolve("m-1"));
        try (WatchService watch = FileSystems.getDefault().newWatchService()) {
            messageDir.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            ScriptedStream source = new ScriptedStream(false, true, first, tail);
            // the store pauses the source from enqueue, before the attach; that pause returns only once the sink has stored the
            // queued bytes, so the store's re-check after the pause runs after the drain
            source.onPause = src -> {
                if (src.pauses.get() == 1) awaitSpooled(watch, messageDir, sealed);
            };
            AtomicReference<Future<Body>> written = new AtomicReference<>();
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread writer = detector.watchedVirtualThread("test-source", () -> {
                try {
                    written.set(s.write(BlobKey.body("m-1", 0), source, 4L << 20));
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            assertTrue(writer.join(Duration.ofSeconds(8)), "write() returned");
            if (thrown.get() != null) throw new AssertionError("write() threw", thrown.get());
            Body body = BlobStoreContract.await(written.get());
            Buffer expected = first.copy().appendBuffer(tail);
            assertEquals(expected.length(), body.size());
            assertEquals(TieredBlobStoreTest.HASHER.hashOf(expected), body.hash());
            assertEquals(expected, BlobStoreContract.await(s.read(body.key())), "read back byte for byte");
            assertEquals(1, source.pauses.get(), source.calls::toString);
            assertEquals(1, source.resumes.get(), "resumed exactly once, neither twice nor never: " + source.calls);
        }
    }

    /** Blocks (on the source's virtual thread) until a file under {@code messageDir} holds at least {@code bytes}: the sink has stored them. */
    private static void awaitSpooled(WatchService watch, Path messageDir, long bytes) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (largestFile(messageDir) < bytes) {
                long left = deadline - System.nanoTime();
                WatchKey key = left > 0 ? watch.poll(left, TimeUnit.NANOSECONDS) : null;
                if (key == null) throw new AssertionError("the sink did not store the queued bytes while the source's pause() was in flight");
                key.pollEvents();
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the sink", e);
        }
    }

    private static long largestFile(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }   // a .part moved away between list and size: not the one being written
            }).max().orElse(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The test's spool key: a real AES key whose material is handed out only once the test opens the gate — it holds the sink thread at its open. */
    static final class GatedKey implements SecretKey {
        private final SecretKey real = TieredBlobStoreTest.key();
        private final CountDownLatch gate = new CountDownLatch(1);

        void open() { gate.countDown(); }

        @Override public String getAlgorithm() { return real.getAlgorithm(); }
        @Override public String getFormat() { return real.getFormat(); }
        @Override public byte[] getEncoded() {
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("the test never opened the gate");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted at the gate", e);
            }
            return real.getEncoded();
        }
    }

    /**
     * A test-controlled source. It delivers its scripted chunks synchronously while not paused (inside handler() and
     * resume(), like a synchronous source), then ends if told to; the test can also push a chunk, fail or end it by hand
     * at any moment. Hooks run synchronously inside pause() and resume(). Every call is counted and recorded in order.
     */
    static final class ScriptedStream implements ReadStream<Buffer> {
        private final ArrayDeque<Buffer> script = new ArrayDeque<>();
        private final boolean endAfterScript;
        private volatile Handler<Buffer> data;
        private volatile Handler<Throwable> failure;
        private volatile Handler<Void> end;
        private volatile boolean paused;
        private volatile boolean draining;
        private volatile boolean ended;
        private long credit;
        final AtomicInteger pauses = new AtomicInteger();
        final AtomicInteger resumes = new AtomicInteger();
        final List<String> calls = new CopyOnWriteArrayList<>();
        private final Semaphore resumed = new Semaphore(0);
        volatile Consumer<ScriptedStream> onPause = s -> { };
        volatile Consumer<ScriptedStream> onResume = s -> { };
        volatile Consumer<ScriptedStream> beforeResume = s -> { };

        ScriptedStream(boolean handedOverPaused, boolean endAfterScript, Buffer... chunks) {
            this.paused = handedOverPaused;
            this.endAfterScript = endAfterScript;
            for (Buffer c : chunks) script.addLast(c);
        }

        @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { failure = h; return this; }
        @Override public ReadStream<Buffer> endHandler(Handler<Void> h) { end = h; drain(); return this; }
        @Override public ReadStream<Buffer> handler(Handler<Buffer> h) { data = h; drain(); return this; }

        @Override public ReadStream<Buffer> pause() {
            calls.add("pause");
            pauses.incrementAndGet();
            paused = true;
            credit = 0;
            onPause.accept(this);
            return this;
        }

        @Override public ReadStream<Buffer> resume() {
            calls.add("resume");
            resumes.incrementAndGet();
            beforeResume.accept(this);   // runs while the source is still paused: whatever it does lands before this resume takes effect
            paused = false;
            onResume.accept(this);
            resumed.release();
            drain();
            return this;
        }

        @Override public ReadStream<Buffer> fetch(long amount) {
            calls.add("fetch");
            if (amount < 0) throw new IllegalArgumentException("amount < 0");
            if (paused) credit = credit > Long.MAX_VALUE - amount ? Long.MAX_VALUE : credit + amount;
            drain();
            return this;
        }

        /** Delivers one chunk now, paused or not (the store must cope with a source that ignores pause). */
        void push(Buffer chunk) { calls.add("chunk"); data.handle(chunk); }

        void failNow(Throwable t) { calls.add("fail"); failure.handle(t); }

        void endNow() { calls.add("end"); ended = true; end.handle(null); }

        boolean awaitResumes(int n, long seconds) throws InterruptedException { return resumed.tryAcquire(n, seconds, TimeUnit.SECONDS); }

        List<String> callsAfter(String marker) {
            List<String> all = List.copyOf(calls);
            int at = all.indexOf(marker);
            if (at < 0) throw new AssertionError("no '" + marker + "' in " + all);
            return all.subList(at + 1, all.size());
        }

        private void drain() {
            if (draining || data == null) return;
            draining = true;
            try {
                while (!script.isEmpty() && (!paused || credit > 0)) {
                    if (paused) credit--;
                    push(script.pollFirst());
                }
                if (script.isEmpty() && endAfterScript && !ended && end != null) endNow();
            } finally {
                draining = false;
            }
        }
    }
}
