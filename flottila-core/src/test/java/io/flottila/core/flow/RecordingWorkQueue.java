package io.flottila.core.flow;

import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * A {@link WorkQueue} decorator over an {@link InMemoryWorkQueue} — a mock at the store boundary, never of the worker — that
 * records every call a {@link FlowWorker} makes: each claim with what it asked for, how many claims were in flight when it
 * was issued and what it got; each renew, hold and complete. A test can hold the next claim open ({@link #holdNext()} /
 * {@link #releaseHeld()}: the claim runs against the delegate only on release), fail claims or renewals, or answer renewals
 * {@code false} without touching the delegate. A claim counts as in flight from its call until its answer is recorded, which
 * happens before the caller's callbacks run, so a follow-up claim issued from such a callback sees it as done. One-shot faults
 * stage the store failing once: the next claim answered fails ({@link #failNextClaim}) or throws instead of answering
 * ({@link #throwOnNextClaim}); the next completes or holds fail ({@link #failCompletes}, {@link #failHolds}); holds can be
 * answered without touching the delegate ({@link #answerHolds}).
 *
 * <p>Q-1 (D-074): every complete and fast path also as a {@link CompleteCall} — its seq, the attempts it was fenced by and what
 * it answered ({@code null}: failed) — in {@link #completeCalls()}; {@link #onHold} runs a hook at every hold before it reaches
 * the delegate, so a test can claim the row again in between.
 */
final class RecordingWorkQueue implements WorkQueue {

    /** One claim call; {@link #items} or {@link #failure} once answered. */
    static final class ClaimCall {
        final String workerId;
        final int max;
        final Duration lease;
        final int inFlightBefore;
        final int runningAtCall;
        volatile List<Item> items;
        volatile Throwable failure;

        ClaimCall(String workerId, int max, Duration lease, int inFlightBefore, int runningAtCall) {
            this.workerId = workerId;
            this.max = max;
            this.lease = lease;
            this.inFlightBefore = inFlightBefore;
            this.runningAtCall = runningAtCall;
        }

        @Override public String toString() {
            return "claim(" + workerId + ", max " + max + ", lease " + lease + ", in flight before " + inFlightBefore + ", running " + runningAtCall
                    + ") -> " + (items != null ? items : failure);
        }
    }

    record RenewCall(long seq, int attempts, Duration lease, Instant at, Boolean answer) {}

    record HoldCall(long seq, int attempts, Boolean answer) {}

    /** One complete or fast path: the attempts it was fenced by, and whether it removed the row ({@code null}: it failed). */
    record CompleteCall(long seq, int attempts, Boolean answer) {}

    /** One defer (CORE-14a P3): the token it was fenced by, the delay asked, the clock at the call and its answer ({@code null}: it failed). */
    record DeferCall(long seq, int attempts, Duration delay, Instant at, Boolean answer) {}

    /** One leased enqueue (P2-16a, D-121 A2): what it asked for and what it answered ({@code null}: it failed). */
    record EnqueueLeasedCall(String messageId, String partitionKey, String workerId, Duration lease, Optional<Item> answer) {}

    private record Held(ClaimCall call, String flow, Promise<List<Item>> promise) {}

    private final InMemoryWorkQueue delegate;
    private final Clock clock;
    private final List<ClaimCall> claims = new CopyOnWriteArrayList<>();
    private final List<RenewCall> renews = new CopyOnWriteArrayList<>();
    private final List<HoldCall> holds = new CopyOnWriteArrayList<>();
    private final List<Long> completes = new CopyOnWriteArrayList<>();
    private final List<CompleteCall> completeCalls = new CopyOnWriteArrayList<>();
    private final List<DeferCall> deferCalls = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> nextDeferFailure = new AtomicReference<>();
    private final List<EnqueueLeasedCall> enqueueLeasedCalls = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> nextEnqueueFailure = new AtomicReference<>();
    private final Map<String, Integer> handedOut = new ConcurrentHashMap<>();
    private final List<Held> held = new ArrayList<>();   // guarded by this
    private int inFlight;                                 // guarded by this
    private int peakInFlight;                             // guarded by this
    private boolean holdNext;                             // guarded by this
    private volatile Throwable claimFailure;
    private final AtomicReference<Throwable> nextClaimFailure = new AtomicReference<>();
    private final AtomicReference<RuntimeException> nextClaimThrows = new AtomicReference<>();
    private final Deque<Throwable> completeFailures = new ConcurrentLinkedDeque<>();
    private final Deque<Throwable> holdFailures = new ConcurrentLinkedDeque<>();
    private volatile Boolean holdAnswer;
    private volatile Throwable renewFailure;
    private volatile Boolean renewAnswer;
    private volatile IntSupplier runningProbe = () -> -1;

    RecordingWorkQueue(InMemoryWorkQueue delegate, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // --- staging

    /** The next claim stays open until {@link #releaseHeld()}; it reaches the delegate only then. */
    synchronized void holdNext() { holdNext = true; }

    /** Answers every held claim (against the delegate, now) and completes it; clears a pending {@link #holdNext()}. */
    void releaseHeld() {
        List<Held> now;
        synchronized (this) {
            now = new ArrayList<>(held);
            held.clear();
            holdNext = false;
        }
        for (Held h : now) answer(h.call(), h.flow()).onComplete(h.promise());
    }

    synchronized int heldClaims() { return held.size(); }

    /** From now on every claim fails with {@code cause}, until {@link #claimsOk()}. */
    void failClaims(Throwable cause) { claimFailure = Objects.requireNonNull(cause, "cause"); }

    void claimsOk() { claimFailure = null; }

    /** The next claim to be answered (a held one included, at its release) fails with {@code cause}; later ones answer as before. */
    void failNextClaim(Throwable cause) { nextClaimFailure.set(Objects.requireNonNull(cause, "cause")); }

    /** The next claim call throws {@code thrown} instead of returning a future — recorded, never in flight; later ones answer as before. */
    void throwOnNextClaim(RuntimeException thrown) { nextClaimThrows.set(Objects.requireNonNull(thrown, "thrown")); }

    /** The next {@code times} completes fail with {@code cause} without reaching the delegate (the row stays). */
    void failCompletes(int times, Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        for (int i = 0; i < times; i++) completeFailures.addLast(cause);
    }

    /** The next {@code times} holds fail with {@code cause} without reaching the delegate (nothing is held). */
    void failHolds(int times, Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        for (int i = 0; i < times; i++) holdFailures.addLast(cause);
    }

    /** From now on every hold answers {@code answer} without reaching the delegate; {@code null}: the delegate answers again. */
    void answerHolds(Boolean answer) { holdAnswer = answer; }

    /** From now on every renew fails with {@code cause} (the delegate is not called), until {@link #renewsOk()}. */
    void failRenews(Throwable cause) { renewFailure = Objects.requireNonNull(cause, "cause"); }

    /** From now on every renew answers {@code answer} without reaching the delegate (the lease is not extended), until {@link #renewsOk()}. */
    void answerRenews(boolean answer) { renewAnswer = answer; }

    void renewsOk() {
        renewFailure = null;
        renewAnswer = null;
    }

    /** The next enqueue — plain or leased — fails with {@code cause} without reaching the delegate (nothing queued); later ones answer as before. */
    void failNextEnqueue(Throwable cause) { nextEnqueueFailure.set(Objects.requireNonNull(cause, "cause")); }

    /** The next defer fails with {@code cause} without reaching the delegate (the row stays as it was); later ones answer as before. */
    void failNextDefer(Throwable cause) { nextDeferFailure.set(Objects.requireNonNull(cause, "cause")); }

    /** Sampled at every claim call into {@link ClaimCall#runningAtCall}. */
    void probeRunning(IntSupplier probe) { runningProbe = Objects.requireNonNull(probe, "probe"); }

    // --- what happened

    List<ClaimCall> claims() { return List.copyOf(claims); }

    List<ClaimCall> claimsBy(String workerId) { return claims.stream().filter(c -> c.workerId.equals(workerId)).toList(); }

    List<RenewCall> renews() { return List.copyOf(renews); }

    List<HoldCall> holds() { return List.copyOf(holds); }

    List<Long> completes() { return List.copyOf(completes); }

    /** Every leased enqueue so far, in call order (P2-16a). */
    List<EnqueueLeasedCall> enqueueLeasedCalls() { return List.copyOf(enqueueLeasedCalls); }

    /** Every complete and fast path so far with its attempts and answer, in call order (Q-1). */
    List<CompleteCall> completeCalls() { return List.copyOf(completeCalls); }

    /** Every defer so far with its token, delay, the clock at the call and its answer, in call order (CORE-14a). */
    List<DeferCall> deferCalls() { return List.copyOf(deferCalls); }

    /** Runs at every {@code complete} / {@code completeAndTakeNext} call, before it reaches the delegate: what held at the call (P1-09k2). */
    private volatile java.util.function.LongConsumer onComplete = seq -> { };

    void onComplete(java.util.function.LongConsumer hook) { onComplete = java.util.Objects.requireNonNull(hook, "hook"); }

    /** Runs at every {@code hold} call, before it reaches the delegate (Q-1): what the test changes there, the hold sees. */
    private volatile java.util.function.LongConsumer onHold = seq -> { };

    void onHold(java.util.function.LongConsumer hook) { onHold = java.util.Objects.requireNonNull(hook, "hook"); }

    synchronized int claimsInFlight() { return inFlight; }

    synchronized int peakClaimsInFlight() { return peakInFlight; }

    /** Items handed to {@code workerId} so far, by claims and fast paths. */
    int handedOutTo(String workerId) { return handedOut.getOrDefault(workerId, 0); }

    int handedOutTotal() { return handedOut.values().stream().mapToInt(Integer::intValue).sum(); }

    InMemoryWorkQueue delegate() { return delegate; }

    // --- WorkQueue

    /** The delegate's answer (P1-09d2a): this fixture records nothing about it. */
    @Override
    public Future<Boolean> hasItems(String flow) {
        return delegate.hasItems(flow);
    }

    @Override
    public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        Throwable failure = nextEnqueueFailure.getAndSet(null);
        return failure != null ? Future.failedFuture(failure) : delegate.enqueue(messageId, flow, step, partitionKey);
    }

    /** The delegate's leased enqueue (P2-16a, D-121 A2), recorded with its answer; a staged failure reaches no delegate. */
    @Override
    public Future<Optional<Item>> enqueueLeased(String messageId, String flow, int step, String partitionKey, String workerId, Duration lease) {
        Throwable failure = nextEnqueueFailure.getAndSet(null);
        Future<Optional<Item>> answer = failure != null ? Future.failedFuture(failure)
                : delegate.enqueueLeased(messageId, flow, step, partitionKey, workerId, lease);
        enqueueLeasedCalls.add(new EnqueueLeasedCall(messageId, partitionKey, workerId, lease, answer.succeeded() ? answer.result() : null));
        if (answer.succeeded() && answer.result().isPresent()) handedOut.merge(workerId, 1, Integer::sum);
        return answer;
    }

    @Override
    public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        RuntimeException thrown = nextClaimThrows.getAndSet(null);
        if (thrown != null) {
            ClaimCall call = new ClaimCall(workerId, max, lease, claimsInFlight(), runningProbe.getAsInt());
            call.failure = thrown;
            claims.add(call);
            throw thrown;
        }
        ClaimCall call;
        synchronized (this) {
            call = new ClaimCall(workerId, max, lease, inFlight, runningProbe.getAsInt());
            inFlight++;
            peakInFlight = Math.max(peakInFlight, inFlight);
            claims.add(call);
            if (holdNext) {
                holdNext = false;
                Promise<List<Item>> promise = Promise.promise();
                held.add(new Held(call, flow, promise));
                return promise.future();
            }
        }
        return answer(call, flow);
    }

    /** Runs the claim against the delegate (or fails it) and records the answer before anybody sees it. */
    private Future<List<Item>> answer(ClaimCall call, String flow) {
        Throwable once = nextClaimFailure.getAndSet(null);
        Throwable failure = once != null ? once : claimFailure;
        Future<List<Item>> result = failure != null ? Future.failedFuture(failure) : delegate.claim(call.workerId, flow, call.max, call.lease);
        synchronized (this) {
            if (result.succeeded()) {
                call.items = List.copyOf(result.result());
                handedOut.merge(call.workerId, result.result().size(), Integer::sum);
            } else {
                call.failure = result.cause();
            }
            inFlight--;
        }
        return result;
    }

    @Override
    public Future<Boolean> complete(long seq, int attempts) {
        onComplete.accept(seq);
        completes.add(seq);
        Throwable failure = completeFailures.pollFirst();
        Future<Boolean> answer = failure != null ? Future.failedFuture(failure) : delegate.complete(seq, attempts);
        completeCalls.add(new CompleteCall(seq, attempts, answer.succeeded() ? answer.result() : null));
        return answer;
    }

    @Override
    public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
        onComplete.accept(seq);
        completes.add(seq);
        Future<Completion> done = delegate.completeAndTakeNext(seq, attempts, workerId, lease);
        completeCalls.add(new CompleteCall(seq, attempts, done.succeeded() ? done.result().completed() : null));
        if (done.succeeded() && done.result().next().isPresent()) handedOut.merge(workerId, 1, Integer::sum);
        return done;
    }

    /** The delegate's restart (CORE-14b P8), not recorded. */
    @Override
    public Future<Boolean> restart(String messageId, String flow, String partitionKey, int attempt) {
        return delegate.restart(messageId, flow, partitionKey, attempt);
    }

    /** The delegate's defer (CORE-14a P3), recorded with the clock at the call and its answer; a staged failure reaches no delegate. */
    @Override
    public Future<Boolean> defer(long seq, int attempts, Duration delay) {
        Instant at = clock.instant();
        Throwable failure = nextDeferFailure.getAndSet(null);
        Future<Boolean> answer = failure != null ? Future.failedFuture(failure) : delegate.defer(seq, attempts, delay);
        deferCalls.add(new DeferCall(seq, attempts, delay, at, answer.succeeded() ? answer.result() : null));
        return answer;
    }

    @Override
    public Future<Boolean> renew(long seq, int attempts, Duration lease) {
        Throwable failure = renewFailure;
        Boolean forced = renewAnswer;
        Future<Boolean> answer = failure != null ? Future.failedFuture(failure)
                : forced != null ? Future.succeededFuture(forced)
                : delegate.renew(seq, attempts, lease);
        renews.add(new RenewCall(seq, attempts, lease, clock.instant(), answer.succeeded() ? answer.result() : null));
        return answer;
    }

    @Override
    public Future<Boolean> hold(long seq, int attempts) {
        onHold.accept(seq);
        Throwable failure = holdFailures.pollFirst();
        Boolean forced = holdAnswer;
        Future<Boolean> answer = failure != null ? Future.failedFuture(failure)
                : forced != null ? Future.succeededFuture(forced)
                : delegate.hold(seq, attempts);
        holds.add(new HoldCall(seq, attempts, answer.succeeded() ? answer.result() : null));
        return answer;
    }

    /** The delegate's listing (P1-09c1): this class's own {@code Held} is a held claim, not the queue's held row. */
    @Override
    public Future<io.flottila.core.record.Page<WorkQueue.Held>> held(Optional<String> after, int limit) {
        return delegate.held(after, limit);
    }

    @Override
    public Future<Boolean> release(long seq) {
        return delegate.release(seq);
    }
}
