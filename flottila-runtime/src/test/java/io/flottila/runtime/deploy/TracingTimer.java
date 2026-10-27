package io.flottila.runtime.deploy;

import io.flottila.api.Timer;
import io.flottila.testkit.FakeTimer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link FakeTimer} (driven by its clock, never by wall time) that remembers every task scheduled on it — its delay and
 * whether it fired or was cancelled — so a test can tell which tasks are still live at a given moment, e.g. a worker's poll
 * tick at the moment its consumer is stopped.
 */
public final class TracingTimer implements Timer {

    private final FakeTimer inner;
    private final List<Task> tasks = new CopyOnWriteArrayList<>();

    public TracingTimer(FakeTimer inner) {
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    /** One scheduled task. */
    public static final class Task {
        private final Duration delay;
        private volatile boolean fired;
        private volatile boolean cancelled;

        Task(Duration delay) { this.delay = delay; }

        public Duration delay() { return delay; }

        /** Neither fired nor cancelled. */
        public boolean live() { return !fired && !cancelled; }

        @Override
        public String toString() { return "task after " + delay + (fired ? " (fired)" : cancelled ? " (cancelled)" : " (live)"); }
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        Task t = new Task(delay);
        tasks.add(t);
        Cancellable c = inner.schedule(delay, () -> {
            t.fired = true;
            task.run();
        });
        return () -> {
            boolean was = c.cancel();
            if (was) t.cancelled = true;
            return was;
        };
    }

    /** The tasks that are live right now. */
    public List<Task> live() {
        return tasks.stream().filter(Task::live).toList();
    }

    /** Every task scheduled so far, in order. */
    public List<Task> all() {
        return List.copyOf(tasks);
    }
}
