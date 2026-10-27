package io.flottila.runtime.deploy;

import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.vertx.core.Future;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link InMemoryWorkQueue} that records who asked it what: every claim as {@code "<workerId> <flow>"} and every enqueue as
 * the flow it was enqueued for. A worker that polls — on its timer tick, after an accepted message — shows up here as a claim.
 *
 * <p>P1-09d2b: also the message id of every item a claim handed out, the renewals, and every {@code hasItems} call as
 * {@code "<flow> rows=<n>"} with the rows of that flow at the call; {@link #afterFirstHasItems} runs a hook once, right after
 * the first {@code hasItems(flow)} computed its answer and before that answer is returned: what the hook queues arrived after
 * the check.
 */
public final class CountingQueue implements WorkQueue {

    private final InMemoryWorkQueue inner;
    private final List<String> claims = new CopyOnWriteArrayList<>();
    private final List<String> enqueued = new CopyOnWriteArrayList<>();
    private final List<String> claimed = new CopyOnWriteArrayList<>();
    private final List<String> hasItemsCalls = new CopyOnWriteArrayList<>();
    private final java.util.Map<String, Runnable> afterFirstHasItems = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger renews = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Throwable hasItemsFailure;

    public CountingQueue(Clock clock) {
        this.inner = new InMemoryWorkQueue(clock);
    }

    /** Every claim so far, as {@code "<workerId> <flow>"}. */
    public List<String> claims() { return List.copyOf(claims); }

    /** The items queued and not completed (FIX-30: an item completed by its own success leaves none). */
    public int size() { return inner.size(); }

    /** The flow of every enqueue so far. */
    public List<String> enqueued() { return List.copyOf(enqueued); }

    /** The message id of every item a claim handed out, in order: an item claimed twice shows twice. */
    public List<String> claimedIds() { return List.copyOf(claimed); }

    /** The renew calls so far. */
    public int renews() { return renews.get(); }

    /** Every hasItems call, as {@code "<flow> rows=<n>"}: the rows of that flow (queued, leased or held) when it was asked. */
    public List<String> hasItemsCalls() { return List.copyOf(hasItemsCalls); }

    /** Every row queued, in seq order (leased, held or neither). */
    public List<Item> rows() { return inner.snapshot(); }

    /** Runs {@code hook} once, right after the first hasItems({@code flow}) from now on computed its answer, before it returns it. */
    public void afterFirstHasItems(String flow, Runnable hook) { afterFirstHasItems.put(flow, hook); }

    @Override
    public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        enqueued.add(flow);
        return inner.enqueue(messageId, flow, step, partitionKey);
    }

    @Override
    public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        claims.add(workerId + " " + flow);
        Future<List<Item>> items = inner.claim(workerId, flow, max, lease);
        items.onSuccess(taken -> taken.forEach(i -> claimed.add(i.messageId())));   // in-memory: already complete, runs here
        return items;
    }

    @Override public Future<Void> complete(long seq) { return inner.complete(seq); }

    @Override public Future<Optional<Item>> completeAndTakeNext(long seq, String workerId, Duration lease) { return inner.completeAndTakeNext(seq, workerId, lease); }

    @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) {
        renews.incrementAndGet();
        return inner.renew(seq, attempts, lease);
    }

    @Override public Future<Boolean> hold(long seq, int attempts) { return inner.hold(seq, attempts); }

    @Override public Future<io.flottila.core.record.Page<Held>> held(Optional<String> after, int limit) { return inner.held(after, limit); }

    @Override public Future<Boolean> release(long seq) { return inner.release(seq); }

    /** From now on every {@link #hasItems} call (still recorded) fails with {@code failure}; {@code null} answers again. */
    public void failHasItems(Throwable failure) { hasItemsFailure = failure; }

    @Override public Future<Boolean> hasItems(String flow) {
        hasItemsCalls.add(flow + " rows=" + inner.snapshot().stream().filter(i -> i.flow().equals(flow)).count());
        Throwable failure = hasItemsFailure;
        Future<Boolean> answer = failure == null ? inner.hasItems(flow) : Future.failedFuture(failure);
        Runnable hook = afterFirstHasItems.remove(flow);
        if (hook != null) hook.run();
        return answer;
    }
}
