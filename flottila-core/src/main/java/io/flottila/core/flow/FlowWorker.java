package io.flottila.core.flow;

import io.flottila.api.BlobStore;
import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.core.dlq.ParkedException;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs what an at-least-once {@link Delivery} accepted (ARCHITECTURE §4 lifecycle table, D-049 c): {@link #poll()} claims the
 * flow's due items from the {@link WorkQueue}, rebuilds each message's ingress envelope from its {@code ingress} snapshot and
 * the blob store (same id, step 0, headers, props, keys, the body by its recorded key), runs the pipeline without stage 0 —
 * the save recorded it — on one virtual thread per item. It completes an item only when the message is done with (D-049 c):
 * the pipeline succeeded; a step failed and the message was parked (the DLQ row is its record, nothing is logged); or the
 * timeline was read and holds no ingress snapshot (a ghost that can never be rebuilt: logged at WARNING and completed, never
 * a poison loop). Any other failure — the DLQ refusing the park, the store read failing, an {@link Error} — leaves the item
 * to its {@link #LEASE}, logged at WARNING with the message id and the cause: the sender holds a 202 for it, so it runs again
 * when the lease runs out. Production polls on a
 * timer and after each ACK; a worker built later over the same store and queue finishes what an earlier one accepted.
 * Built by {@link FlowBuilder#worker}; it holds only what the builder handed it, never the {@code Runtime}.
 *
 * @since 0.1
 */
public final class FlowWorker {

    /** How long a claimed item stays leased to this worker before another one may take it over. Draft — D-049. */
    public static final Duration LEASE = Duration.ofSeconds(30);

    private static final System.Logger LOG = System.getLogger(FlowWorker.class.getName());

    private final String flow;
    private final String workerId;
    private final int max;
    private final WorkQueue queue;
    private final MessageStore store;
    private final BlobStore blobs;
    private final Pipeline pipeline;

    FlowWorker(String flow, String workerId, int max, WorkQueue queue, MessageStore store, BlobStore blobs, Pipeline pipeline) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.max = max;
        this.queue = Objects.requireNonNull(queue, "queue");
        this.store = Objects.requireNonNull(store, "store");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    /**
     * Claims up to the flow's in-flight request limit of due items and processes them. Completes, once every item claimed is
     * done with — completed in the queue or left to its lease — with the number of items claimed, whatever became of them
     * (0 when nothing was due); fails only when the claim or a completion in the queue fails.
     */
    public Future<Integer> poll() {
        return queue.claim(workerId, flow, max, LEASE).compose(items -> {
            List<Future<Void>> done = new ArrayList<>(items.size());
            for (WorkQueue.Item item : items) done.add(process(item));
            return Future.all(done).map(items.size());
        });
    }

    /** Runs the item's message on a virtual thread of its own; completes the item when the message is done with, else leaves it. */
    private Future<Void> process(WorkQueue.Item item) {
        Promise<Boolean> ran = Promise.promise();
        Thread.ofVirtual().name("flottila-worker-" + workerId + "-" + item.seq()).start(() -> {
            boolean done = false;
            try {
                done = run(item.messageId());
            } catch (ParkedException parked) {
                done = true;   // the DLQ row is the failure's record
            } catch (Throwable failure) {
                LOG.log(System.Logger.Level.WARNING, "message " + item.messageId() + " of flow " + flow + " failed at worker " + workerId
                        + " and is left to its lease (" + LEASE + "): " + failure, failure);
            } finally {
                ran.complete(done);
            }
        });
        return ran.future().compose(done -> done ? queue.complete(item.seq()) : Future.succeededFuture());
    }

    /** Runs the message from its ingress snapshot; {@code true} when it is done with — run, or a ghost without a snapshot. */
    private boolean run(String messageId) {
        Optional<Snapshot> in = store.timeline(messageId).await().stream().filter(s -> Recorder.INGRESS.equals(s.stepName())).findFirst();
        if (in.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "message " + messageId + " of flow " + flow + " has no ingress snapshot to resume from; its item is completed");
            return true;
        }
        pipeline.apply(Replay.rebuild(in.get(), messageId, flow, in.get().props(), blobs));
        return true;
    }
}
