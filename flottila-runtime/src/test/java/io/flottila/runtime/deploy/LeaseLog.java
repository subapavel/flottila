package io.flottila.runtime.deploy;

import io.flottila.runtime.lease.PollerLeases;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-02L: {@link PollerLeases} at the store boundary, recording every call — the key, the owner, the ttl, the clock's instant
 * and the calling thread — and answering each hold from its script ({@link #next}), else from its delegate (a real store: two
 * nodes share one), else as {@link #otherwise}. Every call and every answer is also one line of the case's log, in the order
 * made: {@code "hold <owner>"} at the call, {@code "held <owner> true|false"} or {@code "hold failed <owner>"} when its answer
 * completes (logged before whoever awaits that answer sees it), {@code "release <key> <owner>"} at a release call.
 *
 * <p>A {@link Answer#HANG} answer never completes on its own: the case may complete it ({@link Hold#answer()}), or the hold's
 * bound fails the wait. {@link #quiet} is the witness of a negative ("nothing started", "nothing released"): the thread that
 * would act has ended or is parked.
 */
final class LeaseLog implements PollerLeases {

    /** How a scripted hold is answered. */
    enum Answer { TRUE, FALSE, FAIL, HANG }

    /** The failure a {@link Answer#FAIL} answer carries. */
    static final String DOWN = "the lease store is down";

    /** How long a positive wait of these cases may take. */
    static final Duration BOUND = Duration.ofSeconds(10);

    /** One hold call, numbered from 1 in call order; {@code at} is the clock when it was called (the tick's t0). */
    record Hold(int n, String key, String owner, Duration ttl, Instant at, Thread thread, Promise<Boolean> answer) {
        /** The answer, null while none came, false when it failed. */
        Boolean result() {
            Future<Boolean> f = answer.future();
            return !f.isComplete() ? null : f.succeeded() && Boolean.TRUE.equals(f.result());
        }
    }

    /** One release call, in call order. */
    record Release(String key, String owner, Instant at, Thread thread) {}

    private final Clock clock;
    private final PollerLeases delegate;
    private final java.util.function.Consumer<String> log;
    private final Deque<Answer> script = new ConcurrentLinkedDeque<>();
    private final List<Hold> holds = new CopyOnWriteArrayList<>();
    private final List<Release> releases = new CopyOnWriteArrayList<>();
    private volatile Answer otherwise = Answer.TRUE;
    private volatile String releaseFailure;

    /** @param delegate the store that answers what the script does not; null: {@link #otherwise} answers */
    LeaseLog(Clock clock, PollerLeases delegate, java.util.function.Consumer<String> log) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delegate = delegate;
        this.log = Objects.requireNonNull(log, "log");
    }

    /** The next holds are answered so, in order, whoever calls. */
    LeaseLog next(Answer... answers) {
        script.addAll(Arrays.asList(answers));
        return this;
    }

    /** Holds the script does not answer are answered so (when there is no delegate). */
    LeaseLog otherwise(Answer answer) {
        this.otherwise = Objects.requireNonNull(answer, "answer");
        return this;
    }

    /** Every release from now on fails with {@code message} (and does not reach the delegate). */
    void failReleases(String message) {
        this.releaseFailure = message;
    }

    @Override
    public Future<Boolean> hold(String key, String owner, Duration ttl) {
        Promise<Boolean> answer = Promise.promise();
        Hold h;
        synchronized (holds) {
            h = new Hold(holds.size() + 1, key, owner, ttl, clock.instant(), Thread.currentThread(), answer);
            holds.add(h);
            log.accept("hold " + owner);
        }
        // registered before the caller gets the future: the answer is logged before the caller sees it
        answer.future().onComplete(done -> log.accept(done.succeeded() ? "held " + owner + " " + done.result() : "hold failed " + owner));
        Answer scripted = script.poll();
        if (scripted == null && delegate != null) {
            delegate.hold(key, owner, ttl).onComplete(answer);
        } else {
            switch (scripted == null ? otherwise : scripted) {
                case TRUE -> answer.complete(true);
                case FALSE -> answer.complete(false);
                case FAIL -> answer.fail(new IllegalStateException(DOWN));
                case HANG -> { }
            }
        }
        return answer.future();
    }

    @Override
    public Future<Void> release(String key, String owner) {
        releases.add(new Release(key, owner, clock.instant(), Thread.currentThread()));
        log.accept("release " + key + " " + owner);
        String failure = releaseFailure;
        if (failure != null) return Future.failedFuture(new IllegalStateException(failure));
        return delegate == null ? Future.succeededFuture() : delegate.release(key, owner);
    }

    List<Hold> holds() { return List.copyOf(holds); }

    List<Hold> holdsBy(String owner) { return holds.stream().filter(h -> h.owner().equals(owner)).toList(); }

    List<Release> releases() { return List.copyOf(releases); }

    /** Waits (at most {@link #BOUND}) until the {@code n}th hold (from 1) was called; returns it. */
    Hold hold(int n) {
        until(() -> holds.size() >= n, "hold #" + n + " is called (holds so far: " + holds.size() + ")");
        return holds.get(n - 1);
    }

    /** Completes every answer still open with false: a case's cleanup, so no tick waits on it past the case. */
    void answerEveryOpenHold() {
        for (Hold h : holds) h.answer().tryComplete(false);
    }

    /**
     * Waits until {@code t} has ended or is parked (WAITING with a park blocker: on a latch, a lock, a future's await), not merely
     * between two statements; the witness that whatever {@code t} would do next it has done, or waits for.
     */
    static void quiet(Thread t, String what) {
        until(() -> isQuiet(t), what + ": the thread " + t + " ends or parks; it sits at " + Arrays.toString(t.getStackTrace()));
    }

    static boolean isQuiet(Thread t) {
        Thread.State s = t.getState();
        return s == Thread.State.TERMINATED
                || (s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING) && LockSupport.getBlocker(t) != null;
    }

    /** Waits until {@code t} has ended. */
    static void ended(Thread t, String what) {
        until(() -> t.getState() == Thread.State.TERMINATED, what + ": the thread " + t + " ends; it sits at " + Arrays.toString(t.getStackTrace()));
    }

    /** Spins (no sleep) until {@code condition} holds, at most {@link #BOUND}; fails the case naming {@code what} otherwise. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail(what + ": not within " + BOUND);
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
