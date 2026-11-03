package io.flottila.core.admission;

import io.flottila.api.NackException;
import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A bound on the sends in flight to one target system (D-099): at most {@code maxCalls} permits out at once, shared by every
 * flow whose {@code to} names the target. Requests only — a message's body is already in its flow's {@link Admission} and a
 * reply is bounded by the endpoint's {@code maxBodyBytes}.
 *
 * <p>{@link #acquire()} never blocks: it answers a granted permit when one is free and nobody waits, else a future that the
 * freed permit completes, earliest waiter first (FIFO across flows), or that fails after {@code maxWait} on the injected
 * {@link Timer} with a retryable {@link NackException} ({@code target <t> busy: <n> calls in flight, waited <maxWait>}). A grant
 * only completes the waiter's future, on the thread that freed the permit; a refusal completes it on the timer's thread.
 *
 * <p>{@link #call} is the message's path: it waits on the calling virtual thread, runs the send there and holds the permit until
 * the send's future settles — not until the caller leaves, so a caller interrupted while its send is out still counts against
 * the target. An interrupt while it waits withdraws the request: nothing is sent, and a grant that raced the interrupt is given
 * back at once.
 *
 * @since 0.1
 */
public final class TargetLimit {

    private final String target;
    private final int maxCalls;
    private final Duration maxWait;
    private final Timer timer;
    private final ReentrantLock lock = new ReentrantLock();
    // guarded by lock
    private final LinkedHashSet<Waiter> waiting = new LinkedHashSet<>();   // arrival order; a withdrawn or refused one leaves at once
    private int inFlight;
    private long refused;

    /** {@code maxCalls} and {@code maxWait} must be positive: never a limit that admits nothing or waits not at all. */
    public TargetLimit(String target, int maxCalls, Duration maxWait, Timer timer) {
        this.target = Objects.requireNonNull(target, "target");
        if (maxCalls <= 0) throw new IllegalArgumentException("target " + target + ": maxCalls must be positive, was " + maxCalls);
        if (!maxWait.isPositive()) throw new IllegalArgumentException("target " + target + ": maxWait must be positive, was " + maxWait);
        this.maxCalls = maxCalls;
        this.maxWait = maxWait;
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /** A permit: granted now, granted later in arrival order, or refused past {@code maxWait}. Never blocks. */
    public Future<Permit> acquire() {
        return request().answer.future();
    }

    /**
     * Sends under a permit: waits for it on the calling (virtual) thread, runs {@code send} on that thread and answers the
     * send's result, holding the permit until the send's future settles. Throws the refusal past {@code maxWait}, the send's
     * own exception or its future's cause as is (a checked one too), or an {@link InterruptedException} with the interrupt flag
     * restored when interrupted while waiting — then nothing was sent and no permit is kept.
     */
    public <T> T call(Supplier<Future<T>> send) throws Exception {
        Objects.requireNonNull(send, "send");
        Waiter request = request();
        Future<Permit> answer = request.answer.future();
        if (!answer.isComplete()) {
            CountDownLatch answered = new CountDownLatch(1);
            answer.onComplete(ignored -> answered.countDown());
            try {
                answered.await();
            } catch (InterruptedException interrupted) {
                withdraw(request);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
        Permit permit = answer.await();   // complete: the permit, or the refusal thrown
        Future<T> sent;
        try {
            sent = Objects.requireNonNull(send.get(), "the send answered no future");
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }
        sent.onComplete(settled -> permit.close());   // released when the send settles, wherever the caller is by then
        return sent.await();
    }

    /** What the limit holds right now. */
    public Gauge gauge() {
        lock.lock();
        try {
            return new Gauge(target, maxCalls, maxWait, inFlight, waiting.size(), refused);
        } finally {
            lock.unlock();
        }
    }

    /** A free permit taken now, or a place in the queue with its {@code maxWait} bound armed. */
    private Waiter request() {
        Waiter request = new Waiter();
        Permit now = null;
        lock.lock();
        try {
            if (waiting.isEmpty() && inFlight < maxCalls) {
                inFlight++;
                now = new Permit();
            } else {
                request.bound = timer.schedule(maxWait, () -> refuse(request));   // armed first: a timer that throws queues nothing
                waiting.add(request);
            }
        } finally {
            lock.unlock();
        }
        if (now != null) request.answer.complete(now);
        return request;
    }

    /** A freed permit passes to the earliest waiter (in flight stays), else it is given back. */
    private void release() {
        Waiter next;
        Permit handed;
        lock.lock();
        try {
            if (waiting.isEmpty()) {
                inFlight--;
                return;
            }
            next = waiting.removeFirst();
            handed = new Permit();
            next.granted = handed;
        } finally {
            lock.unlock();
        }
        next.bound.cancel();
        next.answer.complete(handed);
    }

    /** {@code maxWait} passed: a request still waiting is refused; one granted or withdrawn meanwhile is left alone. */
    private void refuse(Waiter request) {
        int calls;
        lock.lock();
        try {
            if (!waiting.remove(request)) return;
            refused++;
            calls = inFlight;
        } finally {
            lock.unlock();
        }
        request.answer.fail(new NackException("target " + target + " busy: " + calls + " calls in flight, waited " + maxWait, true));
    }

    /** The waiter was interrupted: out of the queue, or — granted meanwhile — the permit it never used given back. */
    private void withdraw(Waiter request) {
        Permit raced;
        lock.lock();
        try {
            waiting.remove(request);
            raced = request.granted;   // null while queued, and when refused meanwhile
        } finally {
            lock.unlock();
        }
        request.bound.cancel();   // armed when it was queued; a no-op once granted or refused
        if (raced != null) raced.close();
    }

    private static final class Waiter {
        final Promise<Permit> answer = Promise.promise();
        Timer.Cancellable bound;   // set under the lock before the waiter is queued, never changed
        Permit granted;            // guarded by lock: the permit a release handed over
    }

    /**
     * One send's place at the target; closing it frees the place, once, however often it is closed.
     *
     * @since 0.1
     */
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit() {}

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) release();
        }
    }

    /**
     * A limit's state: its target and bounds, the permits out, the requests waiting, the requests refused so far.
     *
     * @since 0.1
     */
    public record Gauge(String target, int maxCalls, Duration maxWait, int inFlight, int waiting, long refused) {}
}
