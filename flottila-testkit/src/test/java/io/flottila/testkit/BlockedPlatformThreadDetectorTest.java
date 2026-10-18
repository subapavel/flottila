package io.flottila.testkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLAUDE.md: a blocked platform thread in a test is a failure. The detector watches the platform
 * executors a test hands out and reports a task that blocks (BLOCKED / WAITING / TIMED_WAITING)
 * for longer than the threshold. No sleeps in the test: the latch is released only after the
 * detector has reported.
 */
class BlockedPlatformThreadDetectorTest {

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

    /**
     * Holds the calling test for at least {@code threshold} of real time without sleeping: a second
     * detector watches a deliberately blocked task and reports once the threshold has passed.
     */
    private static void waitAtLeast(Duration threshold) throws Exception {
        try (BlockedPlatformThreadDetector probe = BlockedPlatformThreadDetector.start(threshold)) {
            ExecutorService pool = probe.watchedPlatformPool("probe", 1);
            CountDownLatch gate = new CountDownLatch(1);
            pool.submit(() -> { gate.await(); return null; });
            probe.firstViolation().get(5, TimeUnit.SECONDS);
            gate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void anIdlePoolThreadWaitingForWorkIsNotAViolation() throws Exception {
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.start(Duration.ofMillis(10))) {
            ExecutorService pool = detector.watchedPlatformPool("idle", 2);
            CountDownLatch ran = new CountDownLatch(1);
            pool.submit(ran::countDown);
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            // the pool thread now waits for work; keep it idle for several thresholds while the sampler runs
            waitAtLeast(Duration.ofMillis(10));
            waitAtLeast(Duration.ofMillis(10));
            waitAtLeast(Duration.ofMillis(10));
            assertTrue(detector.violations().isEmpty(), "idle waiting is not blocking inside a task: " + detector.violations());
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aBusyTaskThatNeverBlocksIsNotAViolation() throws Exception {
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.start(Duration.ofMillis(10))) {
            ExecutorService pool = detector.watchedPlatformPool("busy", 1);
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            var spinning = pool.submit(() -> { long x = 0; while (!stop.get()) x += Thread.currentThread().hashCode(); return x; });
            waitAtLeast(Duration.ofMillis(10));
            waitAtLeast(Duration.ofMillis(10));
            stop.set(true);
            spinning.get(5, TimeUnit.SECONDS);
            assertTrue(detector.violations().isEmpty(), "a RUNNABLE task is busy, not blocked: " + detector.violations());
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void blockingOnAVirtualThreadIsFine() throws Exception {
        try (BlockedPlatformThreadDetector detector = BlockedPlatformThreadDetector.start(Duration.ofMillis(10))) {
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch parked = new CountDownLatch(1);
            Thread vt = detector.watchedVirtualThread("msg-1", () -> {
                parked.countDown();
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(parked.await(5, TimeUnit.SECONDS));
            waitAtLeast(Duration.ofMillis(10));
            waitAtLeast(Duration.ofMillis(10));
            gate.countDown();
            vt.join(Duration.ofSeconds(5));
            assertTrue(detector.violations().isEmpty(), "virtual threads may block; that is the point");
        }
    }

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
        assertTrue(message.contains("blocked a platform thread"), message);
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
}
