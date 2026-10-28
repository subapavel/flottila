package io.flottila.core.batch;

import io.flottila.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The persistence batcher (ARCHITECTURE §4 "Writer"): hand-rolled, no reactive operator chain.
 * Adaptive flush — a save commits immediately when no commit is in flight, accumulates to
 * {@code batchSize} or the {@code window} under load; up to {@code commitConcurrency} commits in
 * flight, each completed per batch; a bounded backlog whose overflow fails the save at once with
 * {@link OverloadedException}. {@link #saveWaiting} instead waits for room, in arrival order, at most
 * {@code maxWait} (D-071): a slot that frees is handed to the oldest waiter under the same lock that freed it, so
 * {@code waiting() > 0} implies {@code backlog() == maxBacklog} and a {@link #save} never overtakes a waiter.
 * Ported from the milestone-1 prototype onto Vert.x {@code Future} and an injected {@link Timer}; thread-safe,
 * callers are the messages' virtual threads.
 *
 * @param <T> the item type
 * @since 0.1
 */
public final class BatchingWriter<T> implements AutoCloseable {

    /**
     * Writer limits; every value finite and positive (CLAUDE.md: never default a limit to unlimited).
     *
     * @param batchSize largest batch handed to the sink
     * @param window how long a partial batch may wait while a commit is in flight; zero = only on completion
     * @param commitConcurrency commits in flight at most
     * @param maxBacklog items queued (not yet handed to the sink) at most; at least one batch
     * @param maxWait how long a {@link #saveWaiting} item may wait for room in the backlog before it fails; positive
     * @since 0.1
     */
    public record Options(int batchSize, Duration window, int commitConcurrency, int maxBacklog, Duration maxWait) {

        /** The default {@code maxWait}: the default stop deadline, so a stopping flow is not held past it (D-071). */
        public static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(10);

        public Options {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(maxWait, "maxWait");
            if (batchSize < 1) throw new IllegalArgumentException("batchSize < 1");
            if (window.isNegative()) throw new IllegalArgumentException("negative window");
            if (commitConcurrency < 1) throw new IllegalArgumentException("commitConcurrency < 1");
            if (maxBacklog < batchSize) throw new IllegalArgumentException("maxBacklog " + maxBacklog + " < batchSize " + batchSize);
            if (maxWait.isNegative() || maxWait.isZero()) throw new IllegalArgumentException("maxWait " + maxWait + " not positive");
        }

        /** The limits with the {@link #DEFAULT_MAX_WAIT}. */
        public Options(int batchSize, Duration window, int commitConcurrency, int maxBacklog) {
            this(batchSize, window, commitConcurrency, maxBacklog, DEFAULT_MAX_WAIT);
        }

        /** The prototype's measured defaults: 100 / 10 ms / 4 in flight / 64 batches of backlog; waiters wait 10 s at most. */
        public static Options defaults() {
            return new Options(100, Duration.ofMillis(10), 4, 6_400);
        }
    }

    private record Pending<T>(T item, Promise<Void> done) {}

    /** A batch a drain took and then failed: the window for what stayed behind it could not be scheduled. */
    private record Refused<T>(List<Pending<T>> batch, Throwable cause) {}

    /** What a drain hands out once the lock is released: batches to commit, refused batches to fail. */
    private record Drained<T>(List<List<Pending<T>>> commit, List<Refused<T>> refused) {}

    /** A {@link #saveWaiting} item waiting for room; its deadline is cancelled when it enters the backlog. */
    private static final class Waiter<T> {
        final Pending<T> pending;
        Timer.Cancellable deadline;   // set under the lock before the waiter is visible

        Waiter(Pending<T> pending) { this.pending = pending; }
    }

    private final BatchSink<T> sink;
    private final Timer timer;
    private final Options options;
    private final ArrayDeque<Pending<T>> queue = new ArrayDeque<>();
    private final ArrayDeque<Waiter<T>> waiters = new ArrayDeque<>();   // arrival order; non-empty only while the queue is full
    private final List<List<Pending<T>>> flying = new ArrayList<>();   // batches handed to the sink and not yet completed
    private final Object lock = new Object();
    private int inFlight;
    private Timer.Cancellable armed;
    private boolean closed;

    public BatchingWriter(BatchSink<T> sink, Timer timer, Options options) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Queues the item; completes when the batch containing it is committed, fails when that commit fails or the backlog is
     * full, and with the timer's exception when its batch was taken but the window for the items behind it could not be
     * scheduled (FIX-69: the batch is then never handed to the sink).
     */
    public Future<Void> save(T item) {
        return enqueue(item, false);
    }

    /**
     * Queues the item as {@link #save} does when the backlog has room; when it is full the item waits, behind every earlier
     * waiter, and enters the moment a slot frees. Completes when the batch containing it is committed; fails when that
     * commit fails, with {@link OverloadedException} when it still waits {@code maxWait} after the call (it is then never
     * handed to the sink), with {@link IllegalStateException} when the writer is closed, and with the timer's exception
     * when its deadline cannot be scheduled. Once queued (directly or moved in from the waiters) it also fails, as with
     * {@link #save}, with the timer's exception when its batch was taken but the window for the items behind it could not
     * be scheduled (FIX-69: the batch is then never handed to the sink).
     */
    public Future<Void> saveWaiting(T item) {
        return enqueue(item, true);
    }

    /** Items waiting for room in a full backlog ({@link #saveWaiting}). */
    public int waiting() {
        synchronized (lock) { return waiters.size(); }
    }

    /** {@code item} into the backlog; a full one fails it at once or, if {@code wait}, lines it up behind the waiters. */
    private Future<Void> enqueue(T item, boolean wait) {
        Objects.requireNonNull(item, "item");
        Pending<T> p = new Pending<>(item, Promise.promise());
        Drained<T> drained;
        synchronized (lock) {
            if (closed) return Future.failedFuture(new IllegalStateException("writer closed"));
            if (queue.size() >= options.maxBacklog()) {   // full; a waiter already there implies full, so no save overtakes it
                return wait ? waitLocked(p) : Future.failedFuture(new OverloadedException(options.maxBacklog()));
            }
            queue.add(p);
            drained = drainLocked(false);
        }
        dispatch(drained);
        return p.done().future();
    }

    /** Under the lock: {@code p} waits with a deadline of {@code maxWait}; a timer that refuses it fails {@code p} at once. */
    private Future<Void> waitLocked(Pending<T> p) {
        Waiter<T> w = new Waiter<>(p);
        try {
            w.deadline = timer.schedule(options.maxWait(), () -> expire(w));
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        waiters.add(w);
        return p.done().future();
    }

    /** Items queued and not yet handed to the sink. */
    public int backlog() {
        synchronized (lock) { return queue.size(); }
    }

    /** Commits currently in flight. */
    public int inFlight() {
        synchronized (lock) { return inFlight; }
    }

    /**
     * Commits what is queued now without waiting for the window (a free commit slot permitting; the rest follows as slots
     * free) and completes when every item waiting, queued or in flight at this moment is committed or failed.
     */
    public Future<Void> flush() {
        Drained<T> drained;
        List<Future<Void>> waiting = new ArrayList<>();
        synchronized (lock) {
            for (List<Pending<T>> batch : flying) for (Pending<T> p : batch) waiting.add(p.done().future());
            for (Pending<T> p : queue) waiting.add(p.done().future());
            for (Waiter<T> w : waiters) waiting.add(w.pending.done().future());
            if (armed != null) { armed.cancel(); armed = null; }
            drained = drainLocked(true);
        }
        dispatch(drained);
        return Future.join(waiting).mapEmpty();
    }

    /** Refuses further saves; what waits, is queued or in flight still commits (window and completions keep draining). */
    @Override public void close() {
        synchronized (lock) { closed = true; }
    }

    /**
     * Under the lock: takes every batch that may go now and arms the window timer for what stays. A timer that refuses the
     * window is never thrown at the caller (FIX-69): the batches just taken are released from flight and refused with its
     * exception, and the drain runs again on the freed slots, so nothing stays queued with no commit in flight and no window.
     */
    private Drained<T> drainLocked(boolean windowElapsed) {
        Drained<T> out = new Drained<>(new ArrayList<>(), new ArrayList<>());
        while (true) {
            List<List<Pending<T>>> batches = takeLocked(windowElapsed);
            if (queue.isEmpty() || armed != null || options.window().isZero()) {
                out.commit().addAll(batches);
                return out;
            }
            try {
                armed = timer.schedule(options.window(), this::onWindow);
            } catch (RuntimeException | Error e) {
                // nothing taken: the queue is non-empty, so a commit is in flight and its completion drains again
                if (batches.isEmpty()) return out;
                for (List<Pending<T>> batch : batches) {
                    inFlight--;
                    flying.remove(batch);
                    out.refused().add(new Refused<>(batch, e));
                }
                continue;
            }
            out.commit().addAll(batches);
            return out;
        }
    }

    /** Under the lock: takes every batch that may go now, moving the oldest waiters into each slot a batch frees. */
    private List<List<Pending<T>>> takeLocked(boolean windowElapsed) {
        List<List<Pending<T>>> batches = new ArrayList<>();
        while (!queue.isEmpty() && inFlight < options.commitConcurrency()
                && (inFlight == 0 || queue.size() >= options.batchSize() || windowElapsed)) {
            int n = Math.min(options.batchSize(), queue.size());
            List<Pending<T>> batch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) batch.add(queue.poll());
            inFlight++;
            flying.add(batch);
            batches.add(batch);
            while (!waiters.isEmpty() && queue.size() < options.maxBacklog()) {   // the freed room goes to the waiters first
                Waiter<T> w = waiters.poll();
                w.deadline.cancel();
                queue.add(w.pending);
            }
        }
        return batches;
    }

    /** Outside the lock: fails the refused batches, oldest first, then hands the taken ones to the sink. */
    private void dispatch(Drained<T> drained) {
        for (Refused<T> r : drained.refused()) {
            for (Pending<T> p : r.batch()) p.done().tryFail(r.cause());
        }
        drained.commit().forEach(this::commit);
    }

    /** {@code w}'s deadline: if it still waits it leaves the line and fails; one that entered meanwhile is left alone. */
    private void expire(Waiter<T> w) {
        synchronized (lock) {
            if (!waiters.remove(w)) return;
        }
        w.pending.done().tryFail(new OverloadedException(options.maxBacklog()));
    }

    private void onWindow() {
        Drained<T> drained;
        synchronized (lock) {
            armed = null;
            drained = drainLocked(true);
        }
        dispatch(drained);
    }

    private void commit(List<Pending<T>> batch) {
        List<T> items = new ArrayList<>(batch.size());
        for (Pending<T> p : batch) items.add(p.item());
        Future<Void> written;
        try {
            written = sink.write(items);
        } catch (RuntimeException e) {
            written = Future.failedFuture(e);
        }
        written.onComplete(ar -> afterCommit(batch, ar.succeeded() ? null : ar.cause()));
    }

    private void afterCommit(List<Pending<T>> batch, Throwable failure) {
        Drained<T> drained;
        synchronized (lock) {           // release the slot first: a throwing completion listener must not leak it
            inFlight--;
            flying.remove(batch);
            drained = drainLocked(false);
        }
        dispatch(drained);
        for (Pending<T> p : batch) {    // listeners run inline here; Vert.x contains their exceptions
            if (failure == null) p.done().tryComplete(); else p.done().tryFail(failure);
        }
    }
}
