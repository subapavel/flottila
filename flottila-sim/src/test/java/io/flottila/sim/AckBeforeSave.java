package io.flottila.sim;

import io.flottila.core.flow.Intake;
import io.vertx.core.Future;

/**
 * Broken variant ACK_BEFORE_SAVE (D-101): the at-least-once intake answers at once with the arrived id and saves nothing — no
 * body persisted, no ingress record, no work item. Built over the public {@link Intake} only.
 */
final class AckBeforeSave implements World {

    static final String NAME = "ack-before-save";

    @Override public String name() { return NAME; }

    @Override public Intake intake(Intake sim) {
        return (arrived, ingress, flow, partitionKey, dedupeKey, abandoned) -> Future.succeededFuture(arrived.id());
    }
}
