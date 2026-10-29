package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The {@link Intake} of stores that share no transaction (D-058, D-082): the ingress body's persist in its own store
 * ({@link io.flottila.api.BlobStore#persist}) and the ingress snapshot's record in the {@link MessageStore} are asked side by
 * side; once both succeeded, {@code abandoned} is asked and false enqueues the item in the {@link WorkQueue}. The first failure
 * decides at once, without waiting for the other save, and nothing is queued; a store that throws instead of failing its
 * future is the same failed save. The in-memory stores and a future non-Postgres queue save so.
 *
 * @since 0.1
 */
public final class SequentialIntake implements Intake {

    private final MessageStore store;
    private final WorkQueue queue;

    public SequentialIntake(MessageStore store, WorkQueue queue) {
        this.store = Objects.requireNonNull(store, "store");
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    @Override
    public Future<Void> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, BooleanSupplier abandoned) {
        Future<Void> persist;
        try {
            persist = arrived.body().store().persist(arrived.body().key());
        } catch (RuntimeException failure) {   // a blob store that throws instead of failing its future: the same failed save
            persist = Future.failedFuture(failure);
        }
        Future<Void> record;
        try {
            record = store.record(ingress);
        } catch (RuntimeException failure) {   // a store that throws instead of failing its future: the same failed save
            record = Future.failedFuture(failure);
        }
        // the first failure decides at once, without waiting for the other save
        return Future.all(persist, record).compose(saved -> abandoned.getAsBoolean()
                ? Future.failedFuture(new IllegalStateException("the save of " + arrived.id() + " was abandoned before its enqueue: its answer was decided"))
                : queue.enqueue(arrived.id(), flow, 1, partitionKey));
    }
}
