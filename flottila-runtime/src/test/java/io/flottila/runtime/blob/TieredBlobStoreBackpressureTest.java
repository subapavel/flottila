package io.flottila.runtime.blob;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.testkit.GeneratedReadStream;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-10: exactly one party resumes each pause of the streaming write's source — the sink when the pause has landed, the
 * source's own re-check when the sink decided while the pause was still being entered (before or after the attach) — a
 * failed write is never resumed, and the first recorded failure is the one the write fails with. A resume issued after the
 * monitor that decided it never undoes what landed in between: a failure's pause (the re-check's and the sink's resume)
 * or a pause of an own-thread source (start()'s hand-over resume); while that hand-over resume is in flight only the
 * hand-over thread resumes the source — a resume decided meanwhile (the sink's, a re-check's) is issued by it after its
 * own; pauses, ours and a failure's, go out at once and the hand-over thread re-issues what its resume undid. Every
 * interleaving is staged (the store's {@code afterSinkDecision} seam holds the sink thread, {@link HeldStream} holds a
 * thread inside a chosen store pause or after a chosen chunk, {@link OwnThreadStream} delivers on its own thread and holds the attach thread inside
 * {@code handler()}, {@link HeldKey} holds the sink at its open), never left to the scheduler; every wait is a latch or a
 * join bounded at 10 s, and every fixture thread is virtual.
 */
class TieredBlobStoreBackpressureTest {

    private static final long MARK = 100;         // high-water mark: the source pauses at the second queued 60-byte chunk (120)
    private static final long BOUND = MARK + 60;  // back-pressure keeps the queue at most one chunk above the mark
    private static final int SIZE = 5000;
    private static final int CHUNK = 60;
    private static final String ATTACH = "fix10-attach";
    private static final String SOURCE = "fix10-source";
    private static final String SINK = "flottila-blob-write";   // the name TieredBlobStore gives the sink's virtual thread

    /** Every latch a staged thread may be waiting on: opened after each case so no thread outlives it on a failure. */
    private final List<CountDownLatch> openOnExit = new ArrayList<>();
    /** Every own-thread source of the case: closed, then each of its latches opened, after each case. */
    private final List<OwnThreadStream> sources = new ArrayList<>();
    /** Every held-stream wrapper of the case: each of its latches (the pause holds and the chunk holds armed later) opened after each case. */
    private final List<HeldStream> wrappers = new ArrayList<>();

    @AfterEach
    void releaseEveryHeldThread() {
        sources.forEach(OwnThreadStream::close);
        openOnExit.forEach(CountDownLatch::countDown);
        wrappers.forEach(h -> h.latches().forEach(CountDownLatch::countDown));
        sources.forEach(o -> o.latches().forEach(CountDownLatch::countDown));
    }

    private static TieredBlobStore store(Path spool, SecretKey key) {
        return new TieredBlobStore(new TieredBlobStore.Config(4096, 0, 1 << 20, spool, key, null, false), TieredBlobStoreTest.HASHER, MARK);
    }

    @Test
    void aSinkThatDecidesWhileTheSourceIsStillEnteringItsPauseLeavesTheOneResumeToTheSourcesReCheck(@TempDir Path dir) throws Exception {
        HeldKey key = new HeldKey(false);
        TieredBlobStore s = store(dir, key);
        HeldStream source = held(new GeneratedReadStream(SIZE, CHUNK), Set.of(1));
        CountDownLatch decided = latch();
        CountDownLatch sinkMayGo = latch();
        openOnExit.add(key.gate);
        AtomicInteger hookCalls = new AtomicInteger();
        // call 1: chunk 1 polled (queued 60); call 2: chunk 2 polled (queued 0 < 50) — the decision for pause 1 is made
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) {
                decided.countDown();
                await(sinkMayGo, "the test never let the sink past its decision for pause 1");
            }
        });
        source.pause();   // handed over paused: start()'s resume delivers the first chunks on the attach thread, after the attach

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.pauseEntered(1), "the attach thread never entered pause 1 (chunk 2 at the high-water mark)");
        key.gate.countDown();   // the sink was held at its open until now: chunks 1 and 2 are both queued (120) when it polls
        await(decided, "the sink never reached its decision for pause 1");
        source.pauseMayReturn(1).countDown();   // pause 1 lands; the re-check sees the sink's decision and resumes; chunks 3-4 enter pause 2
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after pause 1 was let go");
        sinkMayGo.countDown();

        settled(write, "the write never completed after the sink was let go");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        assertTrue(s.peakQueuedBytes() <= BOUND,
                "the queue never held more than the high-water mark plus one chunk — a late sink resume undid pause 2 and let the body in: "
                        + s.peakQueuedBytes() + " bytes, events " + source.events);
        assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + source.events);
        assertEquals(SIZE, write.result().size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash());
        assertNoResumeWithoutAPauseBetween(source.events);
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause1 = storePause(events, 1);
        assertEquals(List.of(new HeldStream.Event("resume", ATTACH)), resumesBeforeNextPause(events, pause1),
                "pause 1 was resumed once, by the source's own re-check on the attach thread: " + events);
    }

    @Test
    void aSinkThatDecidesAfterThePauseLandedResumesItItselfExactlyOnce(@TempDir Path dir) throws Exception {
        TieredBlobStore s = store(dir, TieredBlobStoreTest.key());
        HeldStream source = held(new GeneratedReadStream(SIZE, CHUNK), Set.of());
        CountDownLatch attachReturned = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 1: chunk 1 polled, no decision possible yet (the queue cannot drop below half the mark on the first poll)
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 1) await(attachReturned, "the attach thread's write(...) never returned with pause 1 landed");
        });
        source.pause();

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source, attachReturned);
        Future<Body> write = attach.joined("the attach thread's write(...) never returned (pause 1 never landed)");

        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause1 = storePause(events, 1);
        assertEquals(List.of(new HeldStream.Event("resume", SINK)), resumesBeforeNextPause(events, pause1),
                "the pause had landed when the sink decided: the sink resumed it, exactly once, before the next pause: " + events);
        assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events);
        assertTrue(s.peakQueuedBytes() <= BOUND, "the queue stayed under the high-water mark plus one chunk: " + s.peakQueuedBytes());
        assertEquals(SIZE, write.result().size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash());
        assertNoResumeWithoutAPauseBetween(source.events);
    }

    @Test
    void aSinkFailureAfterTheSourceFailedKeepsTheSourcesCauseAndCarriesItsOwnAsSuppressed(@TempDir Path dir) {
        HeldKey key = new HeldKey(true);   // the sink's open blocks at the gate, then fails: the key hands out no material
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        TieredBlobStoreHandoverTest.ScriptedStream source = new TieredBlobStoreHandoverTest.ScriptedStream(false, false);
        RuntimeException sourceErr = new RuntimeException("connection reset by the source");
        Future<Body> write = s.write(BlobKey.body("m-1", 0), source, 4096);
        source.push(Buffer.buffer(new byte[10]));   // above the memory tier (0): spills and starts the sink thread
        await(key.reached, "the sink thread never reached its open");
        source.failNow(sourceErr);                  // recorded first
        key.gate.countDown();                       // now the sink's open fails

        settled(write, "the write never completed after both failures");
        assertTrue(write.failed(), "the write fails");
        assertSame(sourceErr, write.cause(), "the first recorded failure — the source's — is the cause the write fails with: " + write.cause());
        Throwable[] suppressed = sourceErr.getSuppressed();
        assertEquals(1, suppressed.length, "the sink's later failure is carried as suppressed: " + List.of(suppressed));
        assertInstanceOf(IOException.class, suppressed[0], "the sink's open failure: " + suppressed[0]);
        assertTrue(chainNames(suppressed[0], "master key"), "the suppressed exception is the sink's key refusal: " + suppressed[0]);
        assertEquals(Optional.empty(), s.location(BlobKey.body("m-1", 0)), "nothing stored");
        assertTrue(TieredBlobStoreTest.spoolFiles(dir).stream().noneMatch(p -> p.getFileName().toString().endsWith(".blob")),
                "no committed spool file: " + TieredBlobStoreTest.spoolFiles(dir));
    }

    @Test
    void theSecondPauseIsResumedOnceByItsOwnReCheckWhenTheSinkDecidesWhileItIsBeingEntered(@TempDir Path dir) throws Exception {
        HeldKey key = new HeldKey(false);
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        HeldStream source = held(new GeneratedReadStream(SIZE, CHUNK), Set.of(1, 2));
        CountDownLatch decided1 = latch();
        CountDownLatch sinkMayGo1 = latch();
        CountDownLatch decided2 = latch();
        CountDownLatch sinkMayGo2 = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the decision for pause 1; call 4: chunk 4 polled (queued 0) — the decision for pause 2
        s.afterSinkDecision(() -> {
            int call = hookCalls.incrementAndGet();
            if (call == 2) {
                decided1.countDown();
                await(sinkMayGo1, "the test never let the sink past its decision for pause 1");
            } else if (call == 4) {
                decided2.countDown();
                await(sinkMayGo2, "the test never let the sink past its decision for pause 2");
            }
        });
        source.pause();   // handed over paused: start()'s resume delivers the first chunks on the attach thread

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.pauseEntered(1), "the attach thread never entered pause 1 (chunk 2 at the high-water mark)");
        key.gate.countDown();
        await(decided1, "the sink never reached its decision for pause 1");
        source.pauseMayReturn(1).countDown();   // the re-check resumes pause 1; chunks 3-4 are queued, the attach thread enters pause 2
        await(source.pauseEntered(2), "the attach thread never entered pause 2 (chunk 4 at the high-water mark)");
        sinkMayGo1.countDown();                 // the sink writes chunk 2, polls 3 and 4: its decision for pause 2 comes while it is being entered
        await(decided2, "the sink never reached its decision for pause 2");
        source.pauseMayReturn(2).countDown();   // pause 2 lands; its re-check sees the sink's decision and resumes
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after pause 2 was let go");
        sinkMayGo2.countDown();

        settled(write, "the write never completed after the sink was let go");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        assertTrue(s.peakQueuedBytes() <= BOUND,
                "the queue never held more than the high-water mark plus one chunk — a stale sink resume undid pause 3 and let the body in: "
                        + s.peakQueuedBytes() + " bytes, events " + source.events);
        assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + source.events);
        assertNoResumeWithoutAPauseBetween(source.events);
        assertEquals(SIZE, write.result().size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash());
        List<HeldStream.Event> events = List.copyOf(source.events);
        assertEquals(List.of(new HeldStream.Event("resume", ATTACH)), resumesBeforeNextPause(events, storePause(events, 1)),
                "pause 1 was resumed once, by its own re-check on the attach thread: " + events);
        assertEquals(List.of(new HeldStream.Event("resume", ATTACH)), resumesBeforeNextPause(events, storePause(events, 2)),
                "pause 2 was resumed once, by its own re-check on the attach thread — the sink decided while it was being entered: " + events);
    }

    @Test
    void beforeTheAttachStartAndTheReCheckDoNotBothResumeAPauseTheSourceIsStillEntering(@TempDir Path dir) throws Exception {
        HeldKey key = new HeldKey(false);   // the sink cannot open before the test says
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        source.holdPause(1);   // pause 1, on the source thread: held before it takes effect
        CountDownLatch decided = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1, before the attach: it issues no resume
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) decided.countDown();
        });

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);   // not handed over paused: the source delivers inside handler()
        await(source.pauseEntered(1), "the source thread never entered pause 1 (chunk 2 at the high-water mark)");
        key.gate.countDown();
        await(decided, "the sink never reached its decision for pause 1");
        source.attachMayReturn.countDown();   // the attach thread leaves handler(): start() attaches and decides
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after handler() was let go");
        source.pauseMayTakeEffect(1).countDown();   // pause 1 lands: the source's re-check runs
        source.joined("the source thread never finished delivering the body");

        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        assertEquals(ATTACH, source.attachThread.get(), "the store attached its handler on the attach thread");
        List<HeldStream.Event> events = List.copyOf(source.events);
        assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events);
        assertNoResumeWithoutAPauseBetween(events);
        assertEquals(List.of(new HeldStream.Event("resume", SOURCE)), resumesBeforeNextPause(events, storePause(events, 1)),
                "pause 1 was resumed once, by its own re-check on the source thread — start() on the attach thread must not resume it too: "
                        + events);
        assertEquals(SIZE, write.result().size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash());
    }

    @Test
    void theReCheckNeverResumesAWriteWhoseSinkFailedWhileThePauseWasBeingEntered(@TempDir Path dir) throws Exception {
        HeldKey key = new HeldKey(false);
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        HeldStream source = held(new GeneratedReadStream(SIZE, CHUNK), Set.of(1, 2));
        source.pauseMayReturn(2).countDown();   // store pause 2 is the failing sink's own pause: observed through pauseEntered(2), never held
        IllegalStateException sinkBroke = new IllegalStateException("sink broke");
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — right after the sink's decision for pause 1 it fails (inside its try: the catch records it)
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) throw sinkBroke;
        });
        source.pause();   // handed over paused: start()'s resume delivers the first chunks on the attach thread
        BlobKey blob = BlobKey.body("m-1", 0);

        Attach attach = new Attach(s, blob, source);
        await(source.pauseEntered(1), "the attach thread never entered pause 1 (chunk 2 at the high-water mark)");
        key.gate.countDown();
        await(source.pauseEntered(2), "the failing sink never paused the source (its failure was not recorded)");
        int deliveredAtFailure = source.delivered.get();   // the attach thread, the only one delivering, is held inside pause 1
        source.pauseMayReturn(1).countDown();   // pause 1 lands: its re-check runs with the write already failed
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after pause 1 was let go");

        settled(write, "the write never completed after the sink failed");
        assertTrue(write.failed(), "the write fails");
        assertSame(sinkBroke, write.cause(), "the sink's failure is the cause the write fails with: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause1 = storePause(events, 1);
        List<HeldStream.Event> resumesAfterPause1 = events.subList(pause1 + 1, events.size()).stream()
                .filter(e -> e.kind().equals("resume")).toList();
        assertEquals(List.of(), resumesAfterPause1, "a failed write is never resumed — not by the re-check of the pause being entered: " + events);
        assertEquals(0, source.delivered.get() - deliveredAtFailure,
                "no chunk was delivered into the failed write (" + source.delivered.get() + " in total, " + deliveredAtFailure
                        + " at the failure): " + events);
        assertEquals(Optional.empty(), s.location(blob), "nothing stored");
        assertEquals(List.of(), TieredBlobStoreTest.spoolFiles(dir),
                "the sink had opened and its abort() ran before the write failed: no .blob and no .part left behind");
    }

    @Test
    void theReCheckThatResumesAPauseOvertakenByTheSinkRePausesWhenTheSinkFailedBeforeTheResumeTookEffect(@TempDir Path dir)
            throws Exception {
        HeldKey key = new HeldKey(false);
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        source.holdPause(1);    // pause 1, on the source thread: held before it takes effect
        source.holdResume(1);   // the re-check's resume of pause 1 — the first resume of the write: start() and the sink issue none
        IllegalStateException sinkBroke = new IllegalStateException("sink broke");
        CountDownLatch decided = latch();
        CountDownLatch sinkMayGo = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1 while it is being entered: it clears the flag and
        // leaves the resume to the re-check; held there, then it fails inside its try (the catch records the failure and pauses)
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) {
                decided.countDown();
                await(sinkMayGo, "the test never let the sink past its decision for pause 1");
                throw sinkBroke;
            }
        });
        BlobKey blob = BlobKey.body("m-1", 0);

        Attach attach = new Attach(s, blob, source);   // not handed over paused: the source delivers on its own thread
        await(source.pauseEntered(1), "the source thread never entered pause 1 (chunk 2 at the high-water mark)");
        source.attachMayReturn.countDown();   // start() attaches and resumes nothing: pause 1 is being entered
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after handler() was let go");
        key.gate.countDown();
        await(decided, "the sink never reached its decision for pause 1");
        source.pauseMayTakeEffect(1).countDown();   // pause 1 takes effect; the re-check finds the sink's decision and resumes
        await(source.resumeEntered(1), "the re-check never resumed pause 1");   // held before the resume takes effect
        sinkMayGo.countDown();                // the sink fails: recorded, the source paused, the sink aborted, the write failed
        settled(write, "the write never failed after the sink broke");
        int deliveredAtFailure = source.delivered.get();   // the source thread is held inside the re-check's resume
        source.resumeMayTakeEffect(1).countDown();   // the held resume takes effect after the failure's pause
        source.close();
        source.joined("the source thread never left after it was closed");

        assertTrue(write.failed(), "the write fails");
        assertSame(sinkBroke, write.cause(), "the sink's failure is the cause the write fails with: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int deliveredAfter = source.delivered.get() - deliveredAtFailure;
        assertAll(
                () -> assertTrue(source.isPaused(),
                        "the re-check's resume that took effect after the failure's pause was followed by a pause: the source stays "
                                + "paused, a failed write is never left flowing: " + events),
                () -> assertEquals(0, deliveredAfter,
                        "no chunk was delivered into the failed write after its failure (" + deliveredAtFailure + " at the failure): "
                                + events),
                () -> assertEquals(Optional.empty(), s.location(blob), "nothing stored"),
                () -> assertEquals(List.of(), TieredBlobStoreTest.spoolFiles(dir),
                        "the sink had opened and its abort() ran: no .blob and no .part left behind"));
    }

    @Test
    void theSinksResumeDecidedBeforeTheSourceFailedIsFollowedByAPauseSoTheFailedSourceStaysPaused(@TempDir Path dir)
            throws Exception {
        HeldKey key = new HeldKey(false);
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        // pause 1 is not held: it lands and its re-check runs at once
        source.holdBeforeChunk(3);
        RuntimeException reset = new RuntimeException("connection reset");
        CountDownLatch decided = latch();
        CountDownLatch sinkMayGo = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — after the attach, with pause 1 landed: the sink decides to resume, held before the resume
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) {
                decided.countDown();
                await(sinkMayGo, "the test never let the sink past its decision for pause 1");
            }
        });
        BlobKey blob = BlobKey.body("m-1", 0);

        Attach attach = new Attach(s, blob, source);
        await(source.heldBeforeChunk(3), "the source thread never came back from pause 1 to its loop (chunk 3)");
        source.attachMayReturn.countDown();   // start() attaches: pause 1 has landed and its re-check ran — nothing to resume
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after handler() was let go");
        key.gate.countDown();
        await(decided, "the sink never reached its decision for pause 1");
        source.failNow(reset);                // the store's fail() on the test thread: recorded, the source paused
        int deliveredAtFailure = source.delivered.get();   // the source thread is held before chunk 3
        sinkMayGo.countDown();                // the sink issues the resume it decided before the failure
        settled(write, "the write never failed after the source failed");
        boolean pausedAtTheFailure = source.isPaused();
        source.chunkMayGo(3).countDown();
        source.close();
        source.joined("the source thread never left after it was closed");

        assertTrue(write.failed(), "the write fails");
        assertSame(reset, write.cause(), "the source's failure is the cause the write fails with: " + write.cause());
        assertEquals(List.of(), List.of(reset.getSuppressed()), "the sink did not fail on its own: nothing suppressed");
        List<HeldStream.Event> events = List.copyOf(source.events);
        int deliveredAfter = source.delivered.get() - deliveredAtFailure;
        assertAll(
                () -> assertTrue(pausedAtTheFailure,
                        "the sink's resume, issued after the source's failure paused it, was followed by a pause: the failed source "
                                + "is left paused: " + events),
                () -> assertEquals(0, deliveredAfter,
                        "no chunk was delivered into the failed write after its failure (" + deliveredAtFailure + " at the failure): "
                                + events),
                () -> assertEquals(Optional.empty(), s.location(blob), "nothing stored"));
    }

    @Test
    void aPauseThatLandsWhileTheHandOverResumeIsInFlightIsReIssuedAfterItSoTheBackPressureBoundHolds(@TempDir Path dir)
            throws Exception {
        HeldKey key = new HeldKey(false);   // the sink cannot open before the test says
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        source.holdResume(1);               // start()'s hand-over resume, on the attach thread
        source.holdBeforeChunk(1);
        source.holdBeforeChunk(3);
        // pause 1 is not held
        source.attachMayReturn.countDown(); // the attach thread leaves handler() at once: start() decides with nothing delivered

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.resumeEntered(1), "start() never issued the hand-over resume");
        source.chunkMayGo(1).countDown();   // chunks 1-2 delivered, pause 1 lands, its re-check runs (no resume: the flag is set)
        await(source.heldBeforeChunk(3), "the source thread never came back from pause 1 to its loop (chunk 3)");
        source.resumeMayTakeEffect(1).countDown();   // the hand-over resume takes effect after pause 1
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after the hand-over resume was let go");
        boolean pausedAfterStart = source.isPaused();
        source.chunkMayGo(3).countDown();
        // the sink stays shut until the source thread stops: paused before chunk 3, or — if the undone pause stood — past the end
        await(source.stalled, "the source thread neither waited paused nor finished after chunk 3 was let go");
        key.gate.countDown();

        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        source.joined("the source thread never finished delivering the body");
        List<HeldStream.Event> events = List.copyOf(source.events);
        assertAll(
                () -> assertTrue(pausedAfterStart,
                        "start() returned with the source paused: the pause the hand-over resume undid was issued again: " + events),
                () -> assertTrue(s.peakQueuedBytes() <= BOUND,
                        "the queue never held more than the high-water mark plus one chunk — the undone pause let the body in: "
                                + s.peakQueuedBytes() + " bytes, events " + events),
                () -> assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events),
                () -> assertNoResumeWithoutAPauseBetween(events),
                () -> assertEquals(SIZE, write.result().size()),
                () -> assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash()));
    }

    @Test
    void beforeTheAttachASinkDecisionAfterTheReCheckLeavesTheOneResumeToStart(@TempDir Path dir) throws Exception {
        HeldKey key = new HeldKey(false);
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        // pause 1 is not held
        source.holdBeforeChunk(3);
        CountDownLatch decided = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1, before the attach: it clears the flag, resumes nothing
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) decided.countDown();
        });

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);   // held inside handler() until the test says
        await(source.heldBeforeChunk(3), "the source thread never came back from pause 1 to its loop (chunk 3)");
        key.gate.countDown();
        await(decided, "the sink never reached its decision for pause 1");
        source.attachMayReturn.countDown();   // start() attaches: the resume owed to pause 1 is its own
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after handler() was let go");
        source.chunkMayGo(3).countDown();

        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        source.joined("the source thread never finished delivering the body");
        List<HeldStream.Event> events = List.copyOf(source.events);
        assertEquals(List.of(new HeldStream.Event("resume", ATTACH)), resumesUntilNextPause(events, storePause(events, 1)),
                "pause 1 was resumed once, by start() on the attach thread — the sink decided before the attach and resumed nothing: "
                        + events);
        assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events);
        assertNoResumeWithoutAPauseBetween(events);
        assertTrue(s.peakQueuedBytes() <= BOUND, "the queue stayed under the high-water mark plus one chunk: " + s.peakQueuedBytes());
        assertEquals(SIZE, write.result().size());
        assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash());
    }

    @Test
    void aMarkedPauseTheHandOverResumeUndidIsReIssuedByItsOwnReCheckAndThenResumedOnce(@TempDir Path dir) throws Exception {
        HeldKey key = new HeldKey(false);   // the sink cannot open before the test says
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        source.holdResume(1);               // start()'s hand-over resume, on the attach thread
        source.holdBeforeChunk(1);
        source.holdBeforeChunk(3);
        source.holdPauseAfterEffect(1);     // pause 1, on the source thread: held after it took effect, its re-check pending
        source.attachMayReturn.countDown(); // the attach thread leaves handler() at once: start() decides with nothing delivered
        CountDownLatch decided = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1: it clears the flag and resumes nothing (pausing)
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) decided.countDown();
        });

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.resumeEntered(1), "start() never issued the hand-over resume");
        source.chunkMayGo(1).countDown();   // chunks 1-2 delivered; pause 1 is marked (the hand-over resume is in flight) and takes effect
        await(source.pauseTookEffect(1), "pause 1 never took effect on the source thread (chunk 2 at the high-water mark)");
        source.resumeMayTakeEffect(1).countDown();   // the hand-over resume undoes pause 1; start() finds pause 1's re-check pending
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after the hand-over resume was let go");
        key.gate.countDown();
        await(decided, "the sink never reached its decision for pause 1");
        source.pauseMayReturn(1).countDown();   // pause 1's re-check finds the mark with the hand-over done: it pauses once more
        await(source.heldBeforeChunk(3), "the source thread never came back from pause 1's re-check to its loop (chunk 3)");
        source.chunkMayGo(3).countDown();

        source.joined("the source thread never finished delivering the body");
        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause1 = storePause(events, 1);
        List<HeldStream.Event> afterPause1 = events.subList(pause1 + 1, Math.min(events.size(), pause1 + 4));
        assertAll(
                () -> assertEquals(SIZE, write.result().size()),
                () -> assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash()),
                () -> assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events),
                () -> assertNoResumeWithoutAPauseBetween(events),
                () -> assertTrue(s.peakQueuedBytes() <= BOUND,
                        "the queue never held more than the high-water mark plus one chunk — the undone pause let the body in: "
                                + s.peakQueuedBytes() + " bytes, events " + events),
                () -> assertEquals(List.of(new HeldStream.Event("resume", ATTACH), new HeldStream.Event("pause", SOURCE),
                                new HeldStream.Event("resume", SOURCE)), afterPause1,
                        "after pause 1: the hand-over resume undid it, pause 1's own re-check issued it once more and resumed it once "
                                + "(the sink had decided): " + events));
    }

    @Test
    void theSourceReachingTheMarkWhileStartsReIssueIsInFlightStartsNoSecondReCheckSoThatPauseIsResumedOnce(@TempDir Path dir)
            throws Exception {
        HeldKey key = new HeldKey(false);   // the sink cannot open before the test says
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        source.holdResume(1);               // start()'s hand-over resume, on the attach thread
        source.holdResume(2);               // the sink's resume after its decision at chunk 4
        source.holdBeforeChunk(1);
        source.holdBeforeChunk(3);
        source.holdBeforeChunk(5);
        source.holdPause(2);                // start()'s re-issue of pause 1, on the attach thread: held before it takes effect
        source.holdPause(3);                // a second pause on the source thread, before effect — reached only if enqueue issues one
        source.attachMayReturn.countDown(); // the attach thread leaves handler() at once: start() decides with nothing delivered
        CountDownLatch decided = latch();
        CountDownLatch sinkMayGo = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1: it clears the flag and resumes nothing (start()'s
        // re-issue is pausing); held there until the source has reached the mark again
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) {
                decided.countDown();
                await(sinkMayGo, "the test never let the sink past its decision for pause 1");
            }
        });

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.resumeEntered(1), "start() never issued the hand-over resume");
        source.chunkMayGo(1).countDown();   // chunks 1-2 delivered; pause 1 (marked) lands, its re-check resumes nothing
        await(source.heldBeforeChunk(3), "the source thread never came back from pause 1 to its loop (chunk 3)");
        source.resumeMayTakeEffect(1).countDown();   // the hand-over resume undoes pause 1; start() re-issues it: pause call 2
        await(source.pauseEntered(2), "start() never re-issued the pause the hand-over resume undid");
        key.gate.countDown();
        await(decided, "the sink never reached its decision for pause 1");
        CountDownLatch held = source.nextHold();
        source.chunkMayGo(3).countDown();   // the source flows (pause 2 has not taken effect): chunks 3-4 reach the mark
        await(held, "the source thread never stopped after reaching the mark at chunk 4 (neither at chunk 5 nor in a pause)");
        source.pauseMayTakeEffect(2).countDown();   // start()'s pause takes effect; its re-check resumes nothing
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after its re-issued pause was let go");
        sinkMayGo.countDown();              // the sink writes 2, polls 3 and 4, decides and resumes: resume call 2
        await(source.resumeEntered(2), "the sink never resumed the source after draining chunks 3-4");
        source.resumeMayTakeEffect(2).countDown();
        source.pauseMayTakeEffect(3).countDown();   // the unreached one of these two is harmless
        source.chunkMayGo(5).countDown();

        source.joined("the source thread never finished delivering the body");
        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause2 = storePause(events, 2);
        List<HeldStream.Event> afterPause2 = events.subList(pause2 + 1, Math.min(events.size(), pause2 + 3));
        assertAll(
                () -> assertEquals(SIZE, write.result().size()),
                () -> assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash()),
                () -> assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events),
                () -> assertNoResumeWithoutAPauseBetween(events),
                () -> assertTrue(s.peakQueuedBytes() <= BOUND,
                        "the queue never held more than the high-water mark plus one chunk: " + s.peakQueuedBytes() + " bytes, events "
                                + events),
                () -> assertEquals(new HeldStream.Event("pause", ATTACH), events.get(pause2),
                        "pause 2 is start()'s re-issue on the attach thread: " + events),
                () -> assertEquals(Optional.of(new HeldStream.Event("resume", SINK)), afterPause2.stream().findFirst(),
                        "start()'s re-issued pause, which the source's mark at chunk 4 joined, was resumed by the sink: " + events),
                () -> assertTrue(afterPause2.size() < 2 || afterPause2.get(1).kind().equals("pause"),
                        "the sink's resume of start()'s re-issued pause is the only one: the event after it, if any, is a pause: "
                                + events));
    }

    @Test
    void aResumeTheSinkDecidesDuringTheHandOverIsTakenOverByTheHandOverThreadAndNeverIssuedByTheSink(@TempDir Path dir)
            throws Exception {
        HeldKey key = new HeldKey(false);   // the sink cannot open before the test says
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        source.holdResume(1);               // start()'s hand-over resume, on the attach thread
        source.holdBeforeChunk(1);
        source.holdBeforeChunk(3);
        source.holdBeforeChunk(5);
        source.attachMayReturn.countDown(); // the attach thread leaves handler() at once: start() decides with nothing delivered
        CountDownLatch decided2 = latch();
        CountDownLatch sinkMayGo2 = latch();
        CountDownLatch decided3 = latch();
        CountDownLatch sinkMayGo3 = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1 while the hand-over resume is in flight: it clears
        // the flag; held there, before any resume it would issue, until the source has paused again at chunk 4.
        // call 3: chunk 3 polled (queued 60, not below half the mark: no decision) — the sink is past whatever it issued for
        // call 2; held there until the source has been let go at chunk 5. Its decision for the source's pause comes at chunk 4.
        s.afterSinkDecision(() -> {
            int call = hookCalls.incrementAndGet();
            if (call == 2) {
                decided2.countDown();
                await(sinkMayGo2, "the test never let the sink past its decision for pause 1");
            } else if (call == 3) {
                decided3.countDown();
                await(sinkMayGo3, "the test never let the sink past its poll of chunk 3");
            }
        });

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.resumeEntered(1), "start() never issued the hand-over resume");
        source.chunkMayGo(1).countDown();   // chunks 1-2 delivered; pause 1 (marked) takes effect, its re-check resumes nothing (the flag is set)
        await(source.heldBeforeChunk(3), "the source thread never came back from pause 1 to its loop (chunk 3)");
        key.gate.countDown();
        await(decided2, "the sink never reached its decision for pause 1");
        source.resumeMayTakeEffect(1).countDown();   // the hand-over resume undoes pause 1; start() reconciles after it
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after the hand-over resume was let go");
        source.chunkMayGo(3).countDown();   // chunks 3-4 reach the mark: the source pauses, its re-check resumes nothing
        await(source.heldBeforeChunk(5), "the source thread never came back from its pause at chunk 4 to its loop (chunk 5)");
        sinkMayGo2.countDown();             // the sink leaves its decision for pause 1: a resume issued now undoes the source's pause
        await(decided3, "the sink never polled chunk 3 after its decision for pause 1");
        source.chunkMayGo(5).countDown();
        sinkMayGo3.countDown();             // the sink polls chunk 4, decides for the source's pause and resumes it

        source.joined("the source thread never finished delivering the body");
        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause1 = storePause(events, 1);
        List<HeldStream.Event> afterPause1 = events.subList(pause1 + 1, Math.min(events.size(), pause1 + 6));
        assertAll(
                () -> assertEquals(SIZE, write.result().size()),
                () -> assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash()),
                () -> assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events),
                () -> assertNoResumeWithoutAPauseBetween(events),
                () -> assertTrue(s.peakQueuedBytes() <= BOUND,
                        "the queue never held more than the high-water mark plus one chunk — the sink's late resume let the body in: "
                                + s.peakQueuedBytes() + " bytes, events " + events),
                () -> assertEquals(List.of(new HeldStream.Event("resume", ATTACH), new HeldStream.Event("pause", ATTACH),
                                new HeldStream.Event("resume", ATTACH), new HeldStream.Event("pause", SOURCE),
                                new HeldStream.Event("resume", SINK)), afterPause1,
                        "after pause 1: the hand-over resume undid it, the hand-over thread issued it once more and resumed it once for "
                                + "the sink's deferred decision, the source paused at chunk 4 and the sink resumed that pause: " + events));
    }

    @Test
    void theReCheckOfAPauseMarkedDuringTheHandOverLeavesItsResumeToTheHandOverThreadWhileThatResumeIsInFlight(@TempDir Path dir)
            throws Exception {
        HeldKey key = new HeldKey(false);   // the sink cannot open before the test says
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        source.holdResume(1);               // start()'s hand-over resume, on the attach thread
        source.holdBeforeChunk(1);
        source.holdBeforeChunk(3);
        source.holdPauseAfterEffect(1);     // pause 1, on the source thread: held after it took effect, its re-check pending
        source.attachMayReturn.countDown(); // the attach thread leaves handler() at once: start() decides with nothing delivered
        CountDownLatch decided = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1: it clears the flag and resumes nothing (pausing)
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) decided.countDown();
        });

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.resumeEntered(1), "start() never issued the hand-over resume");
        source.chunkMayGo(1).countDown();   // chunks 1-2 delivered; pause 1 is marked (the hand-over resume is in flight) and takes effect
        await(source.pauseTookEffect(1), "pause 1 never took effect on the source thread (chunk 2 at the high-water mark)");
        key.gate.countDown();
        await(decided, "the sink never reached its decision for pause 1");
        source.pauseMayReturn(1).countDown();   // pause 1's re-check runs while the hand-over resume is still in flight
        await(source.heldBeforeChunk(3), "the source thread never came back from pause 1's re-check to its loop (chunk 3)");
        source.resumeMayTakeEffect(1).countDown();   // the hand-over resume takes effect; start() reconciles after it
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after the hand-over resume was let go");
        source.chunkMayGo(3).countDown();

        source.joined("the source thread never finished delivering the body");
        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause1 = storePause(events, 1);
        List<HeldStream.Event> afterPause1 = events.subList(pause1 + 1, Math.min(events.size(), pause1 + 4));
        assertAll(
                () -> assertEquals(SIZE, write.result().size()),
                () -> assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash()),
                () -> assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events),
                () -> assertNoResumeWithoutAPauseBetween(events),
                () -> assertTrue(s.peakQueuedBytes() <= BOUND,
                        "the queue never held more than the high-water mark plus one chunk: " + s.peakQueuedBytes() + " bytes, events "
                                + events),
                () -> assertEquals(List.of(new HeldStream.Event("resume", ATTACH), new HeldStream.Event("pause", ATTACH),
                                new HeldStream.Event("resume", ATTACH)), afterPause1,
                        "after pause 1: its re-check issued no resume during the hand-over resume; the hand-over resume undid it, the "
                                + "hand-over thread issued it once more and resumed it once: " + events));
    }

    @Test
    void aResumeTheSinkDecidesForASynchronousSourcesPauseInsideTheHandOverResumeIsIssuedByTheHandOverThreadAfterIt(@TempDir Path dir)
            throws Exception {
        HeldKey key = new HeldKey(false);   // the sink cannot open before the test says
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        HeldStream source = held(new GeneratedReadStream(SIZE, CHUNK), Set.of());
        source.holdAfterChunk(2);           // the attach thread, inside start()'s in.resume(), after the store's handler returned for chunk 2
        CountDownLatch decided = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1, which has landed and been re-checked on the attach
        // thread inside the hand-over resume: it clears the flag and, the hand-over resume in flight, leaves the resume to it
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) decided.countDown();
        });
        source.pause();   // handed over paused: start()'s hand-over resume delivers chunks 1-2 on the attach thread, inside in.resume()

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.chunkDelivered(2), "the attach thread never returned from the store's handler for chunk 2");
        String testThread = Thread.currentThread().getName();
        assertEquals(List.of(new HeldStream.Event("pause", testThread), new HeldStream.Event("resume", ATTACH),
                        new HeldStream.Event("pause", ATTACH)), List.copyOf(source.events),
                "staging: the attach thread is inside the hand-over resume with pause 1 issued on it and re-checked (no resume): "
                        + source.events);
        key.gate.countDown();               // the sink opens with chunks 1 and 2 queued (120)
        await(decided, "the sink never reached its decision for pause 1");
        source.chunkMayReturn(2).countDown();   // the drain sees the source paused and stops; in.resume() returns; start() reconciles
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after the delivery of chunk 2 was let go");

        settled(write, "the write never completed — nobody issued the resume the sink decided for pause 1");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause1 = storePause(events, 1);
        List<HeldStream.Event> afterPause1 = events.subList(pause1 + 1, Math.min(events.size(), pause1 + 3));
        assertAll(
                () -> assertEquals(SIZE, write.result().size()),
                () -> assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash()),
                () -> assertEquals(source.pauses.get(), source.resumes.get(), "every pause matched by exactly one resume: " + events),
                () -> assertNoResumeWithoutAPauseBetween(events),
                () -> assertTrue(s.peakQueuedBytes() <= BOUND,
                        "the queue never held more than the high-water mark plus one chunk: " + s.peakQueuedBytes() + " bytes, events "
                                + events),
                () -> assertEquals(Optional.of(new HeldStream.Event("resume", ATTACH)), afterPause1.stream().findFirst(),
                        "pause 1 was resumed by the hand-over thread after its resume, for the sink's deferred decision — never by the "
                                + "sink while the hand-over resume was in flight: " + events),
                () -> assertTrue(afterPause1.size() < 2 || afterPause1.get(1).kind().equals("pause"),
                        "the hand-over thread's resume of pause 1 is the only one: the event after it, if any, is a pause: " + events));
    }

    /** Asserts event order, not pauses == resumes: pause 2's re-check re-issues it with one idempotent pause call more than the resumes. */
    @Test
    void aResumeTheSinkDeferredForAMarkedPauseIsLeftToThePendingReCheckWhenTheSourcePausesAgainBeforeTheHandOverReconciles(
            @TempDir Path dir) throws Exception {
        HeldKey key = new HeldKey(false);   // the sink cannot open before the test says
        openOnExit.add(key.gate);
        TieredBlobStore s = store(dir, key);
        OwnThreadStream source = ownThread(SIZE, CHUNK);
        source.holdResume(1);               // start()'s hand-over resume, on the attach thread: held before its effect
        source.holdResumeAfterEffect(1);    // ... and after it, inside in.resume(): start() has not reconciled
        source.holdBeforeChunk(1);
        source.holdBeforeChunk(3);
        source.holdPauseAfterEffect(2);     // pause 2, on the source thread: held after it took effect, its re-check pending
        source.attachMayReturn.countDown(); // the attach thread leaves handler() at once: start() decides with nothing delivered
        CountDownLatch decided = latch();
        AtomicInteger hookCalls = new AtomicInteger();
        // call 2: chunk 2 polled (queued 0) — the sink's decision for pause 1, whose re-check has run: it clears the flag and,
        // the hand-over resume in flight, defers its resume to the hand-over thread (resumeDeferred)
        s.afterSinkDecision(() -> {
            if (hookCalls.incrementAndGet() == 2) decided.countDown();
        });

        Attach attach = new Attach(s, BlobKey.body("m-1", 0), source);
        await(source.resumeEntered(1), "start() never issued the hand-over resume");
        source.chunkMayGo(1).countDown();   // chunks 1-2 delivered; pause 1 is marked, takes effect, its re-check resumes nothing
        await(source.heldBeforeChunk(3), "the source thread never came back from pause 1 to its loop (chunk 3)");
        key.gate.countDown();
        await(decided, "the sink never reached its decision for pause 1");
        source.resumeMayTakeEffect(1).countDown();   // the hand-over resume undoes pause 1 (resume ATTACH)
        await(source.resumeTookEffect(1), "the hand-over resume never took effect on the attach thread");
        source.chunkMayGo(3).countDown();   // chunks 3-4 reach the mark: pause 2 is marked (hand-over in flight) and takes effect
        await(source.pauseTookEffect(2), "pause 2 never took effect on the source thread (chunk 4 at the high-water mark)");
        source.resumeMayReturn(1).countDown();   // in.resume() returns; start() reconciles with pause 2's re-check pending
        Future<Body> write = attach.joined("the attach thread's write(...) never returned after the hand-over resume was let return");
        source.pauseMayReturn(2).countDown();    // pause 2's re-check finds the deferred resume with the hand-over done: again

        source.joined("the source thread never finished delivering the body");
        settled(write, "the write never completed");
        assertTrue(write.succeeded(), "the write succeeds: " + write.cause());
        List<HeldStream.Event> events = List.copyOf(source.events);
        int pause2 = storePause(events, 2);
        assertAll(
                () -> assertEquals(SIZE, write.result().size()),
                () -> assertEquals(GeneratedReadStream.expectedHash(TieredBlobStoreTest.HASHER, SIZE), write.result().hash()),
                () -> assertNoResumeWithoutAPauseBetween(events),
                () -> assertTrue(s.peakQueuedBytes() <= BOUND,
                        "the queue never held more than the high-water mark plus one chunk — a resume undid pause 2 and let the body in: "
                                + s.peakQueuedBytes() + " bytes, events " + events),
                () -> assertEquals(Optional.of(new HeldStream.Event("pause", SOURCE)), events.stream().skip(pause2 + 1).findFirst(),
                        "right after pause 2 (issued while the hand-over resume was in flight, its re-check still pending when the "
                                + "hand-over thread reconciled): pause 2's own re-check issued it once more — never the sink's deferred "
                                + "resume issued plainly on the hand-over thread (resume ATTACH), which would undo pause 2: " + events));
    }

    // --- staging helpers ---

    private CountDownLatch latch() {
        CountDownLatch l = new CountDownLatch(1);
        openOnExit.add(l);
        return l;
    }

    private HeldStream held(ReadStream<Buffer> inner, Set<Integer> heldPauses) {
        HeldStream h = new HeldStream(inner, heldPauses);
        wrappers.add(h);   // every latch of it, the chunk holds armed later included, opened after the case
        return h;
    }

    private OwnThreadStream ownThread(long size, int chunkSize) {
        OwnThreadStream o = new OwnThreadStream(size, chunkSize);
        sources.add(o);   // closed and every latch of it opened after the case
        return o;
    }

    private static void await(CountDownLatch latch, String property) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError(property + " (not within 10 s)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting: " + property, e);
        }
    }

    static <T> void settled(Future<T> f, String property) {
        CountDownLatch done = new CountDownLatch(1);
        f.onComplete((result, failure) -> done.countDown());
        await(done, property);
    }

    private static boolean chainNames(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) if (c.getMessage() != null && c.getMessage().contains(text)) return true;
        return false;
    }

    /**
     * The index of the store's {@code n}-th pause (1-based): the pauses on any thread but the test's own — the test's pause
     * before the hand-over is on the test thread. Call on the test thread.
     */
    private static int storePause(List<HeldStream.Event> events, int n) {
        String testThread = Thread.currentThread().getName();
        int seen = 0;
        for (int i = 0; i < events.size(); i++) {
            HeldStream.Event e = events.get(i);
            if (e.kind().equals("pause") && !e.thread().equals(testThread) && ++seen == n) return i;
        }
        throw new AssertionError("the store paused the source fewer than " + n + " times: " + events);
    }

    /** The resume events between the pause at {@code from} and the next pause (on any thread); the next pause must exist. */
    private static List<HeldStream.Event> resumesBeforeNextPause(List<HeldStream.Event> events, int from) {
        List<HeldStream.Event> resumes = new ArrayList<>();
        for (HeldStream.Event e : events.subList(from + 1, events.size())) {
            if (e.kind().equals("pause")) return resumes;
            resumes.add(e);
        }
        throw new AssertionError("no pause after the store's pause at event " + from + " — the source was never paused again: " + events);
    }

    /** The resume events between the pause at {@code from} and the next pause, or the end of the events when none follows. */
    private static List<HeldStream.Event> resumesUntilNextPause(List<HeldStream.Event> events, int from) {
        List<HeldStream.Event> resumes = new ArrayList<>();
        for (HeldStream.Event e : events.subList(from + 1, events.size())) {
            if (e.kind().equals("pause")) break;
            resumes.add(e);
        }
        return resumes;
    }

    private static void assertNoResumeWithoutAPauseBetween(Collection<HeldStream.Event> recorded) {
        List<HeldStream.Event> events = List.copyOf(recorded);
        boolean lastWasResume = false;
        for (int i = 0; i < events.size(); i++) {
            boolean resume = events.get(i).kind().equals("resume");
            assertFalse(resume && lastWasResume, "two resumes without a pause between them, at event " + i + ": " + events);
            lastWasResume = resume;
        }
    }

    /** The write runs on its own virtual thread (start() runs there, and with it the first deliveries); its failure is rethrown on the test thread. */
    static final class Attach {
        private final AtomicReference<Future<Body>> write = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        Attach(TieredBlobStore s, BlobKey key, ReadStream<Buffer> source) { this(s, key, source, new CountDownLatch(1)); }

        Attach(TieredBlobStore s, BlobKey key, ReadStream<Buffer> source, CountDownLatch returned) {
            thread = Thread.ofVirtual().name(ATTACH).start(() -> {
                try {
                    write.set(s.write(key, source, 10_000));
                } catch (Throwable t) {
                    failure.set(t);   // rethrown by joined()
                } finally {
                    returned.countDown();
                }
            });
        }

        Future<Body> joined(String property) throws InterruptedException {
            if (!thread.join(Duration.ofSeconds(10))) {
                StringBuilder where = new StringBuilder();
                for (StackTraceElement frame : thread.getStackTrace()) where.append("\n\tat ").append(frame);
                throw new AssertionError(property + " (not within 10 s); the attach thread sits at:" + where);
            }
            if (failure.get() != null) throw new AssertionError("the attach thread's write(...) threw", failure.get());
            return write.get();
        }
    }

    /**
     * Wraps a source: delegates every call, counts {@code pauses} / {@code resumes} and the chunks handed to the store's
     * handler ({@code delivered}), records each pause / resume as an {@link Event} (kind, calling thread's name) in call
     * order — before delegating, so a pause the delegated resume causes is recorded after it. The store's pauses (those after
     * the handler was attached) are numbered from 1 in the order they are entered; a pause whose number is in
     * {@code heldPauses} holds its caller after {@code inner.pause()}: it signals {@link #pauseEntered(int)} and returns only
     * once the test opens {@link #pauseMayReturn(int)} (opened beforehand = observed, not held). {@link #holdAfterChunk(int)},
     * armed before the attach, holds the thread delivering the {@code n}-th chunk (1-based, counted by the wrapper's handler)
     * after the store's handler returned for it, in the wrapper's delivery: it signals {@link #chunkDelivered(int)} and
     * returns to the source only once the test opens {@link #chunkMayReturn(int)}. {@link #latches()} covers both kinds.
     */
    static final class HeldStream implements ReadStream<Buffer> {
        record Event(String kind, String thread) {}

        private record Hold(CountDownLatch entered, CountDownLatch mayReturn) {}

        final ReadStream<Buffer> inner;
        final AtomicInteger pauses = new AtomicInteger();
        final AtomicInteger resumes = new AtomicInteger();
        final AtomicInteger delivered = new AtomicInteger();
        final Queue<Event> events = new ConcurrentLinkedQueue<>();
        private final Map<Integer, Hold> holds;
        private final AtomicInteger storePauses = new AtomicInteger();
        private final Map<Integer, Hold> chunkHolds = new ConcurrentHashMap<>();
        private volatile boolean attached;

        HeldStream(ReadStream<Buffer> inner, Set<Integer> heldPauses) {
            this.inner = inner;
            this.holds = heldPauses.stream().collect(Collectors.toUnmodifiableMap(n -> n, n -> new Hold(new CountDownLatch(1), new CountDownLatch(1))));
        }

        CountDownLatch pauseEntered(int n) { return hold(n).entered(); }

        CountDownLatch pauseMayReturn(int n) { return hold(n).mayReturn(); }

        void holdAfterChunk(int n) { chunkHolds.put(n, new Hold(new CountDownLatch(1), new CountDownLatch(1))); }

        CountDownLatch chunkDelivered(int n) { return chunkHold(n).entered(); }

        CountDownLatch chunkMayReturn(int n) { return chunkHold(n).mayReturn(); }

        List<CountDownLatch> latches() {
            List<CountDownLatch> all = new ArrayList<>();
            holds.values().forEach(h -> all.addAll(List.of(h.entered(), h.mayReturn())));
            chunkHolds.values().forEach(h -> all.addAll(List.of(h.entered(), h.mayReturn())));
            return all;
        }

        private Hold chunkHold(int n) {
            Hold h = chunkHolds.get(n);
            if (h == null) throw new IllegalArgumentException("chunk " + n + " is not held by this stream: " + chunkHolds.keySet());
            return h;
        }

        private Hold hold(int n) {
            Hold h = holds.get(n);
            if (h == null) throw new IllegalArgumentException("store pause " + n + " is not held by this stream: " + holds.keySet());
            return h;
        }

        private void record(String kind) { events.add(new Event(kind, Thread.currentThread().getName())); }

        @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { inner.exceptionHandler(h); return this; }
        @Override public ReadStream<Buffer> endHandler(Handler<Void> h) { inner.endHandler(h); return this; }
        @Override public ReadStream<Buffer> fetch(long amount) { inner.fetch(amount); return this; }

        @Override public ReadStream<Buffer> handler(Handler<Buffer> h) {
            attached = true;
            inner.handler(h == null ? null : chunk -> {
                int n = delivered.incrementAndGet();
                h.handle(chunk);
                Hold hold = chunkHolds.get(n);   // after the store's handler returned for chunk n, on the delivering thread
                if (hold != null) {
                    hold.entered().countDown();
                    await(hold.mayReturn(), "the test never let the delivery of chunk " + n + " return to the source");
                }
            });
            return this;
        }

        @Override public ReadStream<Buffer> pause() {
            pauses.incrementAndGet();
            record("pause");
            inner.pause();
            if (attached) {
                int n = storePauses.incrementAndGet();
                Hold h = holds.get(n);
                if (h != null) {
                    h.entered().countDown();
                    await(h.mayReturn(), "the test never let store pause " + n + " return");
                }
            }
            return this;
        }

        @Override public ReadStream<Buffer> resume() {
            resumes.incrementAndGet();
            record("resume");
            inner.resume();
            return this;
        }
    }

    /**
     * A source that delivers on its own virtual thread ({@value #SOURCE}) and is not handed over paused: {@code handler(h)}
     * records the attaching thread, starts the source thread — which delivers a {@link GeneratedReadStream}-shaped body
     * ({@code byteAt(i)}, chunks of {@code chunkSize}) one chunk at a time, waiting (10 s bound) while paused, and ends once
     * unpaused — and then holds the attaching thread inside {@code handler()} until the test opens {@link #attachMayReturn}.
     * Pauses and resumes are counted at the call (that count numbers the holds, over every thread) and recorded as
     * {@link HeldStream.Event}s: a pause at its call, before any hold — the store's order of issuing pauses; a resume when it
     * takes effect, after any hold — so a resume held while a pause lands is recorded after that pause, as the source sees
     * it. Chunks handed to the store's handler are counted in {@code delivered}; the source thread's failure is rethrown by
     * {@link #joined(String)}.
     * <p>Armed before the attach (n counted from 1 over every thread): {@link #holdPause(int)} holds the n-th {@code pause()}
     * before it takes effect ({@link #pauseEntered(int)} / {@link #pauseMayTakeEffect(int)}); {@link #holdPauseAfterEffect(int)}
     * holds it after it took effect ({@link #pauseTookEffect(int)} / {@link #pauseMayReturn(int)}); {@link #holdResume(int)}
     * holds the n-th {@code resume()} before it takes effect ({@link #resumeEntered(int)} / {@link #resumeMayTakeEffect(int)});
     * {@link #holdResumeAfterEffect(int)} holds its caller after it took effect — the flag cleared, the event recorded, the
     * source thread signalled — and before it returns ({@link #resumeTookEffect(int)} / {@link #resumeMayReturn(int)});
     * {@link #holdBeforeChunk(int)} holds the source thread before
     * chunk n, before it looks at the pause flag. {@link #nextHold()} is a latch the source thread opens the next time it
     * reaches any of these holds, before that hold signals reached — so a latch armed after a hold was seen reached opens at
     * the hold after it. {@link #failNow(Throwable)} calls the store's exception handler on the calling thread;
     * {@link #isPaused()} is the flag the source thread honours; {@link #stalled} opens the first time the source thread
     * waits paused or leaves its loop (a sync point that holds whether or not the source was left paused). {@link #close()}:
     * a source waiting paused leaves at once, one held before a chunk the test has not released leaves when the hold opens —
     * neither delivers nor ends; a source released and not paused runs to its end.
     */
    static final class OwnThreadStream implements ReadStream<Buffer> {
        private record Hold(CountDownLatch reached, CountDownLatch mayGo) {
            Hold() { this(new CountDownLatch(1), new CountDownLatch(1)); }
        }

        final AtomicInteger pauses = new AtomicInteger();
        final AtomicInteger resumes = new AtomicInteger();
        final AtomicInteger delivered = new AtomicInteger();
        final Queue<HeldStream.Event> events = new ConcurrentLinkedQueue<>();
        final AtomicReference<String> attachThread = new AtomicReference<>();
        final CountDownLatch attachMayReturn = new CountDownLatch(1);
        /** Opened the first time the source thread stops flowing: it starts waiting while paused, or it leaves its loop. */
        final CountDownLatch stalled = new CountDownLatch(1);
        private final long size;
        private final int chunkSize;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Map<Integer, Hold> pauseHolds = new ConcurrentHashMap<>();          // before the pause takes effect
        private final Map<Integer, Hold> pauseEffectHolds = new ConcurrentHashMap<>();    // after it took effect
        private final Map<Integer, Hold> resumeHolds = new ConcurrentHashMap<>();
        private final Map<Integer, Hold> resumeEffectHolds = new ConcurrentHashMap<>();   // after the resume took effect
        private final Map<Integer, Hold> chunkHolds = new ConcurrentHashMap<>();
        private final Set<Integer> abandonedChunks = ConcurrentHashMap.newKeySet();   // chunk holds opened by close(), not by the test
        private final AtomicReference<CountDownLatch> nextHold = new AtomicReference<>();
        private final List<CountDownLatch> nextHolds = new CopyOnWriteArrayList<>();   // every latch nextHold() handed out
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition flowing = lock.newCondition();
        private boolean paused;   // guarded by lock
        private boolean closed;   // guarded by lock
        private volatile Handler<Buffer> dataHandler;
        private volatile Handler<Void> endHandler;
        private volatile Handler<Throwable> exceptionHandler;
        private volatile Thread source;

        OwnThreadStream(long size, int chunkSize) {
            this.size = size;
            this.chunkSize = chunkSize;
        }

        void holdPause(int n) { pauseHolds.put(n, new Hold()); }

        CountDownLatch pauseEntered(int n) { return armed(pauseHolds, n, "pause (before effect)").reached(); }

        CountDownLatch pauseMayTakeEffect(int n) { return armed(pauseHolds, n, "pause (before effect)").mayGo(); }

        void holdPauseAfterEffect(int n) { pauseEffectHolds.put(n, new Hold()); }

        CountDownLatch pauseTookEffect(int n) { return armed(pauseEffectHolds, n, "pause (after effect)").reached(); }

        CountDownLatch pauseMayReturn(int n) { return armed(pauseEffectHolds, n, "pause (after effect)").mayGo(); }

        void holdResume(int n) { resumeHolds.put(n, new Hold()); }

        CountDownLatch resumeEntered(int n) { return armed(resumeHolds, n, "resume").reached(); }

        CountDownLatch resumeMayTakeEffect(int n) { return armed(resumeHolds, n, "resume").mayGo(); }

        void holdResumeAfterEffect(int n) { resumeEffectHolds.put(n, new Hold()); }

        CountDownLatch resumeTookEffect(int n) { return armed(resumeEffectHolds, n, "resume (after effect)").reached(); }

        CountDownLatch resumeMayReturn(int n) { return armed(resumeEffectHolds, n, "resume (after effect)").mayGo(); }

        void holdBeforeChunk(int n) { chunkHolds.put(n, new Hold()); }

        CountDownLatch heldBeforeChunk(int n) { return armed(chunkHolds, n, "chunk").reached(); }

        CountDownLatch chunkMayGo(int n) { return armed(chunkHolds, n, "chunk").mayGo(); }

        /** A fresh latch the source thread opens the next time it reaches any of its holds (pause, resume or chunk). */
        CountDownLatch nextHold() {
            CountDownLatch l = new CountDownLatch(1);
            nextHolds.add(l);
            nextHold.set(l);
            return l;
        }

        private static Hold armed(Map<Integer, Hold> holds, int n, String what) {
            Hold h = holds.get(n);
            if (h == null) throw new IllegalArgumentException(what + " " + n + " is not held by this stream: " + holds.keySet());
            return h;
        }

        /**
         * Opens the pending {@link #nextHold()} (on the source thread), signals the hold reached, then waits until the test opens
         * it. The pending latch is taken before {@code reached} is signalled, so a latch armed after the test observed this hold
         * reached is opened at the source thread's next hold, never at this one.
         */
        private void block(Hold h, String property) {
            if (Thread.currentThread() == source) {
                CountDownLatch next = nextHold.getAndSet(null);
                if (next != null) next.countDown();
            }
            h.reached().countDown();
            await(h.mayGo(), property);
        }

        /** Every latch of this stream, for the test's exit: a thread held at any of them is let go. */
        List<CountDownLatch> latches() {
            List<CountDownLatch> all = new ArrayList<>(List.of(attachMayReturn, stalled));
            for (Map<Integer, Hold> holds : List.of(pauseHolds, pauseEffectHolds, resumeHolds, resumeEffectHolds, chunkHolds))
                holds.values().forEach(h -> all.addAll(List.of(h.reached(), h.mayGo())));
            all.addAll(nextHolds);
            return all;
        }

        void failNow(Throwable t) {
            Handler<Throwable> h = exceptionHandler;
            if (h == null) throw new AssertionError("no exception handler registered: the store never attached one");
            h.handle(t);
        }

        boolean isPaused() {
            lock.lock();
            try {
                return paused;
            } finally {
                lock.unlock();
            }
        }

        void close() {
            lock.lock();
            try {
                closed = true;
                flowing.signalAll();
            } finally {
                lock.unlock();
            }
            chunkHolds.forEach((n, h) -> {
                if (h.mayGo().getCount() > 0) {
                    abandonedChunks.add(n);   // before the countDown: the source thread sees it once the hold opens
                    h.mayGo().countDown();
                }
            });
        }

        private void record(String kind) { events.add(new HeldStream.Event(kind, Thread.currentThread().getName())); }

        @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { exceptionHandler = h; return this; }
        @Override public ReadStream<Buffer> endHandler(Handler<Void> h) { endHandler = h; return this; }

        @Override public ReadStream<Buffer> fetch(long amount) {
            throw new UnsupportedOperationException("OwnThreadStream is driven by pause() / resume() only; the store never fetches");
        }

        @Override public ReadStream<Buffer> handler(Handler<Buffer> h) {
            attachThread.set(Thread.currentThread().getName());
            dataHandler = h;
            Thread t = Thread.ofVirtual().name(SOURCE).unstarted(this::deliver);
            source = t;
            t.start();
            await(attachMayReturn, "the test never let the attach thread leave handler()");
            return this;
        }

        @Override public ReadStream<Buffer> pause() {
            int n = pauses.incrementAndGet();
            record("pause");
            Hold before = pauseHolds.get(n);
            if (before != null) block(before, "the test never let pause " + n + " take effect");
            lock.lock();
            try {
                paused = true;
            } finally {
                lock.unlock();
            }
            Hold after = pauseEffectHolds.get(n);
            if (after != null) block(after, "the test never let pause " + n + " return after it took effect");
            return this;
        }

        @Override public ReadStream<Buffer> resume() {
            int n = resumes.incrementAndGet();
            Hold h = resumeHolds.get(n);
            if (h != null) block(h, "the test never let resume " + n + " take effect");
            lock.lock();
            try {
                paused = false;
                record("resume");   // at the effect, under the lock the source thread reads the flag with
                flowing.signalAll();
            } finally {
                lock.unlock();
            }
            Hold after = resumeEffectHolds.get(n);
            if (after != null) block(after, "the test never let resume " + n + " return after it took effect");
            return this;
        }

        private void deliver() {
            try {
                long offset = 0;
                int chunk = 0;
                while (offset < size) {
                    chunk++;
                    Hold h = chunkHolds.get(chunk);
                    if (h != null) {
                        block(h, "the test never let chunk " + chunk + " go");
                        if (abandonedChunks.contains(chunk)) return;   // closed while held here
                    }
                    if (!awaitFlowing("chunk " + chunk)) return;
                    int n = (int) Math.min(chunkSize, size - offset);
                    byte[] bytes = new byte[n];
                    for (int i = 0; i < n; i++) bytes[i] = GeneratedReadStream.byteAt(offset + i);
                    offset += n;
                    delivered.incrementAndGet();
                    dataHandler.handle(Buffer.buffer(bytes));
                }
                if (!awaitFlowing("the end")) return;
                Handler<Void> end = endHandler;
                if (end != null) end.handle(null);
            } catch (Throwable t) {
                failure.set(t);   // rethrown by joined()
            } finally {
                stalled.countDown();
            }
        }

        /** Waits while paused (10 s bound); false = closed while paused: the source thread leaves without delivering. */
        private boolean awaitFlowing(String what) throws InterruptedException {
            lock.lock();
            try {
                long left = TimeUnit.SECONDS.toNanos(10);
                while (paused) {
                    if (closed) return false;
                    stalled.countDown();
                    if (left <= 0) throw new AssertionError("the source stayed paused for 10 s before " + what + ": " + events);
                    left = flowing.awaitNanos(left);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        void joined(String property) throws InterruptedException {
            Thread t = source;
            if (t == null) throw new AssertionError(property + " (the source thread was never started: no handler attached)");
            if (!t.join(Duration.ofSeconds(10))) throw new AssertionError(property + " (not within 10 s): " + events);
            if (failure.get() != null) throw new AssertionError("the source thread failed", failure.get());
        }
    }

    /** The test's spool key: a real AES key whose material is handed out only once the test opens {@link #gate}; {@code refuse} = then none at all. */
    static final class HeldKey implements SecretKey {
        private final SecretKey real = TieredBlobStoreTest.key();
        private final boolean refuse;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);

        HeldKey(boolean refuse) { this.refuse = refuse; }

        @Override public String getAlgorithm() { return real.getAlgorithm(); }
        @Override public String getFormat() { return real.getFormat(); }
        @Override public byte[] getEncoded() {
            reached.countDown();
            try {
                if (!gate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the test never opened the spool key's gate");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted at the spool key's gate", e);
            }
            return refuse ? null : real.getEncoded();
        }
    }
}
