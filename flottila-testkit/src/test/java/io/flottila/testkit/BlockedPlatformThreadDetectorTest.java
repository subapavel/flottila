package io.flottila.testkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLAUDE.md: a blocked platform thread in a test is a failure. The detector watches the platform
 * executors a test hands out and reports a task that blocks (BLOCKED / WAITING / TIMED_WAITING)
 * for at least the threshold. Every case but one drives the detector from a {@link FakeClock}: each
 * advance is one sample, the thread state is staged first (spin until observed, never a sleep), so
 * which state the sampler sees and when is decided by the test, not by wall time. The one wall-time
 * case proves the sampler thread of {@link BlockedPlatformThreadDetector#start(Duration)} samples.
 */
class BlockedPlatformThreadDetectorTest {

    private static final Duration THRESHOLD = Duration.ofMillis(100);
    private static final Duration JOIN_BOUND = Duration.ofSeconds(10);

    private static FakeClock clock() {
        return FakeClock.at(Instant.parse("2026-01-01T00:00:00.850Z"));
    }

    // ---- 1. WAITING -------------------------------------------------------------------------

    @Test
    void drivenReportsAPoolThreadWaitingForAtLeastTheThresholdOnceWithTheClockTimeSinceTheFirstBlockedSample() throws Exception {
        FakeClock clock = clock();
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock)) {
            ExecutorService pool = detector.watchedPlatformPool("p", 1);
            CountDownLatch gate = new CountDownLatch(1);
            AtomicReference<Thread> worker = new AtomicReference<>();
            Future<?> task = pool.submit(() -> { worker.set(Thread.currentThread()); gate.await(); return null; });
            Thread t = spinUntil(worker, task, Thread.State.WAITING);

            clock.advance(THRESHOLD);
            assertEquals(List.of(), detector.violations(), "the first sample only sees the block start");
            clock.advance(THRESHOLD);

            assertEquals(1, detector.violations().size(), "one violation: " + detector.violations());
            BlockedPlatformThreadDetector.Violation v = detector.violations().getFirst();
            assertEquals(t.getName(), v.thread());
            assertEquals("p-0", v.thread(), "the pool names its threads <name>-<n>");
            assertFalse(v.virtual(), "the blocked thread is a platform thread");
            assertEquals(THRESHOLD, v.blockedFor(), "the clock advanced between the first blocked sample and the report");
            assertTrue(v.stackTrace().stream().anyMatch(f -> f.getClassName().equals(CountDownLatch.class.getName())
                    && f.getMethodName().equals("await")), "the stack shows the CountDownLatch.await it blocked in: " + v.stackTrace());
            assertTrue(detector.firstViolation().isDone(), "firstViolation completes with the report");
            assertEquals(v, detector.firstViolation().getNow(null));

            clock.advance(THRESHOLD);
            assertEquals(Thread.State.WAITING, t.getState(), "still blocked at the third sample");
            assertEquals(List.of(v), detector.violations(), "a block still in progress is reported once");

            gate.countDown();
            join(task, t);
            clock.advance(THRESHOLD);
            assertEquals(List.of(v), detector.violations(), "a reported block is reported once");
        }
    }

    // ---- 2. BLOCKED -------------------------------------------------------------------------

    @Test
    void drivenReportsAPoolThreadBlockedOnAMonitorOnce() throws Exception {
        FakeClock clock = clock();
        Object monitor = new Object();
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock)) {
            ExecutorService pool = detector.watchedPlatformPool("mon", 1);
            AtomicReference<Thread> worker = new AtomicReference<>();
            Future<?> task;
            Thread t;
            synchronized (monitor) {
                task = pool.submit(() -> {
                    worker.set(Thread.currentThread());
                    synchronized (monitor) { return null; }
                });
                t = spinUntil(worker, task, Thread.State.BLOCKED);
                clock.advance(THRESHOLD);
                assertEquals(List.of(), detector.violations(), "the first sample only sees the block start");
                clock.advance(THRESHOLD);
            }
            join(task, t);

            assertEquals(1, detector.violations().size(), "one violation: " + detector.violations());
            BlockedPlatformThreadDetector.Violation v = detector.violations().getFirst();
            assertEquals(t.getName(), v.thread());
            assertFalse(v.virtual());
            assertEquals(THRESHOLD, v.blockedFor());

            clock.advance(THRESHOLD);
            assertEquals(List.of(v), detector.violations(), "the monitor is free, nothing new");
        }
    }

    // ---- 3. TIMED_WAITING -------------------------------------------------------------------

    @Test
    void drivenReportsAPoolThreadInATimedWaitAcrossAWholeSecondAndSamplesOnClockSetToo() throws Exception {
        FakeClock clock = clock();
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock)) {
            ExecutorService pool = detector.watchedPlatformPool("timed", 1);
            CountDownLatch gate = new CountDownLatch(1);
            AtomicReference<Thread> worker = new AtomicReference<>();
            Future<?> task = pool.submit(() -> { worker.set(Thread.currentThread()); return gate.await(1, TimeUnit.HOURS); });
            Thread t = spinUntil(worker, task, Thread.State.TIMED_WAITING);

            clock.advance(THRESHOLD);
            assertEquals(List.of(), detector.violations());
            // the whole-second crossing (.95 s -> 2.00 s) pins the seconds half of the time source
            Duration later = Duration.ofSeconds(1).plusMillis(50);
            clock.set(clock.instant().plus(later));

            assertEquals(1, detector.violations().size(), "clock.set is a sample too: " + detector.violations());
            BlockedPlatformThreadDetector.Violation v = detector.violations().getFirst();
            assertEquals(t.getName(), v.thread());
            assertFalse(v.virtual());
            assertEquals(later, v.blockedFor(), "blockedFor is the clock time since the first blocked sample, not the threshold");

            gate.countDown();
            join(task, t);
            clock.advance(THRESHOLD);
            assertEquals(List.of(v), detector.violations());
        }
    }

    // ---- 4. no violation --------------------------------------------------------------------

    @Test
    void drivenABusyTaskThatNeverBlocksIsNotAViolation() throws Exception {
        FakeClock clock = clock();
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock)) {
            ExecutorService pool = detector.watchedPlatformPool("busy", 1);
            Stop stop = new Stop();
            AtomicReference<Thread> worker = new AtomicReference<>();
            Future<?> task = pool.submit(() -> {
                worker.set(Thread.currentThread());
                while (!stop.now) Thread.onSpinWait();
            });
            Thread t = spinUntil(worker, task, Thread.State.RUNNABLE);

            try {
                for (int i = 0; i < 10; i++) clock.advance(THRESHOLD);

                assertEquals(Thread.State.RUNNABLE, t.getState(), "still busy when the last sample ran");
            } finally {
                stop.now = true;
            }
            join(task, t);
            assertEquals(List.of(), detector.violations(), "a RUNNABLE task is busy, not blocked");
            assertFalse(detector.firstViolation().isDone());
        }
    }

    @Test
    void drivenBlockingOnAVirtualThreadIsFine() throws Exception {
        FakeClock clock = clock();
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock)) {
            CountDownLatch gate = new CountDownLatch(1);
            Thread vt = detector.watchedVirtualThread("msg-1", () -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while parked", e);
                }
            });
            assertTrue(vt.isVirtual(), "watchedVirtualThread hands out a virtual thread");
            while (!(vt.getState() == Thread.State.WAITING && LockSupport.getBlocker(vt) != null)) {
                assertTrue(vt.isAlive(), "the virtual thread ended before it parked");
                Thread.onSpinWait();
            }

            for (int i = 0; i < 10; i++) clock.advance(THRESHOLD);

            assertEquals(Thread.State.WAITING, vt.getState(), "still parked when the last sample ran");
            gate.countDown();
            assertTrue(vt.join(JOIN_BOUND), "the virtual thread ends once released: " + Arrays.toString(vt.getStackTrace()));
            assertEquals(List.of(), detector.violations(), "virtual threads may block; that is the point");
            assertFalse(detector.firstViolation().isDone());
        }
    }

    @Test
    void drivenAnIdlePoolThreadWaitingForWorkIsNotAViolation() throws Exception {
        FakeClock clock = clock();
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock)) {
            ExecutorService pool = detector.watchedPlatformPool("idle", 1);
            AtomicReference<Thread> worker = new AtomicReference<>();
            Future<?> task = pool.submit(() -> worker.set(Thread.currentThread()));
            join(task, null);
            Thread t = worker.get();
            // the task is done; the pool thread now parks in the work queue's take()
            while (!(t.getState() == Thread.State.WAITING && LockSupport.getBlocker(t) != null)) {
                assertTrue(t.isAlive(), "the pool thread ended before it parked for work");
                Thread.onSpinWait();
            }

            for (int i = 0; i < 10; i++) clock.advance(THRESHOLD);

            assertEquals(Thread.State.WAITING, t.getState(), "still idle when the last sample ran");
            assertEquals(List.of(), detector.violations(), "idle waiting for work is not blocking inside a task");
            assertFalse(detector.firstViolation().isDone());
        }
    }

    // ---- 5. below the threshold -------------------------------------------------------------

    @Test
    void drivenABlockThatEndsBeforeTheSecondSampleIsNotAViolation() throws Exception {
        FakeClock clock = clock();
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock)) {
            ExecutorService pool = detector.watchedPlatformPool("short", 1);
            CountDownLatch gate = new CountDownLatch(1);
            AtomicReference<Thread> worker = new AtomicReference<>();
            Future<?> task = pool.submit(() -> { worker.set(Thread.currentThread()); gate.await(); return null; });
            Thread t = spinUntil(worker, task, Thread.State.WAITING);

            clock.advance(THRESHOLD.minusNanos(1));
            gate.countDown();
            join(task, t);
            clock.advance(THRESHOLD);

            assertEquals(List.of(), detector.violations(), "the thread was no longer blocked when the second sample came");
            assertFalse(detector.firstViolation().isDone());
        }
    }

    @Test
    void drivenARunnableSampleRestartsTheBlockCountAndTheThresholdIsInclusive() throws Exception {
        FakeClock clock = clock();
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock)) {
            ExecutorService pool = detector.watchedPlatformPool("again", 1);
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);
            Stop busy = new Stop();
            AtomicReference<Thread> worker = new AtomicReference<>();
            Future<?> task = pool.submit(() -> {
                worker.set(Thread.currentThread());
                first.await();
                while (!busy.now) Thread.onSpinWait();
                second.await();
                return null;
            });
            Thread t = spinUntil(worker, task, Thread.State.WAITING);

            clock.advance(THRESHOLD.minusNanos(1));       // blocked: the count starts here
            first.countDown();
            spinUntil(worker, task, Thread.State.RUNNABLE);
            clock.advance(Duration.ofNanos(1));            // running: the count is reset
            busy.now = true;
            spinUntil(worker, task, Thread.State.WAITING);  // blocked again, in second.await()

            clock.advance(THRESHOLD.minusNanos(1));        // the count restarts here
            assertEquals(List.of(), detector.violations(),
                    "a RUNNABLE sample in between ends the first block; the second has only just been seen");
            clock.advance(THRESHOLD.minusNanos(1));
            assertEquals(List.of(), detector.violations(), "one nanosecond below the threshold");
            clock.advance(Duration.ofNanos(1));

            assertEquals(1, detector.violations().size(), "exactly the threshold is a violation: " + detector.violations());
            assertEquals(THRESHOLD, detector.violations().getFirst().blockedFor());
            assertEquals(t.getName(), detector.violations().getFirst().thread());

            second.countDown();
            join(task, t);
        }
    }

    // ---- 6. wall time: the sampler thread samples -------------------------------------------

    @Test
    void reportsATaskThatBlocksAPlatformThread() throws Exception {
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.start(Duration.ofMillis(20))) {
            ExecutorService pool = detector.watchedPlatformPool("jco", 1);
            CountDownLatch gate = new CountDownLatch(1);
            pool.submit(() -> { gate.await(); return null; });

            BlockedPlatformThreadDetector.Violation v = detector.firstViolation().get(5, TimeUnit.SECONDS);
            gate.countDown();

            assertTrue(v.thread().startsWith("jco-"), "names the pool thread, got " + v.thread());
            assertFalse(v.virtual(), "the blocked thread is a platform thread");
            assertTrue(v.blockedFor().compareTo(Duration.ofMillis(20)) >= 0, "at least the threshold: " + v.blockedFor());
            assertTrue(v.blockedFor().compareTo(Duration.ofSeconds(5)) < 0, "a real duration, not a timestamp: " + v.blockedFor());
            assertTrue(v.stackTrace().stream().anyMatch(f -> f.getClassName().equals(CountDownLatch.class.getName())),
                    "the stack shows where it blocked");
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, detector.violations().size());
        }
    }

    // ---- 7. closed --------------------------------------------------------------------------

    /**
     * FakeClock has no un-register, so the detector's listener stays. The pool thread is BLOCKED on a
     * monitor, not WAITING on a latch: {@code close()} interrupts the pools it handed out, an
     * interrupt ends a latch wait (the state at the next sample would race) but not a monitor entry.
     */
    @Test
    void drivenAfterCloseAnAdvanceRecordsNothing() throws Exception {
        FakeClock clock = clock();
        Object monitor = new Object();
        BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.driven(THRESHOLD, clock);
        ExecutorService pool = detector.watchedPlatformPool("closed", 1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        Future<?> task;
        Thread t;
        synchronized (monitor) {
            task = pool.submit(() -> {
                worker.set(Thread.currentThread());
                synchronized (monitor) { return null; }
            });
            t = spinUntil(worker, task, Thread.State.BLOCKED);
            clock.advance(THRESHOLD);                       // the block start is seen while open
            detector.close();
            assertEquals(Thread.State.BLOCKED, t.getState(), "close() does not end a monitor entry");

            clock.advance(THRESHOLD);                       // would report if close() left sampling on

            assertEquals(Thread.State.BLOCKED, t.getState(), "still blocked when the post-close advance ran");
        }
        join(task, t);
        assertEquals(List.of(), detector.violations(), "a closed detector does not sample");
        assertFalse(detector.firstViolation().isDone());
    }

    // ---- 8. the JUnit extension -------------------------------------------------------------

    @Test
    void junitExtensionFailsTheTestThatBlockedAPlatformThread() {
        var results = org.junit.platform.testkit.engine.EngineTestKit.engine("junit-jupiter")
                .selectors(org.junit.platform.engine.discovery.DiscoverySelectors.selectClass(BlockingTestCase.class))
                .execute()
                .testEvents();
        assertEquals(1, results.failed().count());
        assertEquals(1, results.succeeded().count());
        String message = results.failed().stream().findFirst().orElseThrow().getPayload(org.junit.platform.engine.TestExecutionResult.class)
                .orElseThrow().getThrowable().orElseThrow().getMessage();
        assertTrue(message.startsWith("blocked a platform thread: "), message);
    }

    /** Run only through EngineTestKit above (not picked up by surefire: no *Test suffix). */
    @org.junit.jupiter.api.extension.ExtendWith(NoBlockedPlatformThreads.class)
    static class BlockingTestCase {

        @Test
        void blocks(BlockedPlatformThreadDetector detector) throws Exception {
            ExecutorService pool = detector.watchedPlatformPool("bad", 1);
            CountDownLatch gate = new CountDownLatch(1);
            pool.submit(() -> { gate.await(); return null; });
            detector.firstViolation().get(5, TimeUnit.SECONDS);
            gate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        @Test
        void behaves(BlockedPlatformThreadDetector detector) throws Exception {
            ExecutorService pool = detector.watchedPlatformPool("good", 1);
            pool.submit(() -> 42).get(5, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** A stop flag read by a spinning task. */
    private static final class Stop {
        volatile boolean now;
    }

    /**
     * Spins until the task's thread (captured by the task body) is in {@code state}; for WAITING and
     * TIMED_WAITING also parked with a blocker (the task's own wait, not a transient state). Fails at
     * once if the task ends first.
     */
    private static Thread spinUntil(AtomicReference<Thread> worker, Future<?> task, Thread.State state) {
        boolean parked = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
        while (true) {
            Thread t = worker.get();
            if (t != null && t.getState() == state && (!parked || LockSupport.getBlocker(t) != null)) return t;
            if (task.isDone()) {
                try {
                    task.get();
                } catch (ExecutionException e) {
                    throw new AssertionError("the task failed before its thread reached " + state, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted", e);
                }
                throw new AssertionError("the task ended before its thread reached " + state);
            }
            Thread.onSpinWait();
        }
    }

    /** Waits for the task to end; a bound only, the task is released before this is called. */
    private static void join(Future<?> task, Thread worker) throws Exception {
        try {
            task.get(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the task did not end within " + JOIN_BOUND + "; its thread sits at "
                    + (worker == null ? "?" : Arrays.toString(worker.getStackTrace())), e);
        }
    }
}
