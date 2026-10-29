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
     * durable. A save that fails queues nothing — except one whose last write (the enqueue, or the commit that holds it) was
     * already sent when it failed: that item may land, as the duplicate D-049 b accepts. {@code abandoned} is asked once, just
     * before that last write, and true queues nothing and fails the future: the delivery's answer was decided meanwhile. A
     * save that fails before that point never asks it.
     */
    Future<Void> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, BooleanSupplier abandoned);
}
