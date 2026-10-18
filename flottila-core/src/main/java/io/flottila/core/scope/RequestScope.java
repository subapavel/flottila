package io.flottila.core.scope;

import io.flottila.api.Timer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * A thin fork / join / cancel scope over plain virtual threads (ARCHITECTURE §4): one per in-flight
 * message, owned by the message's virtual thread; a timeout or a cancel stops all of its children.
 * To be swapped for {@code StructuredTaskScope} when that leaves preview.
 *
 * <ul>
 *   <li>{@link #fork(Callable)} starts the task on a new virtual thread; after {@link #cancel()} or
 *       {@link #close()} it is refused.</li>
 *   <li>{@link #join()} waits for every child forked so far. An interrupt of the owner cancels the scope
 *       and is rethrown, which is how a parent's cancel unwinds a nested scope.</li>
 *   <li>{@link #cancel()} interrupts the running children; an exception a child throws after that is
 *       reported as {@link Subtask.State#CANCELLED}, not as a failure. Children parked in
 *       {@code Future.await()} wake with an {@code InterruptedException}.</li>
 *   <li>The deadline is enforced through the injected {@link Timer} (never a static scheduler) against
 *       the injected {@link Clock}; it is finite and positive, and it is dropped on {@link #close()}.</li>
 *   <li>{@link #close()} cancels whatever still runs and waits for it — no child outlives its scope.</li>
 * </ul>
 *
 * @since 0.1
 */
public final class RequestScope implements AutoCloseable {

    /**
     * One forked child.
     *
     * @param <T> the child's result type
     * @since 0.1
     */
    public interface Subtask<T> {
        /** @since 0.1 */
        enum State { RUNNING, SUCCESS, FAILED, CANCELLED }

        State state();

        /** The result; {@link IllegalStateException} (with the child's exception as cause, if any) unless {@link State#SUCCESS}. */
        T get();

        /** What the child threw, or null; set for {@link State#FAILED} and usually for {@link State#CANCELLED}. */
        Throwable exception();
    }

    private final Clock clock;
    private final Instant deadline;
    private final Timer.Cancellable deadlineTimer;
    private final List<Child<?>> children = new ArrayList<>();   // guarded by this; JDK 25 virtual threads do not pin on monitors
    private int running;
    private boolean cancelled;
    private boolean closed;

    private RequestScope(Clock clock, Timer timer, Duration deadline) {
        this.clock = clock;
        this.deadline = clock.instant().plus(deadline);
        this.deadlineTimer = timer.schedule(deadline, this::cancel);
    }

    public static RequestScope open(Clock clock, Timer timer, Duration deadline) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) throw new IllegalArgumentException("deadline must be positive, was " + deadline);
        return new RequestScope(clock, timer, deadline);
    }

    public synchronized <T> Subtask<T> fork(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        if (cancelled) throw new IllegalStateException("scope is cancelled");
        if (closed) throw new IllegalStateException("scope is closed");
        Child<T> child = new Child<>(task);
        children.add(child);
        running++;
        // started under the same monitor that cancel() takes: a cancel can never slip between start and interrupt
        child.thread = Thread.ofVirtual().name("flottila-child-", children.size()).start(() -> run(child));
        return child;
    }

    private <T> void run(Child<T> child) {
        try {
            T result = child.task.call();
            child.finish(Subtask.State.SUCCESS, result, null);
        } catch (Throwable t) {
            child.finish(isCancelled() ? Subtask.State.CANCELLED : Subtask.State.FAILED, null, t);
        } finally {
            synchronized (this) {
                running--;
                notifyAll();
            }
        }
    }

    /** Waits for every child forked so far; an interrupt cancels the scope first, then propagates. */
    public synchronized void join() throws InterruptedException {
        while (running > 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                cancel();
                throw e;
            }
        }
    }

    /** Idempotent: interrupts the running children and refuses further forks. */
    public synchronized void cancel() {
        if (cancelled) return;
        cancelled = true;
        for (Child<?> child : children) {
            if (child.state() == Subtask.State.RUNNING) child.thread.interrupt();
        }
    }

    public synchronized boolean isCancelled() { return cancelled; }

    /** Children forked and not yet finished. */
    public synchronized int running() { return running; }

    /** Children forked so far, finished or not. */
    public synchronized int forked() { return children.size(); }

    public Instant deadline() { return deadline; }

    /** Time left until the deadline on the injected clock, never negative. */
    public Duration remaining() {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Cancels whatever still runs, waits for it (keeping the owner's interrupt, if any) and drops the deadline timer. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            if (running > 0) cancel();
            deadlineTimer.cancel();
        }
        boolean interrupted = false;
        while (true) {
            try {
                join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class Child<T> implements Subtask<T> {
        private final Callable<T> task;
        private Thread thread;
        private volatile T result;
        private volatile Throwable exception;
        private volatile State state = State.RUNNING;

        private Child(Callable<T> task) { this.task = task; }

        private void finish(State end, T result, Throwable exception) {
            this.result = result;
            this.exception = exception;
            this.state = end;   // written last: a reader that sees a final state sees its result and exception
        }

        @Override public State state() { return state; }

        @Override
        public T get() {
            State s = state;
            if (s != State.SUCCESS) throw new IllegalStateException("child is " + s, exception);
            return result;
        }

        @Override public Throwable exception() { return exception; }
    }
}
