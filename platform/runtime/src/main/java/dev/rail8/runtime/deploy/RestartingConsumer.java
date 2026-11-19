package dev.rail8.runtime.deploy;

import dev.rail8.api.Consumer;
import dev.rail8.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The consumer of one flow's in endpoint as the node runs it: an inner consumer the component built, started again after a
 * delay whenever it does not start or breaks ({@link Consumer#broken()}), until the flow stops. No operator is needed.
 *
 * <p>{@link #start()} is one attempt with the consumer given: it completes as that consumer's start did, bounded by
 * {@code bound} on the timer (past it the attempt fails with a {@link TimeoutException}), and when it fails nothing is
 * tried again, so a deploy can refuse it. {@link #retry} keeps trying after a failed start. Once a start succeeded, a break
 * of that consumer is always tried again. The first attempt calls the start of the consumer given on the caller's thread;
 * each attempt after it builds a fresh consumer from {@code fresh} (a throw, an Error included, or null fails that attempt)
 * and calls its start on a virtual thread of its own. What follows a start, once it completed or the bound came first, runs on
 * a virtual thread too.
 *
 * <p>The delay before attempt n + 1 after n failures in a row is {@link #FIRST} doubled n − 1 times, at most {@link #CAP}:
 * 1, 2, 4, 8, 16, 30, 30 … seconds, counted from the failure. A consumer that ran {@link #STABLE} or longer before it broke
 * starts the count again: its next delay is {@link #FIRST}. There is no limit on the attempts.
 *
 * <p>An inner consumer whose start failed or which broke is stopped at once; one whose start had not completed at the bound
 * is stopped once that start completes. The next attempt builds its consumer only once that stop settled, so two consumers
 * of one endpoint never run at once: for a polling consumer the wait has no bound, for any other it ends after {@code bound}
 * (a WARNING; a second listener on one port fails its start and is tried again). A break is read only from the inner that
 * runs: one that completes before its start succeeded counts right after it, and one of an inner that no longer runs is
 * ignored.
 *
 * <p>{@link #stop()} cancels the next attempt, waits for the attempt in flight and stops the inner that runs; it completes as
 * that stop did, once every inner stopped here before has stopped too (except one whose start never completed). No attempt
 * follows it.
 *
 * <p>Logs: a WARNING, with the cause's stack trace, on the first failure since the consumer last ran; DEBUG on each failure
 * after it; INFO when it runs again.
 */
final class RestartingConsumer implements Consumer {

    private static final System.Logger LOG = System.getLogger(RestartingConsumer.class.getName());

    /** The delay after the first failure. */
    static final Duration FIRST = Duration.ofSeconds(1);
    /** The longest delay. */
    static final Duration CAP = Duration.ofSeconds(30);
    /** A run at least this long before a break starts the count of failures again. */
    static final Duration STABLE = Duration.ofSeconds(30);

    /**
     * A consumer waiting for its next attempt.
     *
     * @param failures the failures in a row so far, this one included
     * @param next when the next attempt is due
     * @param cause the last failure: the start's, or the break's
     */
    record Waiting(int failures, Instant next, Throwable cause) {}

    private final String flow;
    private final Supplier<Consumer> fresh;
    private final boolean polls;
    private final Duration bound;
    private final Timer timer;
    private final Clock clock;
    private final Promise<Void> stopRequested = Promise.promise();

    // guarded by this
    private Consumer first;
    private boolean started;
    private boolean stopping;
    /** A failed attempt schedules the next one: true once retry() was called or a start succeeded. */
    private boolean retrying;
    /** The inner consumer that runs: its start succeeded and nothing stopped it yet. */
    private Consumer current;
    private Instant ranSince;
    private int failures;
    /** A WARNING was logged and the consumer has not run since: further failures are DEBUG. */
    private boolean warned;
    private Waiting waiting;
    private Timer.Cancellable next = () -> false;
    /** Completes once the attempt in flight ended; complete when none runs. */
    private Future<Void> attempt = Future.succeededFuture();
    /** Completes once the last inner given up here has stopped, also after a late start: what the next attempt waits for. */
    private Future<Void> gone = Future.succeededFuture();
    /** The stops called here, settled, for stop() to wait for. */
    private Future<Void> stopped = Future.succeededFuture();
    /** What stop() returns, once it was called. */
    private Future<Void> stop;

    /**
     * @param flow the flow's qualified id, for the logs
     * @param first the consumer the first attempt starts
     * @param fresh builds the consumer of every later attempt
     * @param polls whether the consumer polls under a lease: the next attempt then waits for the previous stop without a bound
     * @param bound how long an attempt's start may take, and how long the next attempt waits for the previous stop otherwise
     */
    RestartingConsumer(String flow, Consumer first, Supplier<Consumer> fresh, boolean polls, Duration bound, Timer timer, Clock clock) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.first = Objects.requireNonNull(first, "first");
        this.fresh = Objects.requireNonNull(fresh, "fresh");
        this.polls = polls;
        this.bound = Objects.requireNonNull(bound, "bound");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The delay before the next attempt after {@code failures} failures in a row (at least 1). */
    static Duration delay(int failures) {
        Duration d = FIRST;
        for (int i = 1; i < failures && d.compareTo(CAP) < 0; i++) d = d.multipliedBy(2);
        return d.compareTo(CAP) < 0 ? d : CAP;
    }

    /** One attempt with the consumer given; fails as its start did, and then schedules nothing. Called once. */
    @Override
    public Future<Void> start() {
        Promise<Void> ended = Promise.promise();
        synchronized (this) {
            if (started) throw new IllegalStateException("flow '" + flow + "': its consumer was started already");
            started = true;
            if (stopping) return Future.failedFuture(new IllegalStateException("flow '" + flow + "': its consumer was stopped before its start"));
            attempt = ended.future();
        }
        Promise<Void> outcome = Promise.promise();
        attempt(ended, outcome);
        return outcome.future();
    }

    /**
     * Keeps trying after a failed {@link #start()}: the next attempt comes after the first delay, and every failure after it
     * schedules the next. Does nothing once {@link #stop()} was called.
     *
     * @param cause the start's failure
     */
    void retry(Throwable cause) {
        synchronized (this) {
            if (stopping) return;
            if (retrying || current != null) throw new IllegalStateException("flow '" + flow + "': its consumer runs or is tried again already");
            retrying = true;
        }
        failed(cause, "did not start");
    }

    /** Present while the consumer waits for its next attempt: from a failure until a start succeeds, never after a stop. */
    synchronized Optional<Waiting> waiting() {
        return Optional.ofNullable(waiting);
    }

    @Override
    public Future<Void> stop() {
        Promise<Void> done = Promise.promise();
        Future<Void> inFlight;
        synchronized (this) {
            if (stop != null) return stop;
            stop = done.future();
            stopping = true;
            waiting = null;
            next.cancel();
            inFlight = attempt;
        }
        stopRequested.tryComplete();
        inFlight.transform(ended -> {
            Consumer running;
            Future<Void> before;
            synchronized (this) {
                running = current;
                current = null;
                before = stopped;
            }
            Future<Void> last = running == null ? Future.succeededFuture() : call(running::stop);
            return Future.join(last, before).transform(both -> last);
        }).onComplete(done);
        return done.future();
    }

    /** The timer's task: the next attempt on a virtual thread of its own, never on the timer's thread. */
    private void fire() {
        Promise<Void> ended = Promise.promise();
        synchronized (this) {
            if (stopping) return;   // fired as stop() cancelled it
            attempt = ended.future();
        }
        Thread.ofVirtual().name("rail8-consumer-" + flow).start(() -> {
            awaitGone();
            attempt(ended, Promise.promise());
        });
    }

    /** Waits until the inner given up last has stopped, or the stop: for a polling consumer without a bound, else at most {@code bound}. */
    private void awaitGone() {
        Future<Void> before;
        synchronized (this) {
            before = gone;
        }
        if (before.isComplete()) return;
        Promise<Void> late = Promise.promise();
        Timer.Cancellable limit = timer.schedule(bound, () -> late.tryComplete());
        Future.any(before, stopRequested.future(), late.future()).otherwiseEmpty().await();
        limit.cancel();
        if (before.isComplete() || stopRequested.future().isComplete()) return;
        if (!polls) {
            LOG.log(System.Logger.Level.WARNING, "flow '" + flow + "': its consumer before has not stopped within " + bound + "; the next one starts all the same");
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "flow '" + flow + "': its consumer before has not stopped within " + bound + "; the next one starts once it did");
        Future.any(before, stopRequested.future()).otherwiseEmpty().await();
    }

    /**
     * One attempt: builds its consumer (after the first) and calls its start on this thread, bounded; once that start completed,
     * or the bound came first, a virtual thread either runs the consumer or gives it up. {@code outcome} completes as the
     * attempt did, {@code ended} once it settled.
     */
    private void attempt(Promise<Void> ended, Promise<Void> outcome) {
        Consumer c;
        synchronized (this) {
            c = first;
            first = null;
            if (stopping) {
                outcome.fail(new IllegalStateException("flow '" + flow + "': its consumer was stopped before it started"));
                ended.complete();
                return;
            }
        }
        if (c == null) {
            try {
                c = Objects.requireNonNull(fresh.get(), "the component built no consumer");
            } catch (Throwable refused) {   // an Error of a plugin's build too: the attempt fails and the next one comes
                outcome.fail(refused);
                failed(refused, "could not be built");
                ended.complete();
                return;
            }
        }
        Consumer inner = c;
        Promise<Void> bounded = Promise.promise();
        Timer.Cancellable limit = timer.schedule(bound, () -> bounded.tryFail(new TimeoutException("its start did not complete within " + bound)));
        Future<Void> call = call(inner::start);
        call.onComplete(r -> {
            if (r.succeeded()) bounded.tryComplete();
            else bounded.tryFail(r.cause());
        });
        bounded.future().onComplete(r -> Thread.ofVirtual().name("rail8-consumer-" + flow).start(() -> {
            try {
                limit.cancel();
                if (r.succeeded()) {
                    runs(inner);
                    outcome.complete();
                    return;
                }
                Promise<Void> down;
                synchronized (this) {
                    down = givingUp(call);
                }
                giveUp(inner, call, down);
                boolean again = retrying();   // read before the failure is told: a retry() it leads to schedules the next attempt itself
                outcome.fail(r.cause());
                if (again) failed(r.cause(), "did not start");
            } finally {
                outcome.tryFail(new IllegalStateException("flow '" + flow + "': its consumer's attempt broke off"));
                ended.complete();
            }
        }));
    }

    /** The start of {@code inner} succeeded: it runs, and its break is read from now on; once stop() began it is stopped instead. */
    private void runs(Consumer inner) {
        int after;
        synchronized (this) {
            if (stopping) {
                stopped = Future.join(stopped, settled(call(inner::stop), "its consumer, started as the flow stopped, did not stop")).mapEmpty();
                return;
            }
            current = inner;
            ranSince = clock.instant();
            retrying = true;
            after = waiting == null ? 0 : failures;
            waiting = null;
            warned = false;
        }
        if (after > 0) LOG.log(System.Logger.Level.INFO, "flow '" + flow + "': its consumer runs again after " + after + " failures in a row");
        // a break that completed before this listener exists is read at once: right after the start; a broken() that throws
        // or answers null is a break too
        Future.<Void>future(listened -> inner.broken().onComplete(listened)).onComplete(done -> Thread.ofVirtual().name("rail8-consumer-" + flow)
                .start(() -> broke(inner, done.failed() ? done.cause() : new IllegalStateException("its consumer reported a break without a cause"))));
    }

    /** {@code inner} broke: when it is the one that runs, it is stopped and the next attempt scheduled. */
    private void broke(Consumer inner, Throwable cause) {
        Promise<Void> down;
        synchronized (this) {
            if (stopping || current != inner) return;   // stopped, or no longer the one that runs
            current = null;
            if (Duration.between(ranSince, clock.instant()).compareTo(STABLE) >= 0) failures = 0;
            down = givingUp(Future.succeededFuture());   // with current cleared: a stop() from now on waits for it
        }
        giveUp(inner, Future.succeededFuture(), down);
        failed(cause, "stopped working");
    }

    /**
     * Records the stop of an inner about to be given up, whose start {@code call} has run or still runs: the next attempt waits
     * for {@code down}, and so does stop() once that call completed. Called under the lock; completed by {@link #giveUp}.
     */
    private Promise<Void> givingUp(Future<Void> call) {
        Promise<Void> down = Promise.promise();
        gone = down.future();
        if (call.isComplete()) stopped = Future.join(stopped, down.future()).mapEmpty();
        return down;
    }

    /** Stops {@code inner}, once its start {@code call} completed, and completes {@code down} when that stop settled. */
    private void giveUp(Consumer inner, Future<Void> call, Promise<Void> down) {
        String what = call.isComplete() ? "its consumer, given up, did not stop cleanly" : "its consumer, stopped once its late start completed, did not stop cleanly";
        call.transform(started -> settled(call(inner::stop), what)).onComplete(down);
    }

    private synchronized boolean retrying() {
        return retrying;
    }

    /** Counts a failure and schedules the next attempt, unless stop() began. */
    private void failed(Throwable cause, String what) {
        Duration delay;
        int n;
        boolean warn;
        synchronized (this) {
            if (stopping) return;
            n = ++failures;
            delay = delay(n);
            waiting = new Waiting(n, clock.instant().plus(delay), cause);
            warn = !warned;
            warned = true;
            next = timer.schedule(delay, this::fire);
        }
        String text = "flow '" + flow + "': its consumer " + what + " (" + cause + "); it starts again in " + delay + " (failure " + n + " in a row)";
        if (warn) LOG.log(System.Logger.Level.WARNING, text, cause);
        else LOG.log(System.Logger.Level.DEBUG, text);
    }

    /** {@code call}'s future; a throw fails it. */
    private static Future<Void> call(Supplier<Future<Void>> call) {
        return Future.future(done -> call.get().onComplete(done));
    }

    /** {@code call} once settled, never failed: a failure is a WARNING saying {@code what}. */
    private Future<Void> settled(Future<Void> call, String what) {
        return call.onFailure(failed -> LOG.log(System.Logger.Level.WARNING, "flow '" + flow + "': " + what + ": " + failed, failed)).otherwiseEmpty();
    }
}
