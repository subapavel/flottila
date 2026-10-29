package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;

import java.util.function.BooleanSupplier;

/**
 * The durable save an at-least-once {@link Delivery} answers by (ARCHITECTURE §4, D-082): the ingress body made durable, the
 * ingress snapshot recorded and the work item queued for step 1 of {@code flow}. Two implementations exist on day 1: core's
 * {@link SequentialIntake} (the body's persist beside the record, then the enqueue) and runtime's Postgres intake (all three in
 * one transaction).
 *
 * @since 0.1
 */
public interface Intake {

    /**
     * Saves {@code arrived} — its body made durable in its own store, {@code ingress} (its payload kept) recorded, the item queued
     * for step 1 of {@code flow} under {@code partitionKey} (null: an unordered item). The future succeeds once all three are
     * durable, with the id the answer names: {@code arrived}'s own. A save that fails queues nothing — except one whose last
     * write (the enqueue, or the commit that holds it) was already sent when it failed: that item may land, as the duplicate
     * D-049 b accepts. {@code abandoned} is asked once, just before that last write, and true queues nothing and fails the
     * future: the delivery's answer was decided meanwhile. A save that fails before that point never asks it.
     *
     * <p>{@code dedupeKey} (D-084; null: none) names what arrived, scoped by {@code flow}: a key an earlier message of
     * {@code flow} was saved under records and queues nothing of {@code arrived} and never asks {@code abandoned} — the
     * future succeeds with that earlier message's id. An intake that cannot dedupe fails a keyed save, never saves it undeduped.
     */
    Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, BooleanSupplier abandoned);
}
