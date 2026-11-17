package dev.rail8.core.flow;

import dev.rail8.api.Envelope;
import dev.rail8.core.record.Snapshot;
import io.vertx.core.Future;

/**
 * The durable save an at-least-once {@link Delivery} answers by: the ingress body made durable, the ingress snapshot recorded
 * and the work item queued for step 1 of {@code flow}. Two implementations exist: core's {@link SequentialIntake} (the body's
 * persist beside the record, then the enqueue) and the runtime's Postgres intake (all three in one transaction; saves made
 * concurrently may share a transaction as a group, each answered on its own, and a member run again after its group failed
 * commits in a transaction of its own).
 *
 * @since 0.1
 */
public interface Intake {

    /**
     * Saves {@code arrived} — its body made durable in its own store, {@code ingress} (its payload kept) recorded, the item queued
     * for step 1 of {@code flow} under {@code partitionKey} (null: an unordered item). The future succeeds once all three are
     * durable, with the id the answer names: {@code arrived}'s own. A save that fails queues nothing, except one whose last
     * write (the enqueue, or the commit that holds it) was already sent when it failed: that item may land, an accepted
     * duplicate of at-least-once delivery. {@code abandoned} is the delivery's answer: complete once that answer is decided (the
     * save timeout's Nack, the stop's deadline). Its {@link Future#isComplete()} is asked just before that last write, and true
     * queues nothing and fails the future; an intake that groups saves asks it also when the save is taken into a
     * group and again once the group's connection came, true there failing the save with nothing sent for it; a save may also end a wait of its own
     * once it completes (an intake waiting for room for its body stops waiting, retryable, nothing saved). A save that fails
     * before the last write may already have asked it.
     *
     * <p>{@code keepInput} says whether the flow keeps its input body beyond its run (the flow's
     * {@code RecordingPolicy.keepsBody()}): an intake with body references keeps one for the ingress body only when true
     * (false: the item names the body, nothing else does); an intake without references ignores it.
     */
    Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, boolean keepInput, Future<?> abandoned);
}
