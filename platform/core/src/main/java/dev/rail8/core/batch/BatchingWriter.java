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
 * The persistence batcher (ARCHITECTURE §4 "Writer"): hand-rolled, no reactive operator chain.
 * Adaptive flush — a save commits immediately when no commit is in flight, accumulates to
 * {@code batchSize} items or {@code batchBytes} bytes (the weighed constructor; an item above {@code batchBytes} goes alone) or
 * the {@code window} under load — a batch full by count or by bytes goes on a free slot without waiting for the window; up to
 * {@code commitConcurrency} commits in flight, each completed per batch; a bounded backlog whose overflow fails the save at once with
 * {@link OverloadedException}. {@link #saveWaiting} instead waits for room, in arrival order, at most
 * {@code maxWait} (D-071): a slot that frees is handed to the oldest waiter under the same lock that freed it, so
 * {@code waiting() > 0} implies {@code backlog() == maxBacklog} and a {@link #save} never overtakes a waiter.
 * Ported from the milestone-1 prototype onto Vert.x {@code Future} and an injected {@link Timer}; thread-safe,
 * callers are the messages' virtual threads.
 *
 * <p>D-146: no public method ever completes by throwing. A timer that refuses the window schedule — {@code RuntimeException}
 * or {@code Error} — fails no item: the batches the drain just took are committed, they reach the sink in the order taken and
 * whatever stayed queued rides their completions, so nothing is left queued with no commit in flight and no window. The
 * refusal is counted ({@link #windowsRefused()}) and logged at WARNING on the first refusal of an episode and on recovery,
 * two lines per episode. A timer that refuses a cancel is contained the same way: counted ({@link #cancelsRefused()}) and
 * logged on the first refusal of an episode and on recovery, each cancel site (the armed window, an entering waiter's
 * deadline) with an episode of its own. An {@code Error} from {@code sink.write} or from a waiter's deadline schedule becomes
 * the failure cause of the batch or item it belongs to; nothing is thrown at the caller.
 *
 * <p>Documented non-guarantee: the batches of one drain are handed to the sink oldest first, but a sink that completes
 * inline re-enters the drain from inside that hand-off, so a younger batch can reach the sink before the outer loop's
 * next one.
 *
 * @param <T> the item type
 * @since 0.1
 */
public final class BatchingWriter<T> implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(BatchingWriter.class.getName());

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

    private record Pending<T>(T item, long weight, Promise<Void> done) {}

    /** A {@link #saveWaiting} item waiting for room; its deadline is cancelled when it enters the backlog. */
    private static final class Waiter<T> {
        final Pending<T> pending;
        Timer.Cancellable deadline;   // set under the lock before the waiter is visible

        Waiter(Pending<T> pending) { this.pending = pending; }
    }

    /** One cancel site's refusal episode (D-146): each site has its own, so a refusing site never masks the other's first line. */
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
    private long windowsRefused;        // every refusal of the window schedule, counted (D-146)
    private boolean windowRefusing;     // an episode is open: the window was refused and not served since
    private long cancelsRefused;        // every refused cancel at either site, counted (D-146)
    private final CancelSite windowCancel = new CancelSite("the armed window");
    private final CancelSite deadlineCancel = new CancelSite("an entering waiter's deadline");

    /** A writer batching by count alone: every item weighs 1 and a batch holds at most {@code batchSize} of them. */
    public BatchingWriter(BatchSink<T> sink, Timer timer, Options options) {
        this(sink, timer, options, item -> 1L, Objects.requireNonNull(options, "options").batchSize());
    }

    /**
     * A writer batching by count and bytes (D-109): a batch holds at most {@code batchSize} items and at most {@code batchBytes}
     * bytes, an item's bytes its {@code weight} (asked once, when it is saved; never negative); an item above {@code batchBytes}
     * goes alone. Queued items whose bytes reach {@code batchBytes} are a full batch, as {@code batchSize} items are.
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
     * is full. A window the timer refuses fails no item (D-146): the batch this save's drain took is committed all the same
     * and what stayed queued rides its completion.
     */
    public Future<Void> save(T item) {
        return enqueue(item, false);
    }

    /**
     * Queues the item as {@link #save} does when the backlog has room; when it is full the item waits, behind every earlier
     * waiter, and enters the moment a slot frees. Completes when the batch containing it is committed; fails when that
     * commit fails, with {@link OverloadedException} when it still waits {@code maxWait} after the call (it is then never
     * handed to the sink), with {@link IllegalStateException} when the writer is closed, and with the timer's own exception
     * or {@code Error} when its deadline cannot be scheduled. Once queued (directly or moved in from the waiters) it behaves
     * as a {@link #save}: a window the timer refuses fails no item (D-146).
     */
    public Future<Void> saveWaiting(T item) {
        return enqueue(item, true);
    }

    /** Items waiting for room in a full backlog ({@link #saveWaiting}). */
    public int waiting() {
        synchronized (lock) { return waiters.size(); }
    }

    /**
     * {@code item} into the backlog; a full one fails it at once or, if {@code wait}, lines it up behind the waiters. A negative
     * weight is refused (IllegalArgumentException), nothing queued.
     */
    private Future<Void> enqueue(T item, boolean wait) {
        Objects.requireNonNull(item, "item");
        long bytes = weight.applyAsLong(item);
        if (bytes < 0) throw new IllegalArgumentException("a negative weight " + bytes);
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
     * Under the lock: {@code p} waits with a deadline of {@code maxWait}; a timer that refuses it — an {@code Error} as well
     * as an unchecked exception (D-146) — fails {@code p} with that cause at once and queues nothing, never throws at the saver.
     */
    private Future<Void> waitLocked(Pending<T> p) {
        Waiter<T> w = new Waiter<>(p);
        try {
            w.deadline = timer.schedule(options.maxWait(), () -> expire(w));
        } catch (RuntimeException | Error e) {
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
     * How often the timer has refused to schedule the window (D-146). It only rises; each refusal is counted, whether or not
     * it opened an episode and was logged.
     *
     * @since 0.1
     */
    public long windowsRefused() {
        synchronized (lock) { return windowsRefused; }
    }

    /**
     * How often the timer has refused a cancel, the armed window's and an entering waiter's deadline's together (D-146). It
     * only rises; each refusal is counted, whether or not it opened an episode and was logged.
     *
     * @since 0.1
     */
    public long cancelsRefused() {
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
     * window — {@code RuntimeException} or {@code Error} — is never thrown at the caller and fails no item (D-146): the
     * batches just taken are returned for commit like any others. A non-empty queue after the take means a commit is in
     * flight (the take keeps taking while {@code inFlight == 0}, so it never leaves the queue non-empty with no commit in
     * flight), so that commit's completion drains again and nothing stays queued with no commit in flight and no window. The refusal is counted, and logged on the first refusal of an episode;
     * the window served again ends the episode with a second line.
     */
    private List<List<Pending<T>>> drainLocked(boolean windowElapsed) {
        List<List<Pending<T>>> batches = takeLocked(windowElapsed);
        if (queue.isEmpty() || armed != null || options.window().isZero()) return batches;
        try {
            armed = timer.schedule(options.window(), this::onWindow);
        } catch (RuntimeException | Error e) {
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
     * Under the lock: cancels {@code handle}; a timer that refuses the cancel — {@code RuntimeException} or {@code Error} —
     * goes no further (D-146): no public method completes by throwing. Every refusal is counted; the first of an episode at
     * {@code site} is logged with its cause and a cancel served again at that site ends the episode with a second line, so a
     * timer refusing every cancel logs two lines per episode, not one per message. The task the refused cancel leaves
     * pending costs tempo at most — the window's fires into {@link #onWindow}, which only drains again (and gives up a
     * window armed since, which the next drain arms anew), an entered waiter's deadline finds its waiter gone in
     * {@link #expire} and does nothing.
     */
    private void cancelContained(Timer.Cancellable handle, CancelSite site) {
        try {
            handle.cancel();
        } catch (RuntimeException | Error e) {
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

    private void commit(List<Pending<T>> batch) {
        List<T> items = new ArrayList<>(batch.size());
        for (Pending<T> p : batch) items.add(p.item());
        Future<Void> written;
        try {
            written = sink.write(items);
        } catch (RuntimeException | Error e) {   // D-146: an Error from the sink fails its own batch, it is not thrown at the saver
            written = Future.failedFuture(e);
        }
        written.onComplete(ar -> afterCommit(batch, ar.succeeded() ? null : ar.cause()));
    }

    private void afterCommit(List<Pending<T>> batch, Throwable failure) {
        List<List<Pending<T>>> taken;
        synchronized (lock) {           // release the slot first: a throwing completion listener must not leak it
            inFlight--;
            flying.remove(batch);
            taken = drainLocked(false);
        }
        dispatch(taken);
        for (Pending<T> p : batch) {    // listeners run inline here; Vert.x contains their exceptions
            if (failure == null) p.done().tryComplete(); else p.done().tryFail(failure);
        }
    }
}
