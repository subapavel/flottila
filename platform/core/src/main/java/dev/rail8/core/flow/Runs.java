package dev.rail8.core.flow;

import dev.rail8.api.Envelope;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.Timer;
import dev.rail8.core.scope.MessageContext;
import io.vertx.core.Future;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * The runs of messages on one node, and what stops them: a run's time limit, or an operator's cancel.
 *
 * <p>{@link #head} is the first stage of every pipeline the flow builder builds. It binds the message's {@link MessageContext},
 * whose deadline is the run's start plus the flow's time limit, registers the run, and arms a timer at that deadline. A stop —
 * the timer, or {@link #stop} for the message — interrupts the run's thread once, and only while that thread is inside the
 * pipeline; the head clears that interrupt before the thread leaves it, so nothing after the pipeline sees it. From the stop
 * on, {@link #guard} refuses every step that has not yet started, a send does not start, and {@code Retry} neither waits nor
 * tries again. A step that throws while its run is stopped throws a {@link RunStoppedException} instead, carrying the
 * stop's {@link Why} and the step's own failure; a step that returns keeps its result. The head of a stopped run returns only
 * once every send the run started has settled, so nothing the run sent is still out when its caller goes on, unless another
 * interrupts that wait (a delivery's stop deadline); a run that was not stopped waited for its sends itself, except one the
 * node's fence made it give up, which it leaves as it is.
 *
 * <p>A run still going {@code patience} after its time limit — a step that does not answer the interrupt — is logged once as
 * a WARNING naming the message, the last step it entered and its thread. Nothing ends it: it ends when that step returns.
 *
 * @since 0.1
 */
public final class Runs {

    private static final System.Logger LOG = System.getLogger(Runs.class.getName());
    private static final ScopedValue<Run> CURRENT = ScopedValue.newInstance();
    private static final ScopedValue<Why> FROM_START = ScopedValue.newInstance();

    /**
     * Why a run stopped. Its {@link #text()} is what the history row at the step where the run stopped says.
     *
     * @since 0.1
     */
    public sealed interface Why permits TimeLimit, Cancelled {

        /** The stop as the history and the log say it. */
        String text();
    }

    /**
     * The run's time limit passed.
     *
     * @param limit the flow's {@code delivery.timeout}
     * @since 0.1
     */
    public record TimeLimit(Duration limit) implements Why {

        public TimeLimit {
            Objects.requireNonNull(limit, "limit");
        }

        @Override public String text() {
            return "its time limit of " + limit + " passed";
        }
    }

    /**
     * An operator cancelled the message.
     *
     * @param actor who cancelled it, by name
     * @param reason the reason the operator gave, when one was given
     * @since 0.1
     */
    public record Cancelled(String actor, Optional<String> reason) implements Why {

        public Cancelled {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(reason, "reason");
        }

        @Override public String text() {
            return "cancelled by " + actor + reason.map(r -> ": " + r).orElse("");
        }
    }

    private final Clock clock;
    private final Timer timer;
    private final Duration patience;
    private final Map<String, Set<Run>> running = new HashMap<>();   // guarded by itself: message id to its runs on this node

    /**
     * @param clock the clock deadlines are read on
     * @param timer the timer that fires each run's time limit
     * @param patience how long after its time limit a run still going is logged; the worker's lease
     */
    public Runs(Clock clock, Timer timer, Duration patience) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.patience = Objects.requireNonNull(patience, "patience");
        if (!patience.isPositive()) throw new IllegalArgumentException("patience must be positive, was " + patience);
    }

    /**
     * The first stage of a pipeline whose runs have {@code limit}: it binds the message's context with a deadline of the run's
     * start plus {@code limit}, keeps the run stoppable until it returns, and, once stopped, returns only after the run's sends
     * have settled.
     */
    public Stage head(Duration limit) {
        Objects.requireNonNull(limit, "limit");
        if (!limit.isPositive()) throw new IllegalArgumentException("a run's time limit must be positive, was " + limit);
        return (e, rest) -> {
            Run run = new Run(e, limit, Thread.currentThread());
            Instant deadline = clock.instant().plus(limit);
            register(run);
            try {
                if (FROM_START.isBound()) run.stop(FROM_START.get());   // not inside yet: nothing to interrupt
                run.arm();
                run.enter();
                return ScopedValue.where(CURRENT, run).call(() -> MessageContext.of(e, deadline).apply(rest, e));
            } finally {
                run.leave();
                run.awaitSends();
                deregister(run);
            }
        };
    }

    /**
     * Stops every run of {@code messageId} on this node with {@code why}; a run already stopped keeps its first stop. True when
     * one was running.
     */
    public boolean stop(String messageId, Why why) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(why, "why");
        List<Run> runs;
        synchronized (running) {
            runs = List.copyOf(running.getOrDefault(messageId, Set.of()));
        }
        runs.forEach(r -> r.stop(why));
        return !runs.isEmpty();
    }

    /** {@code run}, in which the pipeline's head starts the run already stopped by {@code why}: its first step records why and does nothing. */
    public static <T> T stoppedFromStart(Why why, Supplier<T> run) {
        Objects.requireNonNull(why, "why");
        Objects.requireNonNull(run, "run");
        return ScopedValue.where(FROM_START, why).call(run::get);
    }

    /** The stop of the run bound on this thread; empty when it is not stopped, or no run is bound. */
    public static Optional<Why> why() {
        return CURRENT.isBound() ? Optional.ofNullable(CURRENT.get().why()) : Optional.empty();
    }

    /** The run bound on this thread, or null. */
    static Run current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /**
     * {@code stage} as step {@code step} of a stoppable run: refused with a {@link RunStoppedException} when its run is stopped
     * before it starts; a failure while its run is stopped becomes one, with the failure as its cause, unless it is one already or
     * an {@link Error}. What it returns is kept. Without a bound run it is {@code stage}.
     */
    static Stage guard(Stage stage, StepConfig step) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(step, "step");
        return (e, rest) -> {
            Run run = current();
            if (run == null) return stage.apply(e, rest);
            Why before = run.at(step.name());
            if (before != null) throw new RunStoppedException(before, null);
            try {
                return stage.apply(e, rest);
            } catch (RunStoppedException | Error passing) {
                throw passing;
            } catch (Throwable failure) {   // a checked one too: an interrupted await throws InterruptedException undeclared
                Why why = run.why();
                if (why == null) throw failure;
                run.calm();
                throw new RunStoppedException(why, failure);
            }
        };
    }

    private void register(Run run) {
        synchronized (running) {
            running.computeIfAbsent(run.messageId, id -> Collections.newSetFromMap(new IdentityHashMap<>())).add(run);
        }
    }

    private void deregister(Run run) {
        run.done();
        synchronized (running) {
            Set<Run> runs = running.get(run.messageId);
            runs.remove(run);
            if (runs.isEmpty()) running.remove(run.messageId);
        }
    }

    /** One run of a message: its thread, its stop, and the sends it started that have not settled. */
    final class Run {
        private final String messageId;
        private final String flow;
        private final Duration limit;
        private final Thread thread;
        private final Set<Future<?>> open = Collections.newSetFromMap(new IdentityHashMap<>());   // guarded by this
        private Why why;                      // guarded by this: null until stopped
        private boolean inside;               // guarded by this: the thread is in the pipeline, so a stop may interrupt it
        private boolean interrupted;          // guarded by this: the stop's interrupt may still be set on the thread
        private String step;                  // guarded by this: the last step entered
        private boolean done;                 // guarded by this: the run returned
        private Timer.Cancellable expiry;     // guarded by this: the time limit
        private Timer.Cancellable stuck;      // guarded by this: the WARNING after the time limit

        private Run(Envelope e, Duration limit, Thread thread) {
            this.messageId = e.id();
            this.flow = e.flow();
            this.limit = limit;
            this.thread = thread;
        }

        private synchronized void arm() {
            expiry = timer.schedule(limit, this::timeUp);
        }

        private synchronized void enter() {
            inside = true;
        }

        private void stop(Why stop) {
            synchronized (this) {
                if (why != null || done) return;
                why = stop;
                if (inside) {
                    interrupted = true;
                    thread.interrupt();
                }
            }
            LOG.log(System.Logger.Level.INFO, "message " + messageId + " of flow " + flow + " stops: " + stop.text());
        }

        /** The time limit passed: the run stops, and is watched for a step that does not answer the interrupt. */
        private void timeUp() {
            stop(new TimeLimit(limit));
            synchronized (this) {
                if (!done) stuck = timer.schedule(patience, this::warn);
            }
        }

        private void warn() {
            String at;
            synchronized (this) {
                if (done) return;
                at = step;
            }
            LOG.log(System.Logger.Level.WARNING, "message " + messageId + " of flow " + flow + " still runs " + patience + " after its time limit of " + limit
                    + " on thread " + thread.getName() + (at == null ? "" : ", last in step '" + at + "'")
                    + ": a step there does not answer the interrupt; the run, and its work item, end only when it returns");
        }

        synchronized Why why() {
            return why;
        }

        /** The run enters step {@code name}; its stop so far, null when none. */
        private synchronized Why at(String name) {
            step = name;
            return why;
        }

        /** The stop's interrupt, when still pending on the run's thread, is cleared: called on that thread. */
        private synchronized void calm() {
            if (interrupted) {
                interrupted = false;
                Thread.interrupted();
            }
        }

        /** The thread leaves the pipeline: no stop interrupts it from here on, and the stop's own interrupt is cleared. */
        private synchronized void leave() {
            inside = false;
            calm();
            if (expiry != null) expiry.cancel();
        }

        /** {@code send}, started by this run: the run returns only once it has settled. */
        void watch(Future<?> send) {
            synchronized (this) {
                open.add(send);
            }
            send.onComplete(settled -> {
                synchronized (this) {
                    open.remove(send);
                }
            });
        }

        /**
         * A stopped run waits until every send it started has settled. An interrupt while it waits is never the stop's own,
         * which {@link #leave} cleared, but another's — a delivery's stop deadline: the run stops waiting, leaves its sends as
         * they are, and keeps that interrupt for the other.
         */
        private void awaitSends() {
            List<Future<?>> left;
            synchronized (this) {
                if (why == null) return;
                left = List.copyOf(open);
            }
            CompletableFuture<?>[] settled = left.stream().map(send -> send.toCompletionStage().handle((result, failure) -> null).toCompletableFuture())
                    .toArray(CompletableFuture[]::new);
            try {
                CompletableFuture.allOf(settled).get();
            } catch (InterruptedException another) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException impossible) {   // each one is handled: it never completes exceptionally
                throw new IllegalStateException(impossible);
            }
        }

        private synchronized void done() {
            done = true;
            if (stuck != null) stuck.cancel();
        }
    }
}
