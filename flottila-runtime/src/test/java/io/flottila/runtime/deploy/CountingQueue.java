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
 */
public final class CountingQueue implements WorkQueue {

    private final InMemoryWorkQueue inner;
    private final List<String> claims = new CopyOnWriteArrayList<>();
    private final List<String> enqueued = new CopyOnWriteArrayList<>();

    public CountingQueue(Clock clock) {
        this.inner = new InMemoryWorkQueue(clock);
    }

    /** Every claim so far, as {@code "<workerId> <flow>"}. */
    public List<String> claims() { return List.copyOf(claims); }

    /** The items queued and not completed (FIX-30: an item completed by its own success leaves none). */
    public int size() { return inner.size(); }

    /** The flow of every enqueue so far. */
    public List<String> enqueued() { return List.copyOf(enqueued); }

    @Override
    public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        enqueued.add(flow);
        return inner.enqueue(messageId, flow, step, partitionKey);
    }

    @Override
    public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        claims.add(workerId + " " + flow);
        return inner.claim(workerId, flow, max, lease);
    }

    @Override public Future<Void> complete(long seq) { return inner.complete(seq); }

    @Override public Future<Optional<Item>> completeAndTakeNext(long seq, String workerId, Duration lease) { return inner.completeAndTakeNext(seq, workerId, lease); }

    @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return inner.renew(seq, attempts, lease); }

    @Override public Future<Boolean> hold(long seq, int attempts) { return inner.hold(seq, attempts); }
}
