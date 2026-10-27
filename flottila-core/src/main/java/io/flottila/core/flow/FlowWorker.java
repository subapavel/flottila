package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.BlobStore;
import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.api.StepConfig;
import io.flottila.api.Timer;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.dlq.Parking;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.Snapshot;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs what an at-least-once {@link Delivery} accepted (ARCHITECTURE §4 lifecycle table, D-049 c, D-055). A round claims the
 * flow's due items from the {@link WorkQueue} — at most one claim in flight per worker, asking for the in-flight limit less
 * the items it still runs — rebuilds each message's ingress envelope from its {@code ingress} snapshot and the blob store
 * (same id, step 0, headers, props, keys, the body by its recorded key; from the item's second claim on the claim's attempts
 * as {@link Envelope#ATTEMPT}, so the rerun's step bodies get keys of their own, D-066) and runs the pipeline without stage
 * 0 — the save recorded it — on one virtual thread per item. While an item runs its lease is renewed every third of the
 * {@link WorkerPolicy#lease()} on the timer, fenced by the claim's attempts: a slow healthy message is never claimed twice at
 * once; a renewal that fails is tried again a third of the lease later; a lease found lost is logged and no longer renewed.
 *
 * <p>An item is completed only when its message is done with: the pipeline succeeded; a step failed and the message was
 * parked (the DLQ row is its record, nothing is logged); or the timeline was read and holds no ingress snapshot (a ghost that
 * can never be rebuilt: logged at WARNING and completed, never a poison loop). Any other failure — the DLQ refusing the park,
 * the store read failing, an {@link Error} — leaves the item to its lease, logged at WARNING with the message id and the
 * cause: the sender holds a 202 for it, so it runs again once the lease runs out. An item claimed more often than
 * {@link WorkerPolicy#maxAttempts()} allows does not run again: its ingress envelope is parked at step 1 with an
 * {@link AttemptsExhaustedException} and the item completed; if the timeline cannot be read, the envelope cannot be rebuilt or
 * the park fails, the item is held — never claimed again, blocking its key — and one ERROR line names the message, the flow,
 * the attempts and the cause, whatever the hold answers; a hold that fails is named in it too and leaves the item to its
 * lease (a ghost is completed as above). A claim that returns an item this worker still runs starts no second run.
 *
 * <p>What polls: {@link #poll()}; a {@linkplain #start() started} worker every {@link WorkerPolicy#pollInterval()} on the
 * timer; the handler of {@link #pollingAfterAccept} after each {@link Ack.Accepted}; and each finished item, once, so a
 * backlog drains without waiting for a tick. A poll nobody waits for logs its failure and never throws. {@link #stop()} ends
 * all of that; items already claimed run to their end and keep their lease renewed. A worker built later over the same store
 * and queue finishes what an earlier one accepted. {@link #close()} (FIX-30) closes the producers the builder created for its
 * pipeline once it was stopped and nothing it claimed still runs. Built by {@link FlowBuilder#worker}; it holds only what the builder handed it — the timer,
 * the policy, the parking, the first step and the producers — never the {@code Runtime}.
 *
 * @since 0.1
 */
public final class FlowWorker {

    private static final System.Logger LOG = System.getLogger(FlowWorker.class.getName());

    private final String flow;
    private final String workerId;
    private final int max;
    private final WorkerPolicy policy;
    private final WorkQueue queue;
    private final MessageStore store;
    private final BlobStore blobs;
    private final Pipeline pipeline;
    private final Parking parking;
    private final StepConfig firstStep;
    private final Timer timer;
    private final Producers producers;

    private final Object lock = new Object();
    private final Map<Long, Lease> running = new HashMap<>();   // guarded by lock: claimed by this worker and not finished
    private boolean claiming;                                    // guarded by lock: a claim is in flight
    private Promise<Integer> followUp;                           // guarded by lock: the round after the claim in flight
    private Timer.Cancellable tick;                              // guarded by lock
    private boolean stopped;                                     // guarded by lock
    private Promise<Void> closed;                                // guarded by lock: null until close(); then the close every call returns

    FlowWorker(String flow, String workerId, int max, WorkerPolicy policy, WorkQueue queue, MessageStore store, BlobStore blobs,
               Pipeline pipeline, Parking parking, StepConfig firstStep, Timer timer, Producers producers) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.max = max;
        this.policy = Objects.requireNonNull(policy, "policy");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.store = Objects.requireNonNull(store, "store");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.parking = Objects.requireNonNull(parking, "parking");
        this.firstStep = Objects.requireNonNull(firstStep, "firstStep");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.producers = Objects.requireNonNull(producers, "producers");
    }

    /**
     * One round: claims up to the flow's in-flight request limit less the items this worker still runs and processes them.
     * Completes, once every item the round started is done with — completed, held or left to its lease — with the number
     * of items the claim returned; 0 without a claim when the worker runs its limit or is stopped. Requested while a claim is
     * in flight, it returns the one round that starts once that claim returns, shared by every poll requested meanwhile.
     * Fails when the claim fails or throws, or when a completion or hold in the queue fails.
     */
    public Future<Integer> poll() {
        int room;
        synchronized (lock) {
            if (stopped) return Future.succeededFuture(0);
            if (claiming) {
                if (followUp == null) followUp = Promise.promise();
                return followUp.future();
            }
            room = max - running.size();
            if (room <= 0) return Future.succeededFuture(0);
            claiming = true;
        }
        Future<List<WorkQueue.Item>> claim;
        try {
            claim = queue.claim(workerId, flow, room, policy.lease());
        } catch (Throwable thrown) {   // a queue that throws instead of failing its future: the same failed round, never a wedged worker
            claim = Future.failedFuture(thrown);
        }
        return claim.transform(this::claimed);
    }

    /** Polls every {@link WorkerPolicy#pollInterval()} on the timer until {@link #stop()}; a second call, or one after stop, does nothing. */
    public void start() {
        synchronized (lock) {
            if (stopped || tick != null) return;
            tick = timer.schedule(policy.pollInterval(), this::tick);
        }
    }

    /**
     * Cancels the tick; from now on no round starts. Items already claimed — those of a claim in flight included — run to their
     * end and keep their lease renewed; a finished item polls no more.
     */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            if (tick != null) tick.cancel();
        }
    }

    /**
     * Closes the producers the builder created for this worker's pipeline (FIX-30), each once, as soon as no item this worker
     * claimed still runs and no claim of its is in flight — the items of that claim run to their end too ({@link #stop()}):
     * at once when the worker is idle, otherwise from whichever ends last: an item's end (its run settled in the queue) or a
     * claim's return that started no item (nothing new, or the claim failed). Until then the
     * close stays pending and closes nothing, so a running item sends through open producers. Every call returns the same
     * future. A producer whose close fails is logged at WARNING and the others close all the same; the future completes,
     * never fails, once every producer's close has settled.
     *
     * @throws IllegalStateException before {@link #stop()}: a close follows the stop, it never stops the worker itself
     */
    public Future<Void> close() {
        Promise<Void> close;
        Promise<Void> due;
        synchronized (lock) {
            if (!stopped) throw new IllegalStateException("worker " + workerId + " of flow " + flow + " is closed after its stop: call stop() first");
            if (closed != null) return closed.future();
            close = closed = Promise.promise();
            due = closeDue();
        }
        closeProducers(due);
        return close.future();
    }

    /** {@code handler}, polling once after each of its {@link Ack.Accepted} answers; every answer and failure passes through as is. */
    public Function<Envelope, Future<Ack>> pollingAfterAccept(Function<Envelope, Future<Ack>> handler) {
        Objects.requireNonNull(handler, "handler");
        return e -> {
            Future<Ack> ack = handler.apply(e);
            ack.onSuccess(a -> {
                if (a instanceof Ack.Accepted) pollFor("after message " + e.id() + " was accepted");
            });
            return ack;
        };
    }

    private void tick() {
        synchronized (lock) {
            if (stopped) return;   // fired as the worker stopped
            tick = timer.schedule(policy.pollInterval(), this::tick);
        }
        pollFor("on the timer");
    }

    /** A poll nobody waits for: its failure is logged, never thrown into a timer or a consumer. */
    private void pollFor(String why) {
        poll().onFailure(failure -> LOG.log(System.Logger.Level.WARNING,
                "worker " + workerId + " of flow " + flow + ": the poll " + why + " failed: " + failure, failure));
    }

    /** The claim returned: start what this worker does not run yet, then the follow-up round if one was requested. */
    private Future<Integer> claimed(AsyncResult<List<WorkQueue.Item>> claim) {
        List<Lease> fresh = new ArrayList<>();
        Promise<Integer> next;
        Promise<Void> due;
        synchronized (lock) {
            claiming = false;
            next = followUp;
            followUp = null;
            if (claim.succeeded()) {
                for (WorkQueue.Item item : claim.result()) {
                    if (running.containsKey(item.seq())) continue;   // its lease ran out under a run of ours that is still going
                    Lease lease = new Lease(item);
                    running.put(item.seq(), lease);
                    fresh.add(lease);
                }
            }
            due = closeDue();   // a claim that returned nothing new to a closed worker running nothing
        }
        closeProducers(due);
        List<Future<Void>> done = new ArrayList<>(fresh.size());
        for (Lease lease : fresh) done.add(process(lease));
        if (next != null) poll().onComplete(next);
        if (claim.failed()) return Future.failedFuture(claim.cause());
        return Future.all(done).map(claim.result().size());
    }

    /** Runs the item on a virtual thread of its own under its renewed lease, settles it in the queue, then polls once. */
    private Future<Void> process(Lease lease) {
        WorkQueue.Item item = lease.item;
        lease.renewLater();
        Promise<Verdict> ran = Promise.promise();
        Thread.ofVirtual().name("flottila-worker-" + workerId + "-" + item.seq()).start(() -> {
            Verdict verdict = Verdict.LEAVE;
            try {
                verdict = verdict(item);
            } finally {
                lease.stop();
                ran.complete(verdict);
            }
        });
        return ran.future().compose(verdict -> settle(item, verdict)).andThen(settled -> finished(item));
    }

    /** Runs the message from its ingress snapshot, or parks it there when it is past the bound; what to do with its item. */
    private Verdict verdict(WorkQueue.Item item) {
        String id = item.messageId();
        boolean exhausted = item.attempts() > policy.maxAttempts();
        try {
            Optional<Snapshot> in = store.timeline(id).await().stream().filter(s -> Recorder.INGRESS.equals(s.stepName())).findFirst();
            if (in.isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "message " + id + " of flow " + flow + " has no ingress snapshot to resume from; its item is completed");
                return Verdict.COMPLETE;
            }
            Envelope ingress = Replay.rebuild(in.get(), id, flow, in.get().props(), blobs);
            if (item.attempts() > 1) ingress = ingress.with(Envelope.ATTEMPT, item.attempts());   // a rerun's step bodies under keys of its own (D-066)
            if (exhausted) {
                parking.park(ingress, firstStep, new AttemptsExhaustedException(id, item.attempts(), policy.maxAttempts())).await();
            } else {
                pipeline.apply(ingress);
            }
            return Verdict.COMPLETE;
        } catch (ParkedException parked) {
            return Verdict.COMPLETE;   // the DLQ row is the failure's record
        } catch (Throwable failure) {
            if (exhausted) return Verdict.hold(failure);
            LOG.log(System.Logger.Level.WARNING, "message " + id + " of flow " + flow + " failed at worker " + workerId + " (attempt " + item.attempts()
                    + " of " + policy.maxAttempts() + ") and is left to its lease (" + policy.lease() + "): " + failure, failure);
            return Verdict.LEAVE;
        }
    }

    /** Completes, holds or leaves the item as the run decided; an unparkable item raises one ERROR whatever its hold answers. */
    private Future<Void> settle(WorkQueue.Item item, Verdict verdict) {
        if (verdict.complete()) return queue.complete(item.seq());
        Throwable unparkable = verdict.unparkable();
        if (unparkable == null) return Future.succeededFuture();
        String alert = "message " + item.messageId() + " of flow " + flow + " was claimed " + item.attempts() + " times (maxAttempts "
                + policy.maxAttempts() + ") and could not be parked: " + unparkable + "; work item " + item.seq();
        return queue.hold(item.seq(), item.attempts()).transform(held -> {
            if (held.failed()) {
                LOG.log(System.Logger.Level.ERROR, alert + " could not be held either (" + held.cause() + "): it is left to its lease", unparkable);
                return Future.failedFuture(held.cause());
            }
            LOG.log(System.Logger.Level.ERROR, alert + " held for a human (never claimed again, it blocks its key): " + held.result(), unparkable);
            return Future.succeededFuture();
        });
    }

    private void finished(WorkQueue.Item item) {
        Promise<Void> due;
        synchronized (lock) {
            running.remove(item.seq());
            due = closeDue();
        }
        closeProducers(due);
        pollFor("after message " + item.messageId() + " finished");
    }

    /**
     * The close to run now: the asked one, once nothing runs and no claim is in flight; else null. Under the lock. After the
     * stop no claim starts, so that state is final: it is reached once — by the close, a claim's return or an item's end.
     */
    private Promise<Void> closeDue() {
        return closed != null && !claiming && running.isEmpty() ? closed : null;
    }

    private void closeProducers(Promise<Void> due) {
        if (due != null) producers.close().onComplete(due);
    }

    /** What a run decided for its item: complete it, leave it to its lease, or hold it ({@code unparkable}: past the bound, the park failed). */
    private record Verdict(boolean complete, Throwable unparkable) {
        static final Verdict COMPLETE = new Verdict(true, null);
        static final Verdict LEAVE = new Verdict(false, null);

        static Verdict hold(Throwable unparkable) { return new Verdict(false, unparkable); }
    }

    /** The lease of one running item: renewed every third of the policy's lease, fenced by the claim's attempts, until the run ends or it is lost. */
    private final class Lease {
        final WorkQueue.Item item;
        private Timer.Cancellable next;   // guarded by this
        private boolean stopped;          // guarded by this: the run ended

        Lease(WorkQueue.Item item) {
            this.item = item;
        }

        synchronized void renewLater() {
            next = timer.schedule(policy.lease().dividedBy(3), this::renew);
        }

        /** The run ended: nothing is renewed from now on, and the answer of a renewal in flight is ignored. */
        synchronized void stop() {
            stopped = true;
            next.cancel();
        }

        private void renew() {
            synchronized (this) {
                if (stopped) return;   // fired as the run ended
            }
            queue.renew(item.seq(), item.attempts(), policy.lease()).onComplete(this::renewed);
        }

        private void renewed(AsyncResult<Boolean> answer) {
            boolean lost = answer.succeeded() && !answer.result();
            synchronized (this) {
                if (stopped) return;   // the run ended while the renewal was in flight: its answer no longer matters
                if (!lost) renewLater();
            }
            if (lost) {
                LOG.log(System.Logger.Level.WARNING, "lease lost: message " + item.messageId() + " of flow " + flow + " (work item " + item.seq() + ", attempt "
                        + item.attempts() + ") runs on at worker " + workerId + " but was claimed again, held or completed meanwhile; its lease is no longer renewed");
            } else if (answer.failed()) {
                LOG.log(System.Logger.Level.WARNING, "the lease of message " + item.messageId() + " of flow " + flow + " (work item " + item.seq()
                        + ") could not be renewed; trying again in " + policy.lease().dividedBy(3) + ": " + answer.cause(), answer.cause());
            }
        }
    }
}
