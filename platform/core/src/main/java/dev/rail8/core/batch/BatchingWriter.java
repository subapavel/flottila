package dev.rail8.core.batch;

import dev.rail8.api.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * The persistence batcher: collects saved items into batches and commits each batch to a {@link BatchSink}, written by
 * hand rather than as a reactive operator chain. It works with Vert.x {@code Future}s and an injected {@link Timer}, and
 * is thread-safe; its callers are the messages' virtual threads.
 *
 * <p>Flushing adapts to load. A save commits at once when no commit is in flight. Under load, items accumulate until
 * there are {@code batchSize} of them, {@code batchBytes} bytes of them (with the weighing constructor; an item above
 * {@code batchBytes} goes alone) or the {@code window} has passed; a batch full by count or by bytes goes as soon as a
 * commit slot is free, without waiting for the window. Up to {@code commitConcurrency} commits are in flight, each
 * completing the items of its own batch. The backlog is bounded: a {@link #save} beyond it fails at once with
 * {@link OverloadedException}, while {@link #saveWaiting} waits for room, in arrival order, for at most {@code maxWait}.
 * A slot that frees is handed to the oldest waiter under the same lock that freed it, so {@code waiting() > 0} implies
 * {@code backlog() == maxBacklog} and a {@link #save} never overtakes a waiter.
 *
 * <p>Containment of faults, exactly as far as it goes. Everything thrown by {@code sink.write}, by the timer, by a
 * timer's handle and by the weight function is contained: it never leaves a public method or a timer task, whatever
 * {@code Throwable} it is, a checked exception thrown without being declared included. A fault that belongs to an
 * item or a batch fails exactly that item or batch, with the thrown object itself as cause where there is one, and is not
 * logged: the failed future is its report. A throw of {@code sink.write} fails its batch; so does a sink that answers
 * {@code null}, with a {@code NullPointerException} that says the sink answered null and names the batch's item count, and so
 * does a sink's future that completes failed, whatever its {@code cause()} answers (with no cause the items fail with the
 * {@code NoStackTraceThrowable} that Vert.x's {@code tryFail} puts in its place). A throw of a waiter's deadline schedule
 * fails that save, a throw of the weight function fails that save with the thrown object and a negative weight with an
 * {@code IllegalArgumentException} that names the weight, nothing queued. A fault that belongs to no item fails none and is
 * counted and logged with its cause. A timer that refuses the window schedule fails no item: the batches the drain just took
 * are committed, they reach the sink in the order taken and whatever stayed queued rides their completions, so nothing is
 * left queued with no commit in flight and no window. The refusal is counted ({@link #windowsRefused()}) and logged at
 * WARNING on the first refusal of an episode and on recovery, two lines per episode. A timer that refuses a cancel is
 * contained the same way: counted and logged on the first refusal of an episode and on recovery, each cancel site (the armed
 * window, an entering waiter's deadline) with an episode of its own.
 *
 * <p>Thrown by the writer itself: {@link #save} and {@link #saveWaiting} of a null item throw {@code NullPointerException},
 * nothing queued, an argument check like the constructors'.
 *
 * <p>Outside the guarantee: a sink whose {@code Future} never completes keeps its batch counted in flight and its items
 * pending; a sink's {@code Future} whose {@code onComplete} throws, or calls its listener more than once, is not contained:
 * the throw leaves the method that dispatched the batch with the batch counted in flight unless its listener runs, and the
 * later batches of the same drain counted in flight too and never handed to the sink ({@link #save}, {@link #saveWaiting}
 * and {@link #flush} throw it at their caller, a window at the timer, and an earlier batch's completion leaves before that
 * batch's items complete, so they stay pending), and a second call releases a second slot, so {@link #inFlight} counts one
 * commit fewer than are in flight and one more than {@code commitConcurrency} may go; inline answers — a throw of
 * {@code sink.write} and a null answer always, a future already completed when its listener runs at once inside
 * {@code onComplete} (a Vert.x future bound to a context the dispatching thread is not on runs it later, on that
 * context) — nest, one commit inside the previous one's completion per queued batch while the commit slots are full, and
 * an error of the JVM raised in the writer's own frames (a stack overflow there, an out-of-memory error) is not
 * contained; weights whose sum passes {@code Long.MAX_VALUE} are not guarded; a
 * timer that answers {@code null} where a handle is due is misreported: a null window handle is not counted as a refusal,
 * ends an open refusal episode with the recovery line and leaves the window unarmed, so every later drain that leaves items
 * queued schedules the window again until the timer answers a handle (what stays queued rides the commits in flight, as
 * after a refused window), and a waiter's null deadline is cancelled when the waiter enters: the
 * {@code NullPointerException} of that call is counted and logged as a refused cancel at that site, and the waiter enters
 * all the same; a log handler that throws from one of the writer's log calls is not contained.
 *
 * <p>Documented non-guarantee: the batches of one drain are handed to the sink oldest first, but a sink that answers
 * inline — a throw of {@code sink.write}, a null answer, or a future already completed whose listener runs at once, as
 * above — re-enters the drain from inside that hand-off, so a younger batch can reach the sink before the outer loop's
 * next one.
 *
 * @param <T> the item type
 * @since 0.1
 */
public final class BatchingWriter<T> implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BatchingWriter.class.getName());

    /**
     * Writer limits; every value is finite and positive, so no limit is ever unbounded.
     *
     * @param batchSize largest batch handed to the sink
     * @param window how long a partial batch may wait while a commit is in flight; zero means only on a commit's completion
     * @param commitConcurrency commits in flight at most
     * @param maxBacklog items queued (not yet handed to the sink) at most; at least one batch
     * @param maxWait how long a {@link #saveWaiting} item may wait for room in the backlog before it fails; positive
     * @since 0.1
     */
    public record Options(int batchSize, Duration window, int commitConcurrency, int maxBacklog, Duration maxWait) {

        /** The default {@code maxWait}: the default stop deadline, so a stopping flow is not held past it. */
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

        /** Measured defaults: 100 / 10 ms / 4 in flight / 64 batches of backlog; waiters wait 10 s at most. */
        public static Options defaults() {
            return new Options(100, Duration.ofMillis(10), 4, 6_400);
        }
    }

    private record Pending<T>(T item, long weight, Promise<Void> done) {}

    /** A {@link #saveWaiting} item waiting for room; its deadline is cancelled when it enters the backlog. */
    private static final class Waiter<T> {
        final Pending<T> pending;
        Timer.Cancellable deadline;   // set under the lock before the waiter is visible

        Waiter(Pending<T> pending) { this.pending = pending; }
    }

    /** One cancel site's refusal episode: each site has its own, so a refusing site never masks the other's first line. */
    private static final class CancelSite {
        final String what;
        boolean refusing;   // an episode is open at this site: a cancel was refused and none served since

        CancelSite(String what) { this.what = what; }
    }

    private final BatchSink<T> sink;
    private final Timer timer;
    private final Options options;
    private final ToLongFunction<? super T> weight;
    private final long batchBytes;
    private final ArrayDeque<Pending<T>> queue = new ArrayDeque<>();
    private final ArrayDeque<Waiter<T>> waiters = new ArrayDeque<>();   // arrival order; non-empty only while the queue is full
    private final List<List<Pending<T>>> flying = new ArrayList<>();   // batches handed to the sink and not yet completed
    private final Object lock = new Object();
    private int inFlight;
    private long queuedBytes;   // the weights of the items in the queue
    private Timer.Cancellable armed;
    private boolean closed;
    private long windowsRefused;        // every refusal of the window schedule
    private boolean windowRefusing;     // an episode is open: the window was refused and not served since
    private long cancelsRefused;        // every refused cancel at either site
    private final CancelSite windowCancel = new CancelSite("the armed window");
    private final CancelSite deadlineCancel = new CancelSite("an entering waiter's deadline");

    /** A writer batching by count alone: every item weighs 1 and a batch holds at most {@code batchSize} of them. */
    public BatchingWriter(BatchSink<T> sink, Timer timer, Options options) {
        this(sink, timer, options, item -> 1L, Objects.requireNonNull(options, "options").batchSize());
    }

    /**
     * A writer batching by count and bytes: a batch holds at most {@code batchSize} items and at most {@code batchBytes}
     * bytes, an item's bytes its {@code weight} (asked once, when it is saved, outside the writer's lock; a throw of it or a
     * negative weight fails that save, nothing queued); an item above {@code batchBytes} goes alone. Queued items whose bytes
     * reach {@code batchBytes} are a full batch, as {@code batchSize} items are.
     *
     * @param batchBytes the bytes of a batch at most; positive
     * @since 0.1
     */
    public BatchingWriter(BatchSink<T> sink, Timer timer, Options options, ToLongFunction<? super T> weight, long batchBytes) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.options = Objects.requireNonNull(options, "options");
        this.weight = Objects.requireNonNull(weight, "weight");
        if (batchBytes < 1) throw new IllegalArgumentException("batchBytes " + batchBytes + " < 1");
        this.batchBytes = batchBytes;
    }

    /**
     * Queues the item; completes when the batch containing it is committed and fails when that commit fails or the backlog
     * is full. A window the timer refuses fails no item: the batch this save's drain took is committed all the same
     * and what stayed queued rides its completion. A throw of the weight function fails the save with the thrown object and a
     * negative weight with an {@code IllegalArgumentException} that names it, nothing queued and the sink not called; the one
     * throw the writer makes itself is a null item's {@code NullPointerException}, nothing queued (the class Javadoc lists
     * what is outside the guarantee).
     */
    public Future<Void> save(T item) {
        return enqueue(item, false);
    }

    /**
     * Queues the item as {@link #save} does when the backlog has room; when it is full the item waits, behind every earlier
     * waiter, and enters the moment a slot frees. Completes when the batch containing it is committed; fails when that
     * commit fails, with {@link OverloadedException} when it still waits {@code maxWait} after the call (it is then never
     * handed to the sink), with {@link IllegalStateException} when the writer is closed, and with whatever the timer throws
     * when its deadline cannot be scheduled, a checked exception too. A null item, a throw of the weight function and a
     * negative weight are met as {@link #save} meets them, before any wait: nobody waits and no deadline is scheduled. Once
     * queued (directly or moved in from the waiters) it behaves as a {@link #save}: a window the timer refuses fails no
     * item.
     */
    public Future<Void> saveWaiting(T item) {
        return enqueue(item, true);
    }

    /** Items waiting for room in a full backlog ({@link #saveWaiting}). */
    public int waiting() {
        synchronized (lock) { return waiters.size(); }
    }

    /**
     * Puts {@code item} into the backlog; a full one fails it at once or, if {@code wait}, lines it up behind the waiters.
     * The weight is asked outside the lock, after the null check and before anything else: any {@code Throwable} of the
     * weight function fails this save with that object and a negative weight with an IllegalArgumentException that names
     * it, nothing queued, nobody waiting, no deadline scheduled. Only a null item is thrown (NullPointerException), the writer's
     * own argument check.
     */
    private Future<Void> enqueue(T item, boolean wait) {
        Objects.requireNonNull(item, "item");
        long bytes;
        try {
            bytes = weight.applyAsLong(item);
        } catch (Throwable e) {   // code the writer was given: its throw is this save's failure, never the caller's
            return Future.failedFuture(e);
        }
        if (bytes < 0) return Future.failedFuture(new IllegalArgumentException("a negative weight " + bytes));
        Pending<T> p = new Pending<>(item, bytes, Promise.promise());
        List<List<Pending<T>>> taken;
        synchronized (lock) {
            if (closed) return Future.failedFuture(new IllegalStateException("writer closed"));
            if (queue.size() >= options.maxBacklog()) {   // full; a waiter already there implies full, so no save overtakes it
                return wait ? waitLocked(p) : Future.failedFuture(new OverloadedException(options.maxBacklog()));
            }
            queue(p);
            taken = drainLocked(false);
        }
        dispatch(taken);
        return p.done().future();
    }

    /**
     * Under the lock: {@code p} waits with a deadline of {@code maxWait}; a timer that refuses it — any {@code Throwable}, a
     * checked exception thrown without being declared too — fails {@code p} with that cause at once and queues nothing,
     * never throws at the saver.
     */
    private Future<Void> waitLocked(Pending<T> p) {
        Waiter<T> w = new Waiter<>(p);
        try {
            w.deadline = timer.schedule(options.maxWait(), () -> expire(w));
        } catch (Throwable e) {
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
     * How often the timer has refused to schedule the window. It only rises; each refusal is counted, whether or not
     * it opened an episode and was logged.
     *
     * @since 0.1
     */
    public long windowsRefused() {
        synchronized (lock) { return windowsRefused; }
    }

    /**
     * How often the timer has refused a cancel, the armed window's and an entering waiter's deadline's together. It only
     * rises; each refusal is counted, whether or not it opened an episode and was logged. Package-private: the count is for
     * the tests only.
     */
    long cancelsRefused() {
        synchronized (lock) { return cancelsRefused; }
    }

    /**
     * Commits what is queued now without waiting for the window (a free commit slot permitting; the rest follows as slots
     * free) and completes when every item waiting, queued or in flight at this moment is committed or failed.
     */
    public Future<Void> flush() {
        List<List<Pending<T>>> taken;
        List<Future<Void>> waiting = new ArrayList<>();
        synchronized (lock) {
            for (List<Pending<T>> batch : flying) for (Pending<T> p : batch) waiting.add(p.done().future());
            for (Pending<T> p : queue) waiting.add(p.done().future());
            for (Waiter<T> w : waiters) waiting.add(w.pending.done().future());
            if (armed != null) { cancelContained(armed, windowCancel); armed = null; }   // a refused cancel gives up the handle all the same: the drain below may arm again
            taken = drainLocked(true);
        }
        dispatch(taken);
        return Future.join(waiting).mapEmpty();
    }

    /** Refuses further saves; what waits, is queued or in flight still commits (window and completions keep draining). */
    @Override public void close() {
        synchronized (lock) { closed = true; }
    }

    /**
     * Under the lock: takes every batch that may go now and arms the window timer for what stays. A timer that refuses the
     * window — any {@code Throwable}, a checked exception thrown without being declared too — is never thrown at the caller
     * and fails no item: the batches just taken are returned for commit like any others. A non-empty queue after the take
     * means a commit is in flight (the take keeps taking while {@code inFlight == 0}, so it never leaves the queue non-empty
     * with no commit in flight), so that commit's completion drains again and nothing stays queued with no commit in flight
     * and no window. The refusal is counted, and logged on the first refusal of an episode; the window served again ends
     * the episode with a second line.
     */
    private List<List<Pending<T>>> drainLocked(boolean windowElapsed) {
        List<List<Pending<T>>> batches = takeLocked(windowElapsed);
        if (queue.isEmpty() || armed != null || options.window().isZero()) return batches;
        try {
            armed = timer.schedule(options.window(), this::onWindow);
        } catch (Throwable e) {
            windowsRefused++;
            if (!windowRefusing) {
                windowRefusing = true;
                LOG.log(System.Logger.Level.WARNING, "BatchingWriter: the timer refuses to schedule the window; "
                        + "the batches already taken still commit and what stays queued rides their completions (D-146)", e);
            }
            return batches;
        }
        if (windowRefusing) {
            windowRefusing = false;
            LOG.log(System.Logger.Level.WARNING, "BatchingWriter: the timer schedules the window again after "
                    + windowsRefused + " refusal(s) in all (D-146)");
        }
        return batches;
    }

    /** Under the lock: {@code p} at the back of the queue, its bytes counted. */
    private void queue(Pending<T> p) {
        queue.add(p);
        queuedBytes += p.weight();
    }

    /**
     * Under the lock: takes every batch that may go now, moving the oldest waiters into each slot a batch frees. A batch is the
     * queue's front: its first item, then each next one while the batch stays within {@code batchSize} items and {@code batchBytes}
     * bytes.
     */
    private List<List<Pending<T>>> takeLocked(boolean windowElapsed) {
        List<List<Pending<T>>> batches = new ArrayList<>();
        while (!queue.isEmpty() && inFlight < options.commitConcurrency()
                && (inFlight == 0 || queue.size() >= options.batchSize() || queuedBytes >= batchBytes || windowElapsed)) {
            List<Pending<T>> batch = new ArrayList<>();
            long bytes = 0;
            do {
                Pending<T> p = queue.poll();
                batch.add(p);
                bytes += p.weight();
                queuedBytes -= p.weight();
            } while (!queue.isEmpty() && batch.size() < options.batchSize() && bytes + queue.peek().weight() <= batchBytes);
            inFlight++;
            flying.add(batch);
            batches.add(batch);
            while (!waiters.isEmpty() && queue.size() < options.maxBacklog()) {   // the freed room goes to the waiters first
                Waiter<T> w = waiters.poll();
                cancelContained(w.deadline, deadlineCancel);
                queue(w.pending);
            }
        }
        return batches;
    }

    /** Outside the lock: hands the batches the drain took to the sink, oldest first. */
    private void dispatch(List<List<Pending<T>>> taken) {
        taken.forEach(this::commit);
    }

    /**
     * Under the lock: cancels {@code handle}; a timer that refuses the cancel — any {@code Throwable}, a checked exception
     * thrown without being declared too — goes no further: a refused cancel never reaches the caller. Every refusal is
     * counted; the first of an episode at {@code site} is logged with its cause and a cancel served again at that site ends
     * the episode with a second line, so a timer refusing every cancel logs two lines per episode, not one per message. The
     * task the refused cancel leaves pending costs time at most: the window's fires into {@link #onWindow}, which only drains again (and gives up a
     * window armed since, which the next drain arms anew), an entered waiter's deadline finds its waiter gone in
     * {@link #expire} and does nothing.
     */
    private void cancelContained(Timer.Cancellable handle, CancelSite site) {
        try {
            handle.cancel();
        } catch (Throwable e) {
            cancelsRefused++;
            if (!site.refusing) {
                site.refusing = true;
                LOG.log(System.Logger.Level.WARNING, "BatchingWriter: the timer refused to cancel " + site.what
                        + "; the writer carries on and the task it left pending can only drain again (D-146)", e);
            }
            return;
        }
        if (site.refusing) {
            site.refusing = false;
            LOG.log(System.Logger.Level.WARNING, "BatchingWriter: the timer cancels " + site.what + " again after "
                    + cancelsRefused + " refused cancel(s) in all (D-146)");
        }
    }

    /** {@code w}'s deadline: if it still waits it leaves the line and fails; one that entered meanwhile is left alone. */
    private void expire(Waiter<T> w) {
        synchronized (lock) {
            if (!waiters.remove(w)) return;
        }
        w.pending.done().tryFail(new OverloadedException(options.maxBacklog()));
    }

    private void onWindow() {
        List<List<Pending<T>>> taken;
        synchronized (lock) {
            armed = null;
            taken = drainLocked(true);
        }
        dispatch(taken);
    }

    /**
     * Outside the lock: hands {@code batch} to the sink. Whatever {@code sink.write} throws or answers fails at most this
     * batch: any {@code Throwable} it throws, a checked one too, and a null answer each become a failed future, so
     * the batch fails, its slot is released and the queue drained again, whichever path dispatched it. What the answered
     * future's {@code onComplete} does is outside the guarantee (class Javadoc).
     */
    private void commit(List<Pending<T>> batch) {
        List<T> items = new ArrayList<>(batch.size());
        for (Pending<T> p : batch) items.add(p.item());
        Future<Void> written;
        try {
            written = sink.write(items);
        } catch (Throwable e) {   // whatever the sink throws fails its own batch; it is not thrown at the dispatcher
            written = Future.failedFuture(e);
        }
        if (written == null) {
            written = Future.failedFuture(new NullPointerException("the sink answered null instead of a Future for a batch of "
                    + batch.size() + " item(s)"));
        }
        written.onComplete(ar -> afterCommit(batch, ar.succeeded(), ar.cause()));
    }

    /**
     * {@code batch}'s commit is over: the slot is released and the queue drained, then its items complete if
     * {@code committed}, else fail with {@code failure} — a failed commit whose future names no cause fails them too:
     * {@code tryFail(null)} on a Vert.x promise fails it with a {@code NoStackTraceThrowable}.
     */
    private void afterCommit(List<Pending<T>> batch, boolean committed, Throwable failure) {
        List<List<Pending<T>>> taken;
        synchronized (lock) {           // release the slot first: a throwing completion listener must not leak it
            inFlight--;
            flying.remove(batch);
            taken = drainLocked(false);
        }
        dispatch(taken);
        for (Pending<T> p : batch) {    // listeners run inline here; Vert.x catches any Throwable of theirs, Error included
            if (committed) p.done().tryComplete(); else p.done().tryFail(failure);
        }
    }
}
