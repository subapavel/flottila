package io.flottila.sim;

import io.flottila.api.Envelope;
import io.flottila.core.flow.Intake;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;

import java.util.function.BooleanSupplier;

/**
 * The at-least-once flow's intake as the simulation builds it (D-101): the sequential intake over the store and the queue, with
 * STORE_REFUSE drawn per save — call 0 refuses the ingress record (nothing saved), call 1 the enqueue (the body and the record
 * saved, the save abandoned before its enqueue: nothing queued). Either is a failed save, a retryable Nack.
 */
final class SimIntake implements Intake {

    private final Intake intake;
    private final Faults faults;

    SimIntake(Intake intake, Faults faults) {
        this.intake = intake;
        this.faults = faults;
    }

    @Override
    public Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, BooleanSupplier abandoned) {
        String id = arrived.id();
        if (faults.hit(FaultKind.STORE_REFUSE, id, 1, 0)) {
            return Future.failedFuture(new IllegalStateException("STORE_REFUSE: the store refused the ingress record of " + id));
        }
        boolean enqueueRefused = faults.hit(FaultKind.STORE_REFUSE, id, 1, 1);
        return intake.save(arrived, ingress, flow, partitionKey, dedupeKey, enqueueRefused ? () -> true : abandoned);
    }
}
