package io.flottila.sim;

import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
import io.vertx.core.Future;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The simulation's claim cap over the world's queue (D-101): a claim asks for at most one item and only the driver's claim
 * reaches the queue — a claim from any other thread (the chain-end poll of a FlowWorker run) answers {@code []} — so a POLL
 * starts at most one run, and only a POLL starts one. Each item handed out — by the driver's claim or by a fast path on the
 * finishing thread — is told to {@code claimed} / {@code taken} before the worker starts its run. Everything else passes through.
 */
final class ClaimCap implements WorkQueue {

    private final WorkQueue queue;
    private final Runs runs;
    private final BiConsumer<Item, String> claimed;
    private final BiConsumer<Item, String> taken;

    ClaimCap(WorkQueue queue, Runs runs, BiConsumer<Item, String> claimed, BiConsumer<Item, String> taken) {
        this.queue = queue;
        this.runs = runs;
        this.claimed = claimed;
        this.taken = taken;
    }

    @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
        return queue.enqueue(messageId, flow, step, partitionKey);
    }

    @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
        if (!runs.onDriver()) return Future.succeededFuture(List.of());
        return queue.claim(workerId, flow, Math.min(max, 1), lease).onSuccess(items -> items.forEach(i -> claimed.accept(i, workerId)));
    }

    @Override public Future<Boolean> complete(long seq, int attempts) {
        return queue.complete(seq, attempts);
    }

    @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
        return queue.completeAndTakeNext(seq, attempts, workerId, lease).onSuccess(c -> c.next().ifPresent(i -> taken.accept(i, workerId)));
    }

    @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return queue.renew(seq, attempts, lease); }

    @Override public Future<Boolean> hold(long seq, int attempts) { return queue.hold(seq, attempts); }

    @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return queue.held(after, limit); }

    @Override public Future<Boolean> release(long seq) { return queue.release(seq); }

    @Override public Future<Boolean> hasItems(String flow) { return queue.hasItems(flow); }
}
