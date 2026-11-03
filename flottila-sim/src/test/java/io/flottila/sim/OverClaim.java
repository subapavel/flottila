package io.flottila.sim;

import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Page;
import io.vertx.core.Future;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Broken variant: every claim reaching the queue hands out one item more than it asked for, so a worker near its limit runs
 * past it (the flow's maxInFlight is each worker's run limit). Built over the public {@link WorkQueue} only; the simulation's
 * claim cap above it still asks for one.
 */
final class OverClaim implements World {

    static final String NAME = "over-claim";

    @Override public String name() { return NAME; }

    @Override public WorkQueue queue(WorkQueue sim, Clock clock) {
        return new WorkQueue() {
            @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) {
                return sim.enqueue(messageId, flow, step, partitionKey);
            }
            @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
                return sim.claim(workerId, flow, max + 1, lease);   // THE DEFECT
            }
            @Override public Future<Boolean> complete(long seq, int attempts) { return sim.complete(seq, attempts); }
            @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
                return sim.completeAndTakeNext(seq, attempts, workerId, lease);
            }
            @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return sim.renew(seq, attempts, lease); }
            @Override public Future<Boolean> hold(long seq, int attempts) { return sim.hold(seq, attempts); }
            @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return sim.held(after, limit); }
            @Override public Future<Boolean> release(long seq) { return sim.release(seq); }
            @Override public Future<Boolean> hasItems(String flow) { return sim.hasItems(flow); }
        };
    }
}
