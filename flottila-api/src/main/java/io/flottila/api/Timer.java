package io.flottila.api;

import java.time.Duration;

/**
 * One-shot scheduling, injected through {@link Runtime} like the clock and the id generator (no
 * static singletons, no timer threads owned by core). The runtime backs it with Vert.x timers;
 * the testkit's fake fires when the fake clock reaches the due time.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface Timer {

    /** Runs {@code task} once, {@code delay} after now; returns a handle to cancel it. */
    Cancellable schedule(Duration delay, Runnable task);

    /**
     * Handle of a scheduled task.
     *
     * @since 0.1
     */
    @FunctionalInterface
    interface Cancellable {
        /** Cancels the task; {@code true} if it was still pending, {@code false} if it had fired or was cancelled. */
        boolean cancel();
    }
}
