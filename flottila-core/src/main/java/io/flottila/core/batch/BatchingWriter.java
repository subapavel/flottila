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
 * {@link OverloadedException}. Ported from the milestone-1 prototype onto Vert.x {@code Future}
 * and an injected {@link Timer}; thread-safe, callers are the messages' virtual threads.
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
     * @since 0.1
     */
    public record Options(int batchSize, Duration window, int commitConcurrency, int maxBacklog) {

        public Options {
            Objects.requireNonNull(window, "window");
            if (batchSize < 1) throw new IllegalArgumentException("batchSize < 1");
            if (window.isNegative()) throw new IllegalArgumentException("negative window");
            if (commitConcurrency < 1) throw new IllegalArgumentException("commitConcurrency < 1");
            if (maxBacklog < batchSize) throw new IllegalArgumentException("maxBacklog " + maxBacklog + " < batchSize " + batchSize);
        }

        /** The prototype's measured defaults: 100 / 10 ms / 4 in flight / 64 batches of backlog. */
        public static Options defaults() {
            return new Options(100, Duration.ofMillis(10), 4, 6_400);
        }
    }

    private record Pending<T>(T item, Promise<Void> done) {}

    private final BatchSink<T> sink;
    private final Timer timer;
    private final Options options;
    private final ArrayDeque<Pending<T>> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private int inFlight;
    private Timer.Cancellable armed;
    private boolean closed;

    public BatchingWriter(BatchSink<T> sink, Timer timer, Options options) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.options = Objects.requireNonNull(options, "options");
    }

    /** Queues the item; completes when the batch containing it is committed, fails when that commit fails or the backlog is full. */
    public Future<Void> save(T item) {
        Objects.requireNonNull(item, "item");
        Pending<T> p = new Pending<>(item, Promise.promise());
        List<List<Pending<T>>> toCommit;
        synchronized (lock) {
            if (closed) return Future.failedFuture(new IllegalStateException("writer closed"));
            if (queue.size() >= options.maxBacklog()) return Future.failedFuture(new OverloadedException(options.maxBacklog()));
            queue.add(p);
            toCommit = drainLocked(false);
        }
        toCommit.forEach(this::commit);
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

    /** Refuses further saves; what is queued or in flight still commits (window and completions keep draining). */
    @Override public void close() {
        synchronized (lock) { closed = true; }
    }

    /** Under the lock: takes every batch that may go now and arms the window timer for what stays. */
    private List<List<Pending<T>>> drainLocked(boolean windowElapsed) {
        List<List<Pending<T>>> batches = new ArrayList<>();
        while (!queue.isEmpty() && inFlight < options.commitConcurrency()
                && (inFlight == 0 || queue.size() >= options.batchSize() || windowElapsed)) {
            int n = Math.min(options.batchSize(), queue.size());
            List<Pending<T>> batch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) batch.add(queue.poll());
            inFlight++;
            batches.add(batch);
        }
        if (!queue.isEmpty() && armed == null && !options.window().isZero()) {
            armed = timer.schedule(options.window(), this::onWindow);
        }
        return batches;
    }

    private void onWindow() {
        List<List<Pending<T>>> toCommit;
        synchronized (lock) {
            armed = null;
            toCommit = drainLocked(true);
        }
        toCommit.forEach(this::commit);
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
        List<List<Pending<T>>> toCommit;
        synchronized (lock) {           // release the slot first: a throwing completion listener must not leak it
            inFlight--;
            toCommit = drainLocked(false);
        }
        toCommit.forEach(this::commit);
        for (Pending<T> p : batch) {    // listeners run inline here; Vert.x contains their exceptions
            if (failure == null) p.done().tryComplete(); else p.done().tryFail(failure);
        }
    }
}
