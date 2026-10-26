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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A flow's consumer handler, per delivery mode (ARCHITECTURE §4 lifecycle table, D-049): what a consumer calls with the
 * ingress envelope and answers its sender by. Every mode first asks the flow's {@link Admission} for the message's request
 * and bytes; without room the answer is a retryable {@link Ack.Nack} before anything runs.
 * <ul>
 * <li><b>sync</b>: the pipeline runs on the caller's (virtual) thread; its result is the {@link Ack.Reply}, its failure fails
 * the returned future with the step's exception as thrown, a checked one included — the consumer maps it (by
 * {@link io.flottila.api.Fault.Kind}). The permit is released when the pipeline has returned or thrown.</li>
 * <li><b>at-least-once</b>: the durable save is this handler's, never a stage (§4, F-2): the ingress snapshot with its
 * payload is recorded — whatever the flow's recording level — then the message is enqueued in the flow's {@link WorkQueue},
 * and only when both are durable is the answer {@link Ack.Accepted}. A save not done within the flow's
 * {@code saveTimeout} is a retryable {@link Ack.Nack}, and a save that completes after that enqueues nothing; a failed save
 * is a retryable Nack too, whether its future fails or the store throws, and so is a timer that refuses to bound the save
 * (nothing is saved then). Nothing of the pipeline runs here: a {@link FlowWorker} runs it. The permit is released when
 * the answer is decided.</li>
 * <li><b>at-most-once</b>: {@link Ack.Accepted} at once; the pipeline runs afterwards on a virtual thread of its own and a
 * failure is logged and dropped — not parked, not queued. The permit is released when the pipeline ends.</li>
 * </ul>
 * {@link #stop(Duration)} (D-056) refuses new deliveries, lets those in flight — from the permit until the permit is released —
 * finish until its deadline on the injected {@link Timer}, then cancels them.
 * Built by {@link FlowBuilder#delivery}; it holds only what the builder handed it, never the {@code Runtime}.
 *
 * @since 0.1
 */
public final class Delivery implements Function<Envelope, Future<Ack>> {

    private static final System.Logger LOG = System.getLogger(Delivery.class.getName());

    /** What a mode does with an admitted message. */
    private interface Mode {
        /**
         * Under the delivery's lock, right after the permit: what the stop's deadline cancels — the flight's thread (the
         * caller's, or a run's own, already started) or its pending answer. Never blocks.
         */
        void board(Envelope e, Flight flight);

        /** After the lock: the answer; the flight {@link Flight#land lands} once, when its answer is decided (at-most-once: when its run ends). */
        Future<Ack> deliver(Envelope e, Flight flight);
    }

    /** One delivery between its permit and its release (D-056): what {@link #stop} waits for and its deadline cancels. */
    private final class Flight {
        private final Admission.Permit permit;
        private final String flow;
        private Thread thread;          // sync, at-most-once: interrupted at the deadline; set under the lock
        private Promise<Ack> answer;    // at-least-once: a retryable Nack at the deadline while still pending; set under the lock

        private Flight(Admission.Permit permit, String flow) {
            this.permit = permit;
            this.flow = flow;
        }

        /**
         * The permit back, once, and the flight out of the stop's sight — the stop done when it was the last. Returns the
         * cancel's retryable Nack when the deadline came while it flew (its thread was interrupted then), else null; from here
         * on the deadline does not touch its thread.
         */
        private Ack.Nack land() {
            permit.close();
            Promise<Void> last = null;
            Timer.Cancellable pending = null;
            Ack.Nack cancel;
            synchronized (lock) {
                flights.remove(this);
                cancel = cancelling ? cancelled(flow, deadline) : null;
                if (stopped != null && flights.isEmpty()) {
                    last = stopped;
                    pending = deadlineTimer;
                }
            }
            if (last != null) {
                pending.cancel();
                last.complete();
            }
            return cancel;
        }
    }

    private final Admission admission;
    private final Timer timer;
    private final Mode mode;
    private final Object lock = new Object();
    private final Set<Flight> flights = new HashSet<>();      // guarded by lock: admitted and not landed
    private Promise<Void> stopped;                             // guarded by lock: null until stop
    private Duration deadline;                                 // guarded by lock
    private Timer.Cancellable deadlineTimer;                   // guarded by lock: scheduled by a stop with deliveries in flight
    private boolean cancelling;                                // guarded by lock: the deadline has come

    private Delivery(Admission admission, Timer timer, Mode mode) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.mode = mode;
    }

    /** Sync request/reply: the pipeline on the caller's thread, its result the reply; {@code timer} runs the stop's deadline. */
    static Delivery sync(Admission admission, Pipeline pipeline, Timer timer) {
        Objects.requireNonNull(pipeline, "pipeline");
        return new Delivery(admission, timer, new Mode() {
            @Override
            public void board(Envelope e, Flight flight) {
                flight.thread = Thread.currentThread();
            }

            @Override
            public Future<Ack> deliver(Envelope e, Flight flight) {
                Envelope reply = null;
                Exception failure = null;
                Ack.Nack cancel;
                try {
                    reply = pipeline.apply(e);
                } catch (Exception thrown) {   // an interrupted await throws InterruptedException, sneakily
                    failure = thrown;
                } finally {
                    cancel = flight.land();
                    if (cancel != null) Thread.interrupted();   // the deadline's interrupt: the caller's thread leaves with its flag clear
                }
                if (failure == null) return Future.succeededFuture(new Ack.Reply(reply));   // a real answer beats a retry
                if (cancel != null) return Future.succeededFuture(cancel);   // whatever the failure's kind
                return Future.failedFuture(failure);
            }
        });
    }

    /**
     * At-most-once: accepted at once, the pipeline afterwards on its own virtual thread, a failure logged and dropped;
     * {@code timer} runs the stop's deadline.
     */
    static Delivery atMostOnce(Admission admission, Pipeline pipeline, Timer timer) {
        Objects.requireNonNull(pipeline, "pipeline");
        return new Delivery(admission, timer, new Mode() {
            @Override
            public void board(Envelope e, Flight flight) {
                // started under the lock: the deadline only ever interrupts a run that is alive
                flight.thread = Thread.ofVirtual().name("flottila-" + e.flow() + "-" + e.id()).start(() -> run(e, flight));
            }

            @Override
            public Future<Ack> deliver(Envelope e, Flight flight) {
                return Future.succeededFuture(new Ack.Accepted(e.id()));
            }

            private void run(Envelope e, Flight flight) {
                Exception dropped = null;
                try {
                    pipeline.apply(e);
                } catch (Exception failure) {   // an interrupted await throws InterruptedException, sneakily
                    dropped = failure;
                } finally {
                    flight.land();   // the run is over: the permit is back before anything is logged
                }
                if (dropped != null) {
                    LOG.log(System.Logger.Level.WARNING, "at-most-once message " + e.id() + " of flow " + e.flow() + " failed and is dropped", dropped);
                }
            }
        });
    }

    /**
     * At-least-once: {@code ingress} gives the snapshot to save (payload kept), {@code store} saves it, {@code queue} takes the
     * item for {@code flow}; {@code timer} bounds the whole save by {@code saveTimeout} and runs the stop's deadline. The item
     * has no partition key: business keys are set by steps, and none has run at the save (D-049 b).
     */
    static Delivery atLeastOnce(Admission admission, String flow, Function<Envelope, Snapshot> ingress, MessageStore store, WorkQueue queue,
                                Timer timer, Duration saveTimeout) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(ingress, "ingress");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(saveTimeout, "saveTimeout");
        return new Delivery(admission, timer, new Mode() {
            @Override
            public void board(Envelope e, Flight flight) {
                flight.answer = Promise.promise();
            }

            @Override
            public Future<Ack> deliver(Envelope e, Flight flight) {
                Promise<Ack> answer = flight.answer;
                answer.future().onComplete(decided -> flight.land());
                // whichever completes the answer first wins — the save, its timeout, the stop's deadline; the others' completion is a no-op
                Timer.Cancellable timeout;
                try {
                    timeout = timer.schedule(saveTimeout, () -> answer.tryComplete(new Ack.Nack("durable save timed out after " + saveTimeout, true)));
                } catch (RuntimeException refused) {   // a timer that refuses (shutting down): nothing saved, the flight lands, the sender retries
                    answer.tryComplete(retryable(refused));
                    return answer.future();
                }
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
                            else answer.tryComplete(retryable(saved.cause()));
                        });
                return answer.future();
            }
        });
    }

    /**
     * Admits {@code e} — its request and its body's bytes — and delivers it by the flow's mode; without room, a retryable Nack;
     * once {@link #stop} was called, a retryable Nack before admission.
     */
    @Override
    public Future<Ack> apply(Envelope e) {
        Objects.requireNonNull(e, "envelope");
        long bytes = e.body().size();
        Flight flight;
        synchronized (lock) {
            if (stopped != null) return Future.succeededFuture(new Ack.Nack("flow " + e.flow() + " is stopping", true));
            Optional<Admission.Permit> permit = admission.tryAcquire(bytes);
            if (permit.isEmpty()) {
                AdmissionRefusedException refused = new AdmissionRefusedException(bytes, admission.inFlightRequests(), admission.inFlightBytes(),
                        admission.maxRequests(), admission.maxBytes());
                return Future.succeededFuture(new Ack.Nack(refused.getMessage(), true));
            }
            flight = new Flight(permit.get(), e.flow());
            flights.add(flight);
            mode.board(e, flight);
        }
        return mode.deliver(e, flight);
    }

    /**
     * Stops the flow's deliveries (D-056): from this call on {@link #apply} answers a retryable Nack before admission; the
     * deliveries in flight finish as usual until {@code deadline} on the injected timer, then are cancelled — a sync or
     * at-most-once run's thread is interrupted (a sync failure after that answers a retryable Nack "cancelled: ..." whatever
     * its kind, a reply still returned is kept), a pending at-least-once answer becomes that Nack (its late save enqueues
     * nothing). A zero deadline cancels at once. The future completes when nothing is in flight any more — at once when
     * nothing is — and the deadline's timer is cancelled then. A second call returns the same future, whatever its deadline.
     *
     * @throws NullPointerException     for a null {@code deadline}
     * @throws IllegalArgumentException for a negative one
     */
    public Future<Void> stop(Duration deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isNegative()) throw new IllegalArgumentException("a stop deadline must not be negative, was " + deadline);
        Promise<Void> stop;
        boolean idle;
        synchronized (lock) {
            if (stopped != null) return stopped.future();
            stop = Promise.promise();
            stopped = stop;
            this.deadline = deadline;
            idle = flights.isEmpty();
            if (!idle) deadlineTimer = timer.schedule(deadline, this::cancelInFlight);
        }
        if (idle) stop.complete();
        else if (deadline.isZero()) cancelInFlight();   // at once, not on the timer's turn (a repeat then interrupts again)
        return stop.future();
    }

    /** The stop's deadline: every sync and at-most-once run in flight is interrupted, every pending at-least-once answer is the cancel's Nack. */
    private void cancelInFlight() {
        List<Flight> inFlight;
        Duration after;
        synchronized (lock) {
            cancelling = true;
            after = deadline;
            inFlight = List.copyOf(flights);
            // under the lock: a flight that landed is out of the set, so no interrupt reaches a thread that left its delivery
            for (Flight f : inFlight) if (f.thread != null) f.thread.interrupt();
        }
        // outside the lock: completing an answer runs the consumer's callbacks, and the flight lands
        for (Flight f : inFlight) if (f.answer != null) f.answer.tryComplete(cancelled(f.flow, after));
    }

    /** A retryable Nack naming {@code failure}: its message, else (none) the failure itself. */
    private static Ack.Nack retryable(Throwable failure) {
        return new Ack.Nack(Objects.toString(failure.getMessage(), failure.toString()), true);
    }

    private static Ack.Nack cancelled(String flow, Duration deadline) {
        return new Ack.Nack("cancelled: flow " + flow + " stopping after " + deadline, true);
    }
}
