package io.flottila.cli;

import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03b1 (D-080): when {@code flottila test} may move virtual time. {@code Quiescence.quiet()} is true only on two consecutive
 * samples that each show no virtual thread mounted and none queued, with equal ({@code equals}) fingerprints — the fingerprint
 * being what the runner reads of the case's world (timer schedules and cancels, clock, mock records, queue size, DLQ rows,
 * answer done).
 *
 * <p>The type these tests define (package-private in {@code io.flottila.cli}, P2-03b1 test-author within the packet):
 * <ul>
 *   <li>{@code Quiescence.ofJvm(Supplier<?> fingerprint)} — mounted and queued from the JVM's
 *       {@code jdk.management.VirtualThreadSchedulerMXBean} ({@code getMountedVirtualThreadCount()},
 *       {@code getQueuedVirtualThreadCount()});</li>
 *   <li>{@code new Quiescence(IntSupplier mounted, LongSupplier queued, Supplier<?> fingerprint)} — the same over given
 *       readings;</li>
 *   <li>{@code boolean quiet()} — takes samples now; whether the condition above holds. A probe may keep its last sample
 *       between calls; it never answers true on a single sample.</li>
 * </ul>
 * The JVM cases stage a real virtual thread (spinning: mounted; parked on a Vert.x future: unmounted); the scripted cases pin
 * each reading — a queued thread with nothing mounted cannot be staged on a real scheduler.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class QuiescenceTest {

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("not within 10 s: " + what);
            Thread.onSpinWait();
        }
    }

    /** Answers {@code script[i]} on its i-th call (0-based), its last value from then on. */
    static IntSupplier scripted(int... script) {
        AtomicInteger calls = new AtomicInteger();
        return () -> script[Math.min(calls.getAndIncrement(), script.length - 1)];
    }

    // --- the JVM's scheduler

    /** A virtual thread that spins is mounted: never quiet while it spins; the same probe is quiet once it has ended. */
    @Test
    void spinningVirtualThreadIsNotQuiet() throws InterruptedException {
        AtomicBoolean spinning = new AtomicBoolean();
        AtomicBoolean release = new AtomicBoolean();
        Thread spinner = Thread.ofVirtual().name("spinner").start(() -> {
            spinning.set(true);
            while (!release.get()) Thread.onSpinWait();
        });
        Quiescence quiescence = Quiescence.ofJvm(() -> "unchanged");
        try {
            until(spinning::get, "the spinner spins");

            List<Integer> quietAt = new ArrayList<>();
            for (int i = 0; i < 50; i++) if (quiescence.quiet()) quietAt.add(i);

            assertTrue(spinner.isAlive() && !release.get(), "every sample was taken while the spinner spun");
            assertEquals(List.of(), quietAt, "never quiet while a virtual thread is mounted");
        } finally {
            release.set(true);
            spinner.join();
        }
        until(quiescence::quiet, "quiet once the spinner has ended (the probe is not always false)");
    }

    /** A virtual thread parked on a Vert.x future (as a message's thread waits for a mock's timer) is unmounted: quiet. */
    @Test
    void parkedVirtualThreadIsQuiet() throws InterruptedException {
        Promise<Void> gate = Promise.promise();
        Thread parked = Thread.ofVirtual().name("parked").start(() -> gate.future().await());
        try {
            until(() -> parked.getState() == Thread.State.WAITING, "the virtual thread parks in await()");
            Quiescence quiescence = Quiescence.ofJvm(() -> "unchanged");

            until(quiescence::quiet, "quiet while the only virtual thread is parked");

            assertEquals(Thread.State.WAITING, parked.getState(), "still parked: quiet was seen with it alive, not after it ended");
        } finally {
            gate.complete();
            parked.join();
        }
    }

    // --- each reading

    /** Nothing mounted, nothing queued, equal fingerprints (fresh instances on every sample: equality, not identity): quiet. */
    @Test
    void idleSamplesWithEqualFingerprintsAreQuiet() {
        Quiescence quiescence = new Quiescence(() -> 0, () -> 0L, () -> new ArrayList<>(List.of("timer 3/1", "queue 0", "dlq 0")));

        until(quiescence::quiet, "quiet");
        assertTrue(quiescence.quiet(), "and stays quiet while nothing changes");
    }

    /** A virtual thread queued for a carrier, none mounted: not quiet; quiet once the queue is empty. */
    @Test
    void aQueuedVirtualThreadIsNotQuiet() {
        AtomicLong queued = new AtomicLong(1);
        Quiescence quiescence = new Quiescence(() -> 0, queued::get, () -> "unchanged");

        for (int i = 0; i < 20; i++) assertFalse(quiescence.quiet(), "sample " + i + ": one virtual thread queued");

        queued.set(0);
        until(quiescence::quiet, "quiet once nothing is queued");
    }

    /** A fingerprint that changes from one sample to the next (the case's world moved): not quiet; quiet once it holds still. */
    @Test
    void aChangingFingerprintIsNotQuiet() {
        AtomicInteger changes = new AtomicInteger();
        AtomicBoolean still = new AtomicBoolean();
        Quiescence quiescence = new Quiescence(() -> 0, () -> 0L, () -> still.get() ? -1 : changes.incrementAndGet());

        for (int i = 0; i < 20; i++) assertFalse(quiescence.quiet(), "sample " + i + ": the fingerprint moved");

        still.set(true);
        until(quiescence::quiet, "quiet once the fingerprint holds still");
    }

    /** Two consecutive samples, both idle: a busy first or a busy second sample is not quiet; the samples after are. */
    @Test
    void oneBusySampleOfTwoIsNotQuiet() {
        Quiescence busyFirst = new Quiescence(scripted(1, 0), () -> 0L, () -> "unchanged");
        Quiescence busySecond = new Quiescence(scripted(0, 1, 0), () -> 0L, () -> "unchanged");

        assertFalse(busyFirst.quiet(), "the first sample had a virtual thread mounted");
        assertFalse(busySecond.quiet(), "the second sample had a virtual thread mounted");

        until(busyFirst::quiet, "quiet on the idle samples after");
        until(busySecond::quiet, "quiet on the idle samples after");
    }

    // --- the steady time (F-17): idle samples under one fingerprint for a stretch of wall time, on a scripted nanoTime

    static final Duration STEADY = Duration.ofMillis(1);

    /**
     * Idle samples with one fingerprint are not quiet until {@code steady} has passed since the first of them: not at once, not
     * a nanosecond before (1 ms is 1 000 000 ns, not 1), quiet at it and after.
     */
    @Test
    void notQuietBeforeTheSteadyTimeHasPassed() {
        AtomicLong nanos = new AtomicLong(5_000_000_000L);
        Quiescence quiescence = new Quiescence(() -> 0, () -> 0L, () -> "unchanged", STEADY, nanos::get);

        assertFalse(quiescence.quiet(), "the first idle samples start the steady wait");
        nanos.addAndGet(999_999);
        assertFalse(quiescence.quiet(), "1 ns before the steady time");
        nanos.addAndGet(1);
        assertTrue(quiescence.quiet(), "at the steady time");
        nanos.addAndGet(1);
        assertTrue(quiescence.quiet(), "and after it, while nothing changes");
    }

    /**
     * A fingerprint that changes between two quiet() calls (each call's own samples equal) starts the steady wait afresh: not
     * quiet a steady time after the first idle samples, quiet a steady time after the change.
     */
    @Test
    void aChangedFingerprintRestartsTheSteadyWait() {
        AtomicLong nanos = new AtomicLong(0);
        AtomicReference<String> world = new AtomicReference<>("before");
        Quiescence quiescence = new Quiescence(() -> 0, () -> 0L, world::get, STEADY, nanos::get);

        assertFalse(quiescence.quiet(), "t = 0: the steady wait starts");
        nanos.set(900_000);
        world.set("after");
        assertFalse(quiescence.quiet(), "t = 0.9 ms: the world moved");
        nanos.set(1_000_000);
        assertFalse(quiescence.quiet(), "t = 1 ms: a steady time after the first samples, 0.1 ms after the change");
        nanos.set(1_899_999);
        assertFalse(quiescence.quiet(), "1 ns before a steady time after the change");
        nanos.set(1_900_000);
        assertTrue(quiescence.quiet(), "a steady time after the change");
    }

    /** A busy sample between idle ones starts the steady wait afresh, though the fingerprint never changed. */
    @Test
    void aBusySampleRestartsTheSteadyWait() {
        AtomicLong nanos = new AtomicLong(0);
        AtomicInteger mounted = new AtomicInteger();
        Quiescence quiescence = new Quiescence(mounted::get, () -> 0L, () -> "unchanged", STEADY, nanos::get);

        assertFalse(quiescence.quiet(), "t = 0: the steady wait starts");
        nanos.set(500_000);
        mounted.set(1);
        assertFalse(quiescence.quiet(), "t = 0.5 ms: a virtual thread mounted");
        nanos.set(600_000);
        mounted.set(0);
        assertFalse(quiescence.quiet(), "t = 0.6 ms: idle again, the wait starts again");
        nanos.set(1_000_000);
        assertFalse(quiescence.quiet(), "t = 1 ms: a steady time after the first samples, 0.4 ms after the busy one");
        nanos.set(1_600_000);
        assertTrue(quiescence.quiet(), "a steady time after the busy sample");
    }

    /**
     * The runner's own steady time ({@code FlowTests.STEADY}) is not zero: the first idle samples of a case's world do not move
     * its clock, the samples a steady time later do (F-17: the hand-over of a monitor-blocked virtual thread ends within it).
     */
    @Test
    void theRunnersSteadyTimeKeepsTheFirstIdleSamplesFromBeingQuiet() {
        AtomicLong nanos = new AtomicLong(0);
        Quiescence quiescence = new Quiescence(() -> 0, () -> 0L, () -> "unchanged", FlowTests.STEADY, nanos::get);

        assertFalse(quiescence.quiet(), "the first idle samples under the runner's steady time");
        nanos.set(FlowTests.STEADY.toNanos());
        assertTrue(quiescence.quiet(), "a steady time later");
    }

    /**
     * Over the JVM, a thread the runner counts as busy (blocked inside a timer call, unmounted or not) is mounted all the same:
     * never quiet while it is busy; the same probe is quiet once it is not.
     */
    @Test
    void aBusyThreadIsNeverQuiet() {
        AtomicInteger busy = new AtomicInteger(1);
        Quiescence quiescence = Quiescence.ofJvm(busy::get, Duration.ZERO, () -> "unchanged");

        List<Integer> quietAt = new ArrayList<>();
        for (int i = 0; i < 50; i++) if (quiescence.quiet()) quietAt.add(i);
        assertEquals(List.of(), quietAt, "never quiet while one thread is busy");

        busy.set(0);
        until(quiescence::quiet, "quiet once nothing is busy (the probe is not always false)");
    }
}
