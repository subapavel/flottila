package dev.rail8.cli;

import jdk.management.VirtualThreadSchedulerMXBean;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * When {@code rail8 test} may move a case's virtual time: the case's world is quiet when two consecutive samples each
 * show no virtual thread mounted on a carrier and none queued for one, and equal ({@code equals}) fingerprints of what the runner
 * reads of the world. Every message of a case runs on a virtual thread and every timer task on the thread that advances the
 * clock, so a world with nothing mounted or queued can only move when the clock does; the fingerprint and the second sample
 * guard the instant a thread changes hands.
 *
 * <p>A sample of {@link #ofJvm} counts as mounted the scheduler's mounted count, the carrier threads running now
 * ({@link #runningCarriers()}) and the runner's busy threads. The scheduler's count alone is not enough: it is
 * its pool's active count, and the pool leaves a carrier out of it while the carrier's virtual thread, mounted and running, is
 * inside a blocking operation the pool compensates for — between {@code jdk.internal.misc.Blocker.begin()} and {@code end()},
 * as a write of {@code System.out} or {@code System.err} that reaches the file descriptor and every read of a {@code Process}
 * pipe is. The scheduler's counts stay in the sample: a virtual thread queued for a carrier that has not woken yet is seen only
 * by them.
 *
 * <p>One instant does not see every hand-over: a virtual thread blocked on a monitor leaves its carrier (JDK 24 on, JEP 491), and
 * from the monitor's release until the JDK's unblocker thread queues it again it is neither mounted nor queued. A quiescence
 * with a {@code steady} time therefore answers quiet only once its samples have stayed idle, with the same fingerprint, for at
 * least that long: the hand-over ends within it. Zero answers on the two samples alone. Not thread-safe: one thread samples.
 */
final class Quiescence {

    private static final String CARRIER = "jdk.internal.misc.CarrierThread";

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

    /**
     * Over the JVM's virtual thread scheduler ({@code jdk.management}, JDK 24 on) and its running carriers, on the two samples
     * alone: a sample reads {@code VirtualThreadSchedulerMXBean}'s mounted and queued counts and {@link #runningCarriers()}.
     */
    static Quiescence ofJvm(Supplier<?> fingerprint) {
        return ofJvm(() -> 0, Duration.ZERO, fingerprint);
    }

    /**
     * Over the JVM's virtual thread scheduler, quiet after {@code steady}: a sample's mounted reading is the scheduler's mounted
     * count plus {@link #runningCarriers()} plus {@code busy}; its queued reading is the scheduler's queued count.
     *
     * @param busy the threads inside a call the runner watches: a thread blocked in one is busy, mounted or not
     */
    static Quiescence ofJvm(IntSupplier busy, Duration steady, Supplier<?> fingerprint) {
        VirtualThreadSchedulerMXBean scheduler = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
        return new Quiescence(() -> scheduler.getMountedVirtualThreadCount() + runningCarriers() + busy.getAsInt(),
                scheduler::getQueuedVirtualThreadCount, fingerprint, steady, System::nanoTime);
    }

    /**
     * The carrier threads running now: the live platform threads of the root thread group and its subgroups whose class is
     * {@code jdk.internal.misc.CarrierThread} (compared by name; the package is not exported) and whose
     * {@link Thread#getState()} is {@code RUNNABLE}. A carrier is {@code RUNNABLE} while it runs a virtual thread, inside a
     * compensated block too, and {@code WAITING} or {@code TIMED_WAITING} while it is parked for work. The threads are read anew
     * on every call: the pool starts spare carriers for compensated blocks and retires idle ones. Called on a virtual thread, the
     * count includes that thread's own carrier.
     */
    static int runningCarriers() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        for (ThreadGroup parent; (parent = root.getParent()) != null; ) root = parent;
        Thread[] threads = new Thread[root.activeCount() + 16];
        int live;
        while ((live = root.enumerate(threads, true)) == threads.length) threads = new Thread[threads.length * 2];   // full: may have missed one
        int running = 0;
        for (int i = 0; i < live; i++) {
            Thread thread = threads[i];
            if (thread.getClass().getName().equals(CARRIER) && thread.getState() == Thread.State.RUNNABLE) running++;
        }
        return running;
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
