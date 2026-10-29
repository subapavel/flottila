package io.flottila.cli;

import jdk.management.VirtualThreadSchedulerMXBean;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * When {@code flottila test} may move a case's virtual time (D-080): the case's world is quiet when two consecutive samples each
 * show no virtual thread mounted on a carrier and none queued for one, and equal ({@code equals}) fingerprints of what the runner
 * reads of the world. Every message of a case runs on a virtual thread and every timer task on the thread that advances the
 * clock, so a world with nothing mounted or queued can only move when the clock does; the fingerprint and the second sample
 * guard the instant a thread changes hands.
 *
 * <p>One instant does not see every hand-over: a virtual thread blocked on a monitor leaves its carrier (JDK 24 on, JEP 491), and
 * from the monitor's release until the JDK's unblocker thread queues it again it is neither mounted nor queued. A quiescence
 * with a {@code steady} time therefore answers quiet only once its samples have stayed idle, with the same fingerprint, for at
 * least that long: the hand-over ends within it. Zero answers on the two samples alone. Not thread-safe: one thread samples.
 */
final class Quiescence {

    private final IntSupplier mounted;
    private final LongSupplier queued;
    private final Supplier<?> fingerprint;
    private final long steady;
    private final LongSupplier nanoTime;
    private Stretch stretch;   // the idle samples since the last busy or changed one; null after a busy one

    /**
     * @param mounted the virtual threads mounted now
     * @param queued the virtual threads waiting for a carrier now
     * @param fingerprint what the runner reads of the case's world, compared with {@code equals}
     */
    Quiescence(IntSupplier mounted, LongSupplier queued, Supplier<?> fingerprint) {
        this(mounted, queued, fingerprint, Duration.ZERO, System::nanoTime);
    }

    /**
     * @param steady how long the samples stay idle with one fingerprint before {@link #quiet()} answers quiet
     * @param nanoTime the wall clock {@code steady} is measured on, in nanoseconds ({@link System#nanoTime()})
     */
    Quiescence(IntSupplier mounted, LongSupplier queued, Supplier<?> fingerprint, Duration steady, LongSupplier nanoTime) {
        this.mounted = Objects.requireNonNull(mounted, "mounted");
        this.queued = Objects.requireNonNull(queued, "queued");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.steady = Objects.requireNonNull(steady, "steady").toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** Over the JVM's virtual thread scheduler ({@code jdk.management}, JDK 24 on), on the two samples alone. */
    static Quiescence ofJvm(Supplier<?> fingerprint) {
        return ofJvm(() -> 0, Duration.ZERO, fingerprint);
    }

    /**
     * Over the JVM's virtual thread scheduler, {@code busy} threads counted as mounted, quiet after {@code steady}.
     *
     * @param busy the threads inside a call the runner watches: a thread blocked in one is busy, mounted or not
     */
    static Quiescence ofJvm(IntSupplier busy, Duration steady, Supplier<?> fingerprint) {
        VirtualThreadSchedulerMXBean scheduler = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
        return new Quiescence(() -> scheduler.getMountedVirtualThreadCount() + busy.getAsInt(), scheduler::getQueuedVirtualThreadCount,
                fingerprint, steady, System::nanoTime);
    }

    /**
     * Takes two samples now, yielding between them; whether both are idle with equal fingerprints, and the samples have stayed so
     * for the steady time.
     */
    boolean quiet() {
        Sample first = sample();
        Thread.yield();
        Sample second = sample();
        if (!first.idle() || !second.idle() || !Objects.equals(first.fingerprint(), second.fingerprint())) {
            stretch = null;
            return false;
        }
        long now = nanoTime.getAsLong();
        if (stretch == null || !Objects.equals(stretch.fingerprint(), second.fingerprint())) stretch = new Stretch(second.fingerprint(), now);
        return now - stretch.since() >= steady;
    }

    private Sample sample() {
        boolean idle = mounted.getAsInt() == 0 && queued.getAsLong() == 0;
        return new Sample(idle, fingerprint.get());
    }

    private record Sample(boolean idle, Object fingerprint) {}

    /** Idle samples with one fingerprint since {@code since} ({@link #nanoTime}). */
    private record Stretch(Object fingerprint, long since) {}
}
