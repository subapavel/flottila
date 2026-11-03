package io.flottila.sim;

import io.flottila.api.Timer;
import io.flottila.core.pattern.Retry;
import io.flottila.testkit.FakeTimer;

import java.time.Duration;
import java.util.List;

/**
 * The run's timer over the fake one (D-101). A schedule from a message thread with {@link Retry} on its stack is that run
 * parking on a backoff: the run is PARKED, and when the fake clock reaches the task, the task is deferred to the driver as a
 * WAKE unit instead of run — the fake timer fires every due task in one loop, which would wake several runs at once. Every
 * other schedule (leases, the save timeout, the driver's own) goes to the fake timer as it is and fires under ADVANCE. The
 * coupling to Retry is accepted and pinned by SimSchedulerTest.aRetryBackoffIsAWakeUnit.
 */
final class SimTimer implements Timer {

    /** A deferred backoff: {@code task} wakes {@code run}. */
    record Wake(Runs.Run run, Runnable task) {}

    private static final String RETRY = Retry.class.getName();

    private final FakeTimer fake;
    private final Runs runs;
    private final List<Wake> wakes;   // the driver's: filled while it moves the clock

    SimTimer(FakeTimer fake, Runs runs, List<Wake> wakes) {
        this.fake = fake;
        this.runs = runs;
        this.wakes = wakes;
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        if (runs.onDriver() || !retryOnStack()) return fake.schedule(delay, task);
        Runs.Run run = runs.current();
        Cancellable scheduled = fake.schedule(delay, () -> wakes.add(new Wake(run, task)));
        runs.park(run);
        return scheduled;
    }

    private static boolean retryOnStack() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().equals(RETRY)));
    }
}
