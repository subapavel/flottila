package io.flottila.core.admission;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-flow admission: a budget of in-flight <em>requests and bytes</em> (CLAUDE.md; ARCHITECTURE §4 —
 * "memory, not thread count, is what backpressure protects"). Both limits are finite and positive.
 *
 * <p>{@link #tryAcquire(long)} is the non-blocking path (HTTP answers 503 + Retry-After when it is empty);
 * {@link #acquire(long)} parks the calling virtual thread until the request is admitted (pollers pause).
 * Waiters are served strictly in arrival order: a later, smaller request never overtakes the head of
 * the queue, and a non-blocking caller never jumps it either. A request larger than the whole byte
 * budget can never be admitted and is refused with {@link IllegalArgumentException} rather than waited
 * on forever — the flow's max body size should have rejected it earlier.
 *
 * <p>Every {@link Permit} releases exactly once, however often it is closed. The lock is held only for
 * bookkeeping; blocking happens on per-waiter conditions, so a release wakes one head waiter, not the
 * whole queue (10k waiters are a bench case, not a thundering herd).
 *
 * @since 0.1
 */
public final class Admission {

    private final long maxRequests;
    private final long maxBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private long inFlightRequests;
    private long inFlightBytes;

    public Admission(long maxRequests, long maxBytes) {
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive, was " + maxRequests);
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive, was " + maxBytes);
        this.maxRequests = maxRequests;
        this.maxBytes = maxBytes;
    }

    public long maxRequests() { return maxRequests; }

    public long maxBytes() { return maxBytes; }

    /** Admits now or answers empty; never waits and never overtakes a waiting caller. */
    public Optional<Permit> tryAcquire(long bytes) {
        checkSize(bytes);
        lock.lock();
        try {
            if (waiters.isEmpty() && fits(bytes)) return Optional.of(take(bytes));
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /** Parks the calling (virtual) thread until admitted, in arrival order; an interrupt leaves the queue holding nothing. */
    public Permit acquire(long bytes) throws InterruptedException {
        checkSize(bytes);
        lock.lock();
        try {
            if (waiters.isEmpty() && fits(bytes)) return take(bytes);
            Waiter me = new Waiter(bytes, lock.newCondition());
            waiters.addLast(me);
            try {
                while (waiters.peekFirst() != me || !fits(bytes)) me.admitted.await();
            } catch (InterruptedException e) {
                waiters.remove(me);
                wakeHead();
                throw e;
            }
            waiters.pollFirst();
            Permit permit = take(bytes);
            wakeHead();
            return permit;
        } finally {
            lock.unlock();
        }
    }

    public long inFlightRequests() {
        lock.lock();
        try { return inFlightRequests; } finally { lock.unlock(); }
    }

    public long inFlightBytes() {
        lock.lock();
        try { return inFlightBytes; } finally { lock.unlock(); }
    }

    /** Callers parked in {@link #acquire(long)} right now. */
    public int waiting() {
        lock.lock();
        try { return waiters.size(); } finally { lock.unlock(); }
    }

    private void checkSize(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes must not be negative, was " + bytes);
        if (bytes > maxBytes) throw new IllegalArgumentException("a body of " + bytes + " bytes can never be admitted: the flow's byte budget is " + maxBytes);
    }

    private boolean fits(long bytes) {
        return inFlightRequests < maxRequests && inFlightBytes + bytes <= maxBytes;
    }

    private Permit take(long bytes) {
        inFlightRequests++;
        inFlightBytes += bytes;
        return new Permit(bytes);
    }

    private void release(long bytes) {
        lock.lock();
        try {
            inFlightRequests--;
            inFlightBytes -= bytes;
            wakeHead();
        } finally {
            lock.unlock();
        }
    }

    /** Signals the head waiter if it fits now; it re-checks under the lock and passes the baton on. */
    private void wakeHead() {
        Waiter head = waiters.peekFirst();
        if (head != null && fits(head.bytes)) head.admitted.signal();
    }

    private record Waiter(long bytes, Condition admitted) {}

    /**
     * One admitted request; closing it gives its request and bytes back, once.
     *
     * @since 0.1
     */
    public final class Permit implements AutoCloseable {
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(long bytes) { this.bytes = bytes; }

        public long bytes() { return bytes; }

        public boolean released() { return released.get(); }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) release(bytes);
        }
    }
}
