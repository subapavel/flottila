package io.flottila.core.record;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * The current thread's CPU time, for the per-step CPU figure on the snapshot (ARCHITECTURE §4 and §8; unknown on a virtual
 * thread, F-21). Injected like the clock, so tests drive it.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface Meter {

    /** CPU nanoseconds consumed by the current thread so far, or {@link Snapshot#CPU_UNKNOWN}. */
    long cpuNanos();

    /**
     * The JVM's reading through {@link ThreadMXBean}; {@link Snapshot#CPU_UNKNOWN} where the platform has none for this thread.
     * On JDK 25 that is every virtual thread, so every step, run on the message's virtual thread, records CPU unknown (F-21).
     */
    static Meter system() {
        return of(ManagementFactory.getThreadMXBean());
    }

    /** The reading of {@code threads}, or {@link Snapshot#CPU_UNKNOWN} when it does not support the current thread or answers a negative value. */
    static Meter of(ThreadMXBean threads) {
        if (!threads.isCurrentThreadCpuTimeSupported()) return () -> Snapshot.CPU_UNKNOWN;
        return () -> {
            long t = threads.getCurrentThreadCpuTime();
            return t < 0 ? Snapshot.CPU_UNKNOWN : t;
        };
    }
}
