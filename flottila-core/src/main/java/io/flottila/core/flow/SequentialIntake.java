package io.flottila.core.flow;

import io.flottila.api.Envelope;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;

import java.util.Objects;
import java.util.Optional;

/**
 * The {@link Intake} of stores that share no transaction (D-058, D-082): the ingress body's persist in its own store
 * ({@link io.flottila.api.BlobStore#persist}) and the ingress snapshot's record in the {@link MessageStore} are asked side by
 * side; once both succeeded, {@code abandoned} is asked and, not complete, enqueues the item in the {@link WorkQueue}. The first failure
 * decides at once, without waiting for the other save, and nothing is queued; a store that throws instead of failing its
 * future is the same failed save. The in-memory stores and a future non-Postgres queue save so. They dedupe nothing (D-084):
 * a save with a dedupe key fails at once, nothing persisted, recorded or queued — every node saves through the Postgres intake.
 * It keeps no body references: {@code keepInput} changes nothing.
 *
 * <p>Given the node's {@link Workers} and id, it hands off (D-121 (a)): just before the enqueue it reserves a slot of the flow's
 * worker; with one the item is enqueued {@linkplain WorkQueue#enqueueLeased leased} to the node and, written leased, run in that
 * slot before the save answers — no claim (a run or give-back that throws is logged at WARNING and the save still answers: its
 * item is queued, one written leased claimed at its lease end; after a failed enqueue it fails with the enqueue's failure); not written leased or the enqueue failed, the slot is given back: a keyed item's key is
 * busy and the key's holder hands it on; an unkeyed item heads its key, so its queue cannot hand off (the {@link WorkQueue}
 * default) and the flow's worker is {@linkplain Workers#wake woken}, as it is once a plain row is queued without a slot. Without
 * them (the two-argument constructor) every item is a plain row and nobody is told.
 *
 * @since 0.1
 */
public final class SequentialIntake implements Intake {

    private static final System.Logger LOG = System.getLogger(SequentialIntake.class.getName());

    private final MessageStore store;
    private final WorkQueue queue;
    private final Workers workers;     // null: no hand-off
    private final String workerId;     // the node the hand-offs are leased to, with workers

    /** An intake that never hands off: every item a plain row for a claim. */
    public SequentialIntake(MessageStore store, WorkQueue queue) {
        this.store = Objects.requireNonNull(store, "store");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.workers = null;
        this.workerId = null;
    }

    /** An intake handing its items off to the started workers of {@code workers}, leased to the node {@code workerId}. */
    public SequentialIntake(MessageStore store, WorkQueue queue, Workers workers, String workerId) {
        this.store = Objects.requireNonNull(store, "store");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
    }

    @Override
    public Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, boolean keepInput,
                               Future<?> abandoned) {
        if (dedupeKey != null) {
            return Future.failedFuture(new IllegalStateException("the save of " + arrived.id() + " has a dedupe key, which flow " + flow
                    + "'s intake cannot keep: only the Postgres intake dedupes (D-084)"));
        }
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
        return Future.all(persist, record).compose(saved -> abandoned.isComplete()
                ? Future.<String>failedFuture(new IllegalStateException("the save of " + arrived.id() + " was abandoned before its enqueue: its answer was decided"))
                : enqueue(arrived.id(), flow, partitionKey).map(arrived.id()));
    }

    /** The item: handed off in a slot of the flow's worker, else a plain row and the worker woken (see the class comment). */
    private Future<Void> enqueue(String id, String flow, String partitionKey) {
        Optional<FlowWorker.Slot> reserved = workers == null ? Optional.empty() : workers.reserve(flow);
        if (reserved.isEmpty()) {
            Future<Void> queued = queue.enqueue(id, flow, 1, partitionKey);
            return workers == null ? queued : queued.onSuccess(done -> workers.wake(flow));
        }
        FlowWorker.Slot slot = reserved.get();
        // from a completed future on, so a queue that throws fails the save and gives the slot back
        return Future.<Void>succeededFuture().compose(start -> queue.enqueueLeased(id, flow, 1, partitionKey, workerId, slot.lease())).transform(written -> {
            if (written.failed()) {
                isolated(id, flow, "give back", () -> slot.cancel(false));   // the enqueue's failure answers the save, not the give-back's throw
                return Future.failedFuture(written.cause());
            }
            if (written.result().isPresent()) {
                isolated(id, flow, "run", () -> slot.run(written.result().get()));
            } else {
                isolated(id, flow, "give back", () -> slot.cancel(false));   // a keyed row: its key is busy, the key's holder hands it on
                if (partitionKey == null) workers.wake(flow);   // an unkeyed row heads its key: this queue cannot hand off, a claim takes it
            }
            return Future.succeededFuture();
        });
    }

    /** {@code call} on the slot of message {@code id}: a throw is logged at WARNING, the save answers all the same — as the enqueue decided. */
    private static void isolated(String id, String flow, String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException | Error thrown) {
            LOG.log(System.Logger.Level.WARNING, "the hand-off slot of message " + id + " of flow " + flow + " failed to " + what
                    + "; an item written leased is claimed at its lease end: " + thrown, thrown);
        }
    }
}
