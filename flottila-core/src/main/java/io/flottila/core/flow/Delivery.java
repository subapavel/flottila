package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.api.Timer;
import io.flottila.core.admission.Admission;
import io.flottila.core.admission.AdmissionRefusedException;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Snapshot;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A flow's consumer handler, per delivery mode (ARCHITECTURE §4 lifecycle table, D-049): what a consumer calls with the
 * ingress envelope and answers its sender by. Every mode first asks the flow's {@link Admission} for the message's request
 * and bytes; without room the answer is a retryable {@link Ack.Nack} before anything runs.
 * <ul>
 * <li><b>sync</b>: the pipeline runs on the caller's (virtual) thread; its result is the {@link Ack.Reply}, its failure fails
 * the returned future with the step's exception as thrown — the consumer maps it (by {@link io.flottila.api.Fault.Kind}).
 * The permit is released when the returned future completes.</li>
 * <li><b>at-least-once</b>: the durable save is this handler's, never a stage (§4, F-2): the ingress snapshot with its
 * payload is recorded — whatever the flow's recording level — then the message is enqueued in the flow's {@link WorkQueue},
 * and only when both are durable is the answer {@link Ack.Accepted}. A save not done within the flow's
 * {@code saveTimeout} is a retryable {@link Ack.Nack}, and a save that completes after that enqueues nothing; a failed save
 * is a retryable Nack too, whether its future fails or the store throws. Nothing of the pipeline runs here: a {@link FlowWorker} runs it. The permit is released when
 * the answer is decided.</li>
 * <li><b>at-most-once</b>: {@link Ack.Accepted} at once; the pipeline runs afterwards on a virtual thread of its own and a
 * failure is logged and dropped — not parked, not queued. The permit is released when the pipeline ends.</li>
 * </ul>
 * Built by {@link FlowBuilder#delivery}; it holds only what the builder handed it, never the {@code Runtime}.
 *
 * @since 0.1
 */
public final class Delivery implements Function<Envelope, Future<Ack>> {

    private static final System.Logger LOG = System.getLogger(Delivery.class.getName());

    /** What a mode does with an admitted message; it releases {@code permit} exactly once. */
    @FunctionalInterface
    private interface Mode {
        Future<Ack> deliver(Envelope e, Admission.Permit permit);
    }

    private final Admission admission;
    private final Mode mode;

    private Delivery(Admission admission, Mode mode) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.mode = mode;
    }

    /** Sync request/reply: the pipeline on the caller's thread, its result the reply. */
    static Delivery sync(Admission admission, Pipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        return new Delivery(admission, (e, permit) -> {
            try {
                return Future.succeededFuture(new Ack.Reply(pipeline.apply(e)));
            } catch (RuntimeException failure) {
                return Future.failedFuture(failure);
            } finally {
                permit.close();
            }
        });
    }

    /** At-most-once: accepted at once, the pipeline afterwards on its own virtual thread, a failure logged and dropped. */
    static Delivery atMostOnce(Admission admission, Pipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        return new Delivery(admission, (e, permit) -> {
            Thread.ofVirtual().name("flottila-" + e.flow() + "-" + e.id()).start(() -> {
                RuntimeException dropped = null;
                try {
                    pipeline.apply(e);
                } catch (RuntimeException failure) {
                    dropped = failure;
                } finally {
                    permit.close();   // the run is over: the permit is back before anything is logged
                }
                if (dropped != null) {
                    LOG.log(System.Logger.Level.WARNING, "at-most-once message " + e.id() + " of flow " + e.flow() + " failed and is dropped", dropped);
                }
            });
            return Future.succeededFuture(new Ack.Accepted(e.id()));
        });
    }

    /**
     * At-least-once: {@code ingress} gives the snapshot to save (payload kept), {@code store} saves it, {@code queue} takes the
     * item for {@code flow}; {@code timer} bounds the whole save by {@code saveTimeout}. The item has no partition key: business
     * keys are set by steps, and none has run at the save (D-049 b).
     */
    static Delivery atLeastOnce(Admission admission, String flow, Function<Envelope, Snapshot> ingress, MessageStore store, WorkQueue queue,
                                Timer timer, Duration saveTimeout) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(ingress, "ingress");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(saveTimeout, "saveTimeout");
        return new Delivery(admission, (e, permit) -> {
            Promise<Ack> answer = Promise.promise();
            answer.future().onComplete(decided -> permit.close());
            // whichever completes the answer first wins; the loser's completion is a no-op
            Timer.Cancellable timeout = timer.schedule(saveTimeout, () -> answer.tryComplete(new Ack.Nack("durable save timed out after " + saveTimeout, true)));
            Future<Void> record;
            try {
                record = store.record(ingress.apply(e));
            } catch (RuntimeException failure) {   // a store that throws instead of failing its future: the same failed save
                record = Future.failedFuture(failure);
            }
            record.compose(recorded -> answer.future().isComplete() ? Future.<Void>succeededFuture() : queue.enqueue(e.id(), flow, 1, null))
                    .onComplete(saved -> {
                        timeout.cancel();
                        if (saved.succeeded()) answer.tryComplete(new Ack.Accepted(e.id()));
                        else answer.tryComplete(new Ack.Nack(Objects.toString(saved.cause().getMessage(), saved.cause().toString()), true));
                    });
            return answer.future();
        });
    }

    /** Admits {@code e} — its request and its body's bytes — and delivers it by the flow's mode; without room, a retryable Nack. */
    @Override
    public Future<Ack> apply(Envelope e) {
        Objects.requireNonNull(e, "envelope");
        long bytes = e.body().size();
        Optional<Admission.Permit> permit = admission.tryAcquire(bytes);
        if (permit.isEmpty()) {
            AdmissionRefusedException refused = new AdmissionRefusedException(bytes, admission.inFlightRequests(), admission.inFlightBytes(),
                    admission.maxRequests(), admission.maxBytes());
            return Future.succeededFuture(new Ack.Nack(refused.getMessage(), true));
        }
        return mode.deliver(e, permit.get());
    }
}
