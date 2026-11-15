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
 * tick at the moment its consumer is stopped. With {@link #trace} every schedule and every cancel that took effect also goes to
 * a sink as {@code "schedule <delay>"} / {@code "cancel <delay>"} (P1-09d2b: a worker's start schedules its poll tick, its stop
 * cancels it). P1-09d2c: each task also names the class whose lambda it is ({@link Task#owner()}, e.g. {@code Delivery} for a
 * delivery's stop deadline), so a test tells a delivery's deadline from a timer the deploy arms with the same delay.
 */
public final class TracingTimer implements Timer {

    private final FakeTimer inner;
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private volatile java.util.function.Consumer<String> trace = e -> {};

    public TracingTimer(FakeTimer inner) {
        this.inner = Objects.requireNonNull(inner, "inner");
    }

    /** One scheduled task. */
    public static final class Task {
        private final Duration delay;
        private final String owner;
        private volatile boolean fired;
        private volatile boolean cancelled;

        Task(Duration delay, String owner) {
            this.delay = delay;
            this.owner = owner;
        }

        public Duration delay() { return delay; }

        /** The simple name of the class that made the task: a lambda's or method reference's host class ({@code Delivery}, {@code FlowWorker}). */
        public String owner() { return owner; }

        /** Neither fired nor cancelled. */
        public boolean live() { return !fired && !cancelled; }

        @Override
        public String toString() { return owner + " task after " + delay + (fired ? " (fired)" : cancelled ? " (cancelled)" : " (live)"); }
    }

    /** From now on every schedule and every cancel that took effect goes to {@code sink} too, on the calling thread. */
    public TracingTimer trace(java.util.function.Consumer<String> sink) {
        this.trace = Objects.requireNonNull(sink, "sink");
        return this;
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        Task t = new Task(delay, owner(task));
        Cancellable c = inner.schedule(delay, () -> {
            t.fired = true;
            task.run();
        });
        // listed and traced only once the inner timer holds it: a test that sees the task and advances the clock cannot lose it
        tasks.add(t);
        trace.accept("schedule " + delay);
        return () -> {
            boolean was = c.cancel();
            if (was) {
                t.cancelled = true;
                trace.accept("cancel " + delay);
            }
            return was;
        };
    }

    /** {@code io.flottila.core.flow.Delivery$$Lambda/0x...} → {@code Delivery}. */
    static String owner(Runnable task) {
        String name = task.getClass().getName();
        int lambda = name.indexOf("$$Lambda");
        if (lambda >= 0) name = name.substring(0, lambda);
        return name.substring(name.lastIndexOf('.') + 1);
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
