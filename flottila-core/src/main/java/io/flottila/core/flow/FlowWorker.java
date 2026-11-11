package io.flottila.core.flow;

import io.flottila.api.BlobStore;
import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.api.StepConfig;
import io.flottila.api.Timer;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.dlq.Parking;
import io.flottila.core.node.FencedException;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.Snapshot;
import io.flottila.core.record.Trail;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

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
 * <p>The run is bound to a {@link Trail} seeded with the ingress body's key (D-064, D-066, F-10): its step bodies are
 * persisted and its rows written when the pipeline ended — succeeded, parked or thrown, an {@link Error} included — and the
 * item is settled only after that trail closed, so a crash before that leaves the item to a rerun that records again. What the
 * trail could not persist or write never changes what is done with the item.
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
 * lease (a ghost is completed as above). A claim that returns an item this worker still runs starts no second run: the run
 * adopts that claim — its attempts become the token the lease is renewed and the item settled by, and a renewal stopped as
 * lost resumes (Q-1, D-074); an adoption after the settle read its token is not seen: complete answers false, the row stays
 * leased to this worker unrenewed and runs once more after its lease.
 *
 * <p>The queue completes or holds an item only for its current holder (D-074). A complete that answers false — the item was
 * claimed again, held or completed meanwhile — leaves the row to its holder as it is, logged once at WARNING with the message,
 * the flow, the work item, the attempt and the worker, and counted by {@link #staleSettles()}; the round succeeds and the
 * complete is never tried again. A hold that answers false is counted the same way and its ERROR says the item was not held.
 *
 * <p>Same-key fast path (D-075): an item to complete that has a partition key is settled, while the worker is not stopped and
 * its chain is shorter than {@link WorkerPolicy#maxChain()}, by {@link WorkQueue#completeAndTakeNext}; the next item of its key
 * handed out with it runs at once in the same slot — its seq takes the finished one's place among the running items, so the
 * room a claim asks for is unchanged — under a lease of its own, on a virtual thread of its own, one more in the chain (the
 * claimed item is 0). A stale answer is counted and logged as a stale complete is and takes nothing; a failed one fails the
 * round. Any other item — no key, left to its lease or held, the worker stopped, the chain at its bound — is settled as above
 * and ends its chain, and the key goes back to the seq-ordered claim. A next item past {@link WorkerPolicy#maxAttempts()} is
 * parked as a claimed one is, and its key goes on.
 *
 * <p>The node's fence (D-101 (2), {@link FlowBuilder#fence}) is checked before every claim — closed, a round claims nothing and
 * answers 0 —, before every complete, fast path and hold, and before
 * an item past {@link WorkerPolicy#maxAttempts()} is parked: while it is closed the node cannot prove its lease is live and
 * another node may take its items over, so a finished item is settled not at all — no complete, no fast path, no hold — and
 * is left unsettled, its run's outcome to whoever runs it next (one WARNING per item); an item past the bound is not parked
 * (no DLQ row a later holder could write again) and is never held for it, but left to its lease. The at-least-once {@code to}
 * checks the same fence before each send and fails it with a {@link FencedException}, never retried and never parked, and a step's
 * failure under a closed fence is not parked but surfaces as a {@link FencedException} with that failure as its cause (no DLQ row).
 *
 * <p>The local hand-off (D-121 (a), P2-16a). A {@linkplain #start() started} worker is registered in the node's {@link Workers}
 * until its {@link #stop()}; the intake asks it for a {@link Slot} before its insert ({@link Workers#reserve}) — none while the
 * node's fence is closed, the worker is stopped, its room ({@code max − running − reserved}, less what a claim in flight asked
 * for) is taken or a backlog is known — and the item it writes leased to the node (attempts 1, the policy's lease) runs in that
 * slot after the commit ({@link Slot#run}) as a claimed item does: renewed, fenced, settled, parked past {@link WorkerPolicy#maxAttempts()}. A slot is
 * never refused once reserved: it runs after {@link #stop()}, and fenced under a closed fence. A slot whose item was not
 * written leased is given back ({@link Slot#cancel}); one whose commit is in doubt arms the lease-end wake below.
 *
 * <p>What claims: {@link #poll()}; {@link #start()}, once, then every {@link WorkerPolicy#pollInterval()} on the timer (the
 * reconciliation, D-121 (b) B5: its first an interval plus the flow's offset after the start); a {@linkplain Workers#wake wake} —
 * a work event from another node among them ({@link WorkSignals}); and the end of a chain (an item no fast path followed) only while a backlog is known. The
 * backlog flag is set from the start, by a wake (a save without a slot, a released hold), by a claim that filled the room it
 * asked for and by a keyed item settled plainly at the chain's bound (its key goes back to the claim); a claim answering less
 * than it asked clears it, unless a wake came since it was issued. An item left to its lease — its run left it, its settle was
 * skipped under a closed fence or failed, its lease was found lost, or its hand-off's commit is in doubt — arms the lease-end
 * wake: one timer at the policy's lease from then, which claims; items left while it is armed fold into it, a later one
 * arming it again for its own end once it fired. A poll nobody waits for logs its failure and never throws. {@link #stop()}
 * ends all of that; items already claimed or reserved run to their end and keep their lease renewed. A worker built later
 * over the same store and queue finishes what an earlier one accepted. {@link #close()} (FIX-30) closes the producers the
 * builder created for its pipeline once it was stopped and nothing it claimed or reserved still runs. Built by
 * {@link FlowBuilder#worker}; it holds only what the builder handed it — the clock, the timer, the policy, the parking, the
 * first step, the producers, the node's {@link Workers} and its {@link WorkSignals} — never the {@code Runtime}.
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
    private final BooleanSupplier fence;
    private final Clock clock;
    private final Workers workers;
    private final WorkSignals signals;

    private final Object lock = new Object();
    private final Map<Long, Lease> running = new HashMap<>();   // guarded by lock: claimed by this worker and not finished
    private int reserved;                                        // guarded by lock: slots handed out, not yet run or cancelled
    private boolean claiming;                                    // guarded by lock: a claim is in flight
    private int asking;                                          // guarded by lock: the room the claim in flight asked for, 0 without one
    private Promise<Integer> followUp;                           // guarded by lock: the round after the claim in flight
    private boolean backlog = true;                              // guarded by lock: rows may wait for a claim (D-121 A6)
    private long wakes;                                          // guarded by lock: wakes so far; a claim clears the backlog only with none since it began
    private Timer.Cancellable leaseEnd;                          // guarded by lock: the armed lease-end wake, null: none
    private Instant laterEnd;                                    // guarded by lock: the end of an item left after it was armed, null: none
    private Timer.Cancellable tick;                              // guarded by lock
    private boolean stopped;                                     // guarded by lock
    private Promise<Void> closed;                                // guarded by lock: null until close(); then the close every call returns
    private final AtomicLong staleSettles = new AtomicLong();

    FlowWorker(String flow, String workerId, int max, WorkerPolicy policy, WorkQueue queue, MessageStore store, BlobStore blobs,
               Pipeline pipeline, Parking parking, StepConfig firstStep, Clock clock, Timer timer, Producers producers, BooleanSupplier fence,
               Workers workers, WorkSignals signals) {
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
        this.fence = Objects.requireNonNull(fence, "fence");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.signals = Objects.requireNonNull(signals, "signals");
    }

    /**
     * One round: claims up to the flow's in-flight request limit less the items this worker still runs and the slots it reserved,
     * and processes them.
     * Completes, once every item the round started — the items its fast paths took after them included — is done with —
     * completed, held or left to its lease — with the number
     * of items the claim returned; 0 without a claim when the node's fence is closed, the worker has no room or is stopped. Requested while a claim is
     * in flight, it returns the one round that starts once that claim returns, shared by every poll requested meanwhile. A claim
     * that returns the room it asked for keeps the backlog known; one returning less clears it, unless a wake came meanwhile.
     * Fails when the claim fails or throws, or when a completion, fast path or hold in the queue fails; one that answers false
     * or stale ({@link #staleSettles()}) is no failure.
     */
    public Future<Integer> poll() {
        if (!fence.getAsBoolean()) return Future.succeededFuture(0);   // the node's fence closed (D-101 (2)): no TAKE, nothing claimed
        int room;
        long since;
        synchronized (lock) {
            if (stopped) return Future.succeededFuture(0);
            if (claiming) {
                if (followUp == null) followUp = Promise.promise();
                return followUp.future();
            }
            room = max - running.size() - reserved;
            if (room <= 0) return Future.succeededFuture(0);
            claiming = true;
            asking = room;
            since = wakes;
        }
        Future<List<WorkQueue.Item>> claim;
        try {
            claim = queue.claim(workerId, flow, room, policy.lease());
        } catch (Throwable thrown) {   // a queue that throws instead of failing its future: the same failed round, never a wedged worker
            claim = Future.failedFuture(thrown);
        }
        return claim.transform(answer -> claimed(answer, room, since));
    }

    /**
     * Registers this worker in the node's {@link Workers} for its flow, claims once and then reconciles every
     * {@link WorkerPolicy#pollInterval()} on the timer until {@link #stop()}, the first reconciliation an interval plus
     * {@code floorMod(flow.hashCode(), interval in ms)} ms after this call (D-121 (b) B5: the flows of one node do not claim
     * together; an interval below 1 ms has no offset); a second call, or one after stop, does nothing.
     */
    public void start() {
        synchronized (lock) {
            if (stopped || tick != null) return;
            long every = Math.max(1, policy.pollInterval().toMillis());
            tick = timer.schedule(policy.pollInterval().plusMillis(Math.floorMod(flow.hashCode(), every)), this::tick);
        }
        workers.register(flow, this);
        pollFor("at the start");
    }

    /**
     * Leaves the node's {@link Workers}, cancels the tick and the lease-end wake; from now on no round starts and no slot is
     * reserved. Items already claimed — those of a claim in flight and of a fast path in flight included — and slots already
     * reserved run to their end and keep their lease renewed; a finished item takes no next one and polls no more.
     */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            if (tick != null) tick.cancel();
            if (leaseEnd != null) leaseEnd.cancel();
            leaseEnd = null;
            laterEnd = null;
        }
        workers.deregister(flow, this);
    }

    /**
     * One hand-off reserved in this worker (D-121 A1, A5): the room of one item, taken until it is {@linkplain #run run} with the
     * item the intake wrote leased to the node or {@linkplain #cancel cancelled}, exactly once. A reserved slot is never refused:
     * it runs after the worker's stop, and fenced under a closed fence; {@link #close()} waits for it.
     *
     * @since 0.1
     */
    public final class Slot {

        private final AtomicBoolean used = new AtomicBoolean();

        private Slot() {
        }

        /** The lease the item is to be written with: the worker's {@link WorkerPolicy#lease()}. */
        public Duration lease() {
            return policy.lease();
        }

        /**
         * Runs {@code leased} — the item written leased to this worker's node by the save the slot was reserved for, its commit
         * acknowledged — in this slot, as a claimed item runs: renewed, fenced, settled, parked past its attempts.
         *
         * @throws IllegalStateException the slot was run or cancelled already
         */
        public void run(WorkQueue.Item leased) {
            Objects.requireNonNull(leased, "leased");
            use();
            handedOff(leased);
        }

        /**
         * Gives the room back: the save failed or its item was not written leased (its key busy: the key's holder hands it on).
         * {@code mayHaveLanded} — the commit is in doubt — arms the lease-end wake, which claims the row once its lease ran out; the
         * room is back before the wake is armed, so a timer that throws reaches the caller with the room given back.
         *
         * @throws IllegalStateException the slot was run or cancelled already
         */
        public void cancel(boolean mayHaveLanded) {
            use();
            cancelled(mayHaveLanded);
        }

        private void use() {
            if (!used.compareAndSet(false, true)) throw new IllegalStateException("a slot of worker " + workerId + " of flow " + flow + " is run or cancelled once");
        }
    }

    /** A slot of this worker (see {@link Workers#reserve}); empty while the fence is closed, it is stopped, it has no room or a backlog is known. */
    Optional<Slot> reserve() {
        if (!fence.getAsBoolean()) return Optional.empty();   // a node that cannot prove its lease leases nothing to itself
        synchronized (lock) {
            if (stopped || backlog || max - running.size() - reserved - asking <= 0) return Optional.empty();   // a claim out holds what it asked
            reserved++;
        }
        return Optional.of(new Slot());
    }

    /** Rows may wait for a claim: the backlog is known, and a claim starts now if there is room. */
    void wake(String why) {
        synchronized (lock) {
            backlog = true;
            wakes++;
        }
        pollFor(why);
    }

    /**
     * Closes the producers the builder created for this worker's pipeline (FIX-30), each once, as soon as no item this worker
     * claimed still runs and no claim of its is in flight — the items of that claim run to their end too ({@link #stop()}):
     * at once when the worker is idle, otherwise from whichever ends last: a chain's end (its last run settled in the queue) or a
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

    /**
     * Completions, fast paths and holds this worker asked for that the queue answered false or stale (Q-1, D-074): the item was
     * claimed again, held or completed meanwhile and was left to its holder.
     */
    public long staleSettles() { return staleSettles.get(); }

    /**
     * The items this worker claimed or was handed and still runs: none of them completed, held or left to its lease in the queue
     * yet — what a {@link #close()} still waits for (FIX-142), beside the slots it reserved.
     */
    public int unsettled() {
        synchronized (lock) {
            return running.size();
        }
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

    /**
     * The claim of {@code asked} items, issued after {@code since} wakes, returned: start what this worker does not run yet, then
     * the follow-up round if one was requested.
     */
    private Future<Integer> claimed(AsyncResult<List<WorkQueue.Item>> claim, int asked, long since) {
        List<Lease> fresh = new ArrayList<>();
        Promise<Integer> next;
        Promise<Void> due;
        synchronized (lock) {
            claiming = false;
            asking = 0;
            next = followUp;
            followUp = null;
            if (claim.succeeded()) {
                if (claim.result().size() >= asked) backlog = true;   // filled: more may wait
                else if (wakes == since) backlog = false;             // drained, and nothing woke it since
                for (WorkQueue.Item item : claim.result()) {
                    Lease ours = running.get(item.seq());
                    if (ours != null) {   // its lease ran out under a run of ours that is still going: the run takes this claim on
                        ours.adopt(item);
                        continue;
                    }
                    Lease lease = new Lease(item);
                    running.put(item.seq(), lease);
                    fresh.add(lease);
                }
            }
            due = closeDue();   // a claim that returned nothing new to a closed worker running nothing
        }
        closeProducers(due);
        List<Future<Void>> done = new ArrayList<>(fresh.size());
        for (Lease lease : fresh) done.add(process(lease, 0));
        if (next != null) poll().onComplete(next);
        if (claim.failed()) return Future.failedFuture(claim.cause());
        return Future.all(done).map(claim.result().size());
    }

    /**
     * A reserved slot runs {@code item}, written leased to the node (D-121 A4): in the running items from now on, out of the reserved
     * ones. A run that cannot start — its renewal not armed (the timer threw), or its virtual thread not started once the renewal
     * was armed — has its lease stopped, is out of the running items again (a close it was the last to hold completes) and its
     * item left to its lease; the throw is passed on, a throw of that cleanup carried on it as suppressed, never in its place.
     */
    private void handedOff(WorkQueue.Item item) {
        Lease lease = new Lease(item);
        synchronized (lock) {
            reserved--;
            running.put(item.seq(), lease);
        }
        Future<Void> ran;
        try {
            ran = process(lease, 0);
        } catch (RuntimeException | Error notStarted) {
            Promise<Void> due;
            synchronized (lock) {
                running.remove(item.seq());
                due = closeDue();
            }
            suppressing(notStarted, lease::stop);   // a renewal armed before the start threw renews no longer
            suppressing(notStarted, () -> closeProducers(due));
            suppressing(notStarted, this::leftToLease);
            throw notStarted;
        }
        ran.onFailure(failure -> LOG.log(System.Logger.Level.WARNING, "worker " + workerId + " of flow " + flow
                + ": the hand-off of message " + item.messageId() + " (work item " + item.seq() + ") failed to settle: " + failure, failure));
    }

    /** {@code cleanup} after {@code primary} was thrown: a throw of its own is carried on {@code primary} as suppressed, never in its place. */
    private static void suppressing(Throwable primary, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error also) {
            primary.addSuppressed(also);
        }
    }

    /**
     * A reserved slot given back — its room and a close it was the last to hold first; {@code mayHaveLanded}: then the lease-end
     * wake, for its item may be in the queue leased to the node, claimed at its lease end.
     */
    private void cancelled(boolean mayHaveLanded) {
        Promise<Void> due;
        synchronized (lock) {
            reserved--;
            due = closeDue();
        }
        closeProducers(due);
        if (mayHaveLanded) leftToLease();
    }

    /** An item was left to its lease: the lease-end wake claims it once its lease ran out. */
    private void leftToLease() {
        synchronized (lock) {
            armLeaseEnd();
        }
    }

    /**
     * Under the lock: the lease-end wake for an item left now — armed at the policy's lease from now when none is, else kept as
     * the later end the armed one re-arms for when it fires (an end it has reached by then is folded into it). Nothing after the stop.
     */
    private void armLeaseEnd() {
        if (stopped) return;
        if (leaseEnd == null) leaseEnd = timer.schedule(policy.lease(), this::leaseEnded);
        else laterEnd = clock.instant().plus(policy.lease());   // never before the armed end: one lease on a clock that never goes back
    }

    /** The lease-end wake fired: armed again for a later end still ahead, then a wake. */
    private void leaseEnded() {
        synchronized (lock) {
            leaseEnd = null;
            Instant now = clock.instant();
            if (laterEnd != null && laterEnd.isAfter(now)) {
                leaseEnd = timer.schedule(Duration.between(now, laterEnd), this::leaseEnded);
            }
            laterEnd = null;
        }
        wake("at an item's lease end");
    }

    /**
     * Runs the item — {@code chain} items after the one its chain claimed (D-075) — on a virtual thread of its own under its
     * renewed lease and settles it in the queue; the item the fast path took next runs in its slot, else the chain ends and
     * claims while a backlog is known. Completes when the chain's last item is done with.
     */
    private Future<Void> process(Lease lease, int chain) {
        WorkQueue.Item item = lease.item;
        lease.renewLater();
        Promise<Verdict> ran = Promise.promise();
        Thread.ofVirtual().name("flottila-worker-" + workerId + "-" + item.seq()).start(() -> {
            Verdict verdict = Verdict.LEAVE;
            try {
                verdict = verdict(item);
            } finally {
                if (lease.stop()) leftToLease();   // found lost: whoever holds it now, its lease end is claimed
                ran.complete(verdict);
            }
        });
        return ran.future().compose(verdict -> settleOrTake(lease, verdict, chain)).transform(settled -> {
            if (settled.succeeded() && settled.result().isPresent()) return process(settled.result().get(), chain + 1);
            finished(item);   // the chain's end
            return settled.succeeded() ? Future.succeededFuture() : Future.failedFuture(settled.cause());
        });
    }

    /**
     * Settles the item — not at all while the node's fence is closed (left to its lease); one to complete that has a key, while the
     * worker is not stopped and its chain is shorter than {@link WorkerPolicy#maxChain()}, by the same-key fast path (D-075): the
     * item the queue hands out with it, if any, is returned under a lease of its own, in the finished item's slot. One at the
     * chain's bound is completed plainly and its key goes back to the claim: the backlog is known.
     */
    private Future<Optional<Lease>> settleOrTake(Lease lease, Verdict verdict, int chain) {
        WorkQueue.Item item = lease.item;
        if (!fence.getAsBoolean()) {   // the node's fence closed (D-101 (2)): no complete, no fast path, no hold
            LOG.log(System.Logger.Level.WARNING, "message " + item.messageId() + " of flow " + flow + " (work item " + item.seq() + ", attempt "
                    + lease.token() + ") finished at worker " + workerId + " while the node's fence is closed: left unsettled for whoever runs it next");
            leftToLease();
            return Future.succeededFuture(Optional.empty());
        }
        if (!verdict.complete() || item.partitionKey() == null || stopped()) return settle(lease, verdict).map(Optional.empty());
        if (chain >= policy.maxChain()) {
            synchronized (lock) {
                backlog = true;   // the key yields to the claim with its next rows, if any, behind it
            }
            return settle(lease, verdict).map(Optional.empty());
        }
        int token = lease.token();
        return queue.completeAndTakeNext(item.seq(), token, workerId, policy.lease()).map(taken -> taken(item, token, taken));
    }

    /**
     * What the fast path answered for {@code item}: stale (counted and logged, nothing taken), nothing taken — the key's next row,
     * if it lies on another node, announced to that node ({@link WorkSignals}, D-121 (b) B4 (i)), the fast path's commit
     * acknowledged —, or the next item, which takes the finished one's slot — unless a claim of this worker answered after the take started it
     * already, under a newer token: then that run goes on and this chain ends.
     */
    private Optional<Lease> taken(WorkQueue.Item item, int token, WorkQueue.Completion taken) {
        if (!taken.completed()) {
            stale(item, token);
            return Optional.empty();
        }
        if (taken.next().isEmpty()) {
            taken.nextNode().ifPresent(this::announce);   // committed: the key's next row is that node's to claim (B4 (i))
            return Optional.empty();
        }
        WorkQueue.Item next = taken.next().get();
        synchronized (lock) {
            if (running.containsKey(next.seq())) return Optional.empty();
            running.remove(item.seq());
            Lease lease = new Lease(next);
            running.put(next.seq(), lease);
            return Optional.of(lease);
        }
    }

    private boolean stopped() {
        synchronized (lock) {
            return stopped;
        }
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
                if (!fence.getAsBoolean()) {   // a park is a settle (D-101 (2)): never from a closed fence, whose items another node may take and park
                    throw new FencedException("message " + id + " of flow " + flow + " is past maxAttempts " + policy.maxAttempts()
                            + " and not parked: the node's fence is closed; left unsettled for whoever runs it next");
                }
                parking.park(ingress, firstStep, new AttemptsExhaustedException(id, item.attempts(), policy.maxAttempts())).await();
            } else {
                Trail trail = new Trail(List.of(ingress.body().key()));   // its seed: the delivery made the ingress durable (D-058)
                try {
                    trail.apply(pipeline, ingress);
                } finally {   // an Error too: a crashed run's bodies and rows are kept, and a rerun re-records what is missing
                    trail.close().toCompletionStage().toCompletableFuture().join();   // never fails; waits through an interrupt
                }
            }
            return Verdict.COMPLETE;
        } catch (ParkedException parked) {
            return Verdict.COMPLETE;   // the DLQ row is the failure's record
        } catch (Throwable failure) {
            if (exhausted && !(failure instanceof FencedException)) return Verdict.hold(failure);   // fenced: not a failed park, never held
            LOG.log(System.Logger.Level.WARNING, "message " + id + " of flow " + flow + " failed at worker " + workerId + " (attempt " + item.attempts()
                    + " of " + policy.maxAttempts() + ") and is left to its lease (" + policy.lease() + "): " + failure, failure);
            return Verdict.LEAVE;
        }
    }

    /**
     * {@code node} told this flow has work, after the commit that made it so: a throw is logged at WARNING and changes no answer — the
     * row stays for that node's reconciliation claim.
     */
    private void announce(String node) {
        try {
            signals.announce(node, flow);
        } catch (RuntimeException | Error thrown) {
            LOG.log(System.Logger.Level.WARNING, "announcing flow " + flow + " to node " + node + " failed; its reconciliation claim finds the row: "
                    + thrown, thrown);
        }
    }

    /**
     * Completes, holds or leaves the item as the run decided, fenced by the lease's token — the attempts of this worker's newest
     * claim of it; an item without a partition key completes by {@link WorkQueue#completeUnkeyed} (a batch, D-122), a keyed one
     * by {@link WorkQueue#completeRow}; a complete or hold answering false is stale and counted. A complete naming the node of its key's next row
     * announces it there ({@link WorkSignals}, D-121 (b) B4 (i)), the delete committed. An unparkable item raises one ERROR whatever its hold
     * answers. An item left — by the run, or by a complete or hold that failed — is left to its lease.
     */
    private Future<Void> settle(Lease lease, Verdict verdict) {
        WorkQueue.Item item = lease.item;
        int token = lease.token();
        if (verdict.complete()) return (item.partitionKey() == null ? queue.completeUnkeyed(item.seq(), token)
                : queue.completeRow(item.seq(), token)).transform(removed -> {
            if (removed.failed()) {
                leftToLease();
                return Future.failedFuture(removed.cause());
            }
            if (!removed.result().completed()) stale(item, token);
            removed.result().nextNode().ifPresent(this::announce);   // committed: the key's next row is that node's (B4 (i))
            return Future.succeededFuture();
        });
        Throwable unparkable = verdict.unparkable();
        if (unparkable == null) {
            leftToLease();
            return Future.succeededFuture();
        }
        String alert = "message " + item.messageId() + " of flow " + flow + " was claimed " + token + " times (maxAttempts "
                + policy.maxAttempts() + ") and could not be parked: " + unparkable + "; work item " + item.seq();
        return queue.hold(item.seq(), token).transform(held -> {
            if (held.failed()) {
                LOG.log(System.Logger.Level.ERROR, alert + " could not be held either (" + held.cause() + "): it is left to its lease", unparkable);
                leftToLease();
                return Future.failedFuture(held.cause());
            }
            if (held.result()) {
                LOG.log(System.Logger.Level.ERROR, alert + " held for a human (never claimed again, it blocks its key)", unparkable);
            } else {
                staleSettles.incrementAndGet();
                LOG.log(System.Logger.Level.ERROR, alert + " not held: claimed again, held or completed meanwhile (attempt " + token
                        + "), it is left to its holder", unparkable);
            }
            return Future.succeededFuture();
        });
    }

    /** A complete or fast path the queue answered stale: counted, one WARNING, never tried again (D-074). */
    private void stale(WorkQueue.Item item, int token) {
        staleSettles.incrementAndGet();
        LOG.log(System.Logger.Level.WARNING, "message " + item.messageId() + " of flow " + flow + " (work item " + item.seq() + ", attempt " + token
                + ") finished at worker " + workerId + " but was claimed again, held or completed meanwhile; left to its holder, not completed");
    }

    /** A chain's end: out of the running items; a claim while a backlog is known (D-121 A6). */
    private void finished(WorkQueue.Item item) {
        Promise<Void> due;
        boolean claim;
        synchronized (lock) {
            running.remove(item.seq());
            due = closeDue();
            claim = backlog;
        }
        closeProducers(due);
        if (claim) pollFor("after message " + item.messageId() + " finished");
    }

    /**
     * The close to run now: the asked one, once nothing runs, no slot is reserved and no claim is in flight; else null. Under the
     * lock. After the stop no claim starts, no slot is reserved and a fast path keeps its item in the slot until the next one
     * takes it, so that state is final: it is reached once — by the close, a claim's return, a slot's cancel or a chain's end.
     */
    private Promise<Void> closeDue() {
        return closed != null && !claiming && running.isEmpty() && reserved == 0 ? closed : null;
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

    /** Where the renewals of a lease stand: one scheduled or in flight, stopped as lost, or ended with the run. */
    private enum Renewal { RENEWING, LOST, ENDED }

    /**
     * The lease of one running item: renewed every third of the policy's lease, fenced by its token — the attempts of this
     * worker's newest claim of the item, read when a renewal fires — until the run ends or it is lost; a claim adopted later
     * moves the token and resumes a lost lease (Q-1, D-074).
     */
    private final class Lease {
        final WorkQueue.Item item;          // as the run was started with
        private int token;                  // guarded by this
        private Timer.Cancellable next;     // guarded by this
        private Renewal renewal = Renewal.RENEWING;   // guarded by this

        Lease(WorkQueue.Item item) {
            this.item = item;
            this.token = item.attempts();
        }

        synchronized void renewLater() {
            next = timer.schedule(policy.lease().dividedBy(3), this::renew);
        }

        synchronized int token() { return token; }

        /** This worker claimed the item again while it runs: the newer attempts are the token from now on; a lost lease is renewed again. */
        synchronized void adopt(WorkQueue.Item again) {
            token = again.attempts();
            if (renewal == Renewal.LOST) {
                renewal = Renewal.RENEWING;
                renewLater();
            }
        }

        /**
         * The run ended (or never started): nothing is renewed from now on, and the answer of a renewal in flight is ignored; true
         * when it was found lost. No renewal to cancel when the first one could not be armed.
         */
        synchronized boolean stop() {
            boolean lost = renewal == Renewal.LOST;
            renewal = Renewal.ENDED;
            if (next != null) next.cancel();
            return lost;
        }

        private void renew() {
            int asked;
            synchronized (this) {
                if (renewal == Renewal.ENDED) return;   // fired as the run ended
                asked = token;
            }
            queue.renew(item.seq(), asked, policy.lease()).onComplete(answer -> renewed(asked, answer));
        }

        /** A false answer is a lost lease only for the token still current; one for a token an adopted claim superseded is not. */
        private void renewed(int asked, AsyncResult<Boolean> answer) {
            boolean lost;
            synchronized (this) {
                if (renewal == Renewal.ENDED) return;   // the run ended while the renewal was in flight: its answer no longer matters
                lost = answer.succeeded() && !answer.result() && asked == token;
                if (lost) renewal = Renewal.LOST;
                else renewLater();
            }
            if (lost) {
                LOG.log(System.Logger.Level.WARNING, "lease lost: message " + item.messageId() + " of flow " + flow + " (work item " + item.seq() + ", attempt "
                        + asked + ") runs on at worker " + workerId + " but was claimed again, held or completed meanwhile; its lease is no longer renewed");
            } else if (answer.failed()) {
                LOG.log(System.Logger.Level.WARNING, "the lease of message " + item.messageId() + " of flow " + flow + " (work item " + item.seq()
                        + ") could not be renewed; trying again in " + policy.lease().dividedBy(3) + ": " + answer.cause(), answer.cause());
            }
        }
    }
}
