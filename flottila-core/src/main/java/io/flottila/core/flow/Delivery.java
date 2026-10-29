package io.flottila.core.flow;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Fault;
import io.flottila.api.Pipeline;
import io.flottila.api.Timer;
import io.flottila.core.admission.Admission;
import io.flottila.core.admission.AdmissionRefusedException;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.core.record.Trail;
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
 * {@link io.flottila.api.Fault.Kind}). The answer is returned as soon as the pipeline has returned or thrown; the permit is
 * released once the run's {@link Trail} closed (D-064, F-10).</li>
 * <li><b>at-least-once</b>: the durable save is this handler's, never a stage (§4, F-2), and it is one call to the flow's
 * {@link Intake} (D-082): the ingress body made durable, the ingress snapshot with its payload recorded — whatever the
 * flow's recording level — and the message queued for its worker under its partition key; only when the intake's save
 * succeeded is the answer {@link Ack.Accepted}, naming the id the save answered — an earlier message's when the arrival's
 * dedupe key (D-084, the prop its component names, read on the envelope as it arrived; no value or {@code ""} is no key, one
 * that cannot be read a retryable Nack with nothing saved) was taken by it, logged once at INFO. The partition key
 * (D-075, {@code partitionBy}) is evaluated first, inside the save's window, on the envelope as it arrived: no value or
 * {@code ""} is no key; one over {@value #MAX_PARTITION_KEY_CHARS} characters is a final Nack; a {@link Fault} of kind
 * MALFORMED or INVALID saves the message without a key (its run parks it); any other failure is a retryable Nack — and
 * after either Nack nothing is saved. A save not done within the
 * flow's {@code saveTimeout} is a retryable {@link Ack.Nack}, and from then on the save is {@code abandoned}: one that has
 * not reached its last write queues nothing; a failed save is a retryable Nack at once, whether its future fails or the
 * intake throws, and so is a timer that refuses to bound the save (nothing is saved then). Nothing of the pipeline runs
 * here but the key's expression: a {@link FlowWorker} runs it. The permit is released when the answer is decided.</li>
 * <li><b>at-most-once</b>: {@link Ack.Accepted} at once; the pipeline runs afterwards on a virtual thread of its own and a
 * failure is logged and dropped — not parked, not queued. The permit is released when the pipeline ended and its
 * {@link Trail} closed, before the failure is logged.</li>
 * </ul>
 * A sync or at-most-once run is bound to a {@link Trail} of its own, with no seed: nothing of it was made durable before it
 * ran. Its kept bodies persist and its rows are written in a mid-run flush when more than 256 snapshots
 * are buffered — the step whose snapshot crosses that line waits for that flush, and so does a sync answer behind it — and
 * when it ended. Otherwise the answer waits for no persist: the end-of-run persists and rows are awaited only by the permit
 * release (and, for at-most-once, the landing).
 * {@link #stop(Duration)} (D-056) refuses new deliveries, lets those in flight — from the permit until the permit is released —
 * finish until its deadline on the injected {@link Timer}, then cancels them. {@link #close()} (FIX-30) closes the producers
 * the builder created for its pipeline once that stop has completed.
 * Built by {@link FlowBuilder#delivery}; it holds only what the builder handed it, never the {@code Runtime}.
 *
 * @since 0.1
 */
public final class Delivery implements Function<Envelope, Future<Ack>> {

    private static final System.Logger LOG = System.getLogger(Delivery.class.getName());

    /** The longest partition key an at-least-once delivery enqueues (D-075); a longer one is a final Nack. */
    static final int MAX_PARTITION_KEY_CHARS = 256;

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
        private Thread thread;          // sync, at-most-once: interrupted at the deadline; set under the lock, null once it left (leave)
        private Promise<Ack> answer;    // at-least-once: a retryable Nack at the deadline while still pending; set under the lock

        private Flight(Admission.Permit permit, String flow) {
            this.permit = permit;
            this.flow = flow;
        }

        /**
         * The flight's thread leaves its delivery: from here on the deadline does not touch it. Returns the cancel's retryable
         * Nack when the deadline came while it flew (its thread was interrupted then), else null.
         */
        private Ack.Nack leave() {
            synchronized (lock) {
                thread = null;
                return cancelling ? cancelled(flow, deadline) : null;
            }
        }

        /** The permit back, once, and the flight out of the stop's sight — the stop done when it was the last. */
        private void land() {
            permit.close();
            Promise<Void> last = null;
            Timer.Cancellable pending = null;
            synchronized (lock) {
                flights.remove(this);
                if (stopped != null && flights.isEmpty()) {
                    last = stopped;
                    pending = deadlineTimer;
                }
            }
            if (last != null) {
                pending.cancel();
                last.complete();
            }
        }
    }

    private final Admission admission;
    private final Timer timer;
    private final Producers producers;
    private final Mode mode;
    private final Object lock = new Object();
    private final Set<Flight> flights = new HashSet<>();      // guarded by lock: admitted and not landed
    private Promise<Void> stopped;                             // guarded by lock: null until stop
    private Duration deadline;                                 // guarded by lock
    private Timer.Cancellable deadlineTimer;                   // guarded by lock: scheduled by a stop with deliveries in flight
    private boolean cancelling;                                // guarded by lock: the deadline has come

    private Delivery(Admission admission, Timer timer, Producers producers, Mode mode) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.producers = Objects.requireNonNull(producers, "producers");
        this.mode = mode;
    }

    /** The flow's admission this delivery admits by: a {@link Replay} built beside it shares it ({@link FlowBuilder#replay}). */
    Admission admission() {
        return admission;
    }

    /** {@link #sync(Admission, Pipeline, Producers, Timer)} over a pipeline that owns no producer. */
    static Delivery sync(Admission admission, Pipeline pipeline, Timer timer) {
        return sync(admission, pipeline, Producers.NONE, timer);
    }

    /**
     * Sync request/reply: the pipeline on the caller's thread, its result the reply; {@code timer} runs the stop's deadline,
     * {@code producers} are the pipeline's, closed by {@link #close()}.
     */
    static Delivery sync(Admission admission, Pipeline pipeline, Producers producers, Timer timer) {
        Objects.requireNonNull(pipeline, "pipeline");
        return new Delivery(admission, timer, producers, new Mode() {
            @Override
            public void board(Envelope e, Flight flight) {
                flight.thread = Thread.currentThread();
            }

            @Override
            public Future<Ack> deliver(Envelope e, Flight flight) {
                Trail trail = new Trail(List.of());
                Envelope reply = null;
                Exception failure = null;
                Ack.Nack cancel;
                try {
                    reply = trail.apply(pipeline, e);
                } catch (Exception thrown) {   // an interrupted await throws InterruptedException, sneakily
                    failure = thrown;
                } finally {
                    cancel = flight.leave();
                    if (cancel != null) Thread.interrupted();   // the deadline's interrupt: the caller's thread leaves with its flag clear
                    trail.close().onComplete(closed -> flight.land());   // the answer does not wait for the run's bodies, the permit does
                }
                if (failure == null) return Future.succeededFuture(new Ack.Reply(reply));   // a real answer beats a retry
                if (cancel != null) return Future.succeededFuture(cancel);   // whatever the failure's kind
                return Future.failedFuture(failure);
            }
        });
    }

    /** {@link #atMostOnce(Admission, Pipeline, Producers, Timer)} over a pipeline that owns no producer. */
    static Delivery atMostOnce(Admission admission, Pipeline pipeline, Timer timer) {
        return atMostOnce(admission, pipeline, Producers.NONE, timer);
    }

    /**
     * At-most-once: accepted at once, the pipeline afterwards on its own virtual thread, a failure logged and dropped;
     * {@code timer} runs the stop's deadline, {@code producers} are the pipeline's, closed by {@link #close()}.
     */
    static Delivery atMostOnce(Admission admission, Pipeline pipeline, Producers producers, Timer timer) {
        Objects.requireNonNull(pipeline, "pipeline");
        return new Delivery(admission, timer, producers, new Mode() {
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
                Trail trail = new Trail(List.of());
                Exception dropped = null;
                try {
                    trail.apply(pipeline, e);
                } catch (Exception failure) {   // an interrupted await throws InterruptedException, sneakily
                    dropped = failure;
                } finally {
                    flight.leave();
                    landAfter(trail.close(), flight, e, dropped);
                }
            }

            /** The run is over once its trail closed: the permit is back before anything is logged. */
            private void landAfter(Future<Void> closed, Flight flight, Envelope e, Exception dropped) {
                closed.onComplete(done -> {
                    flight.land();
                    if (dropped != null) {
                        LOG.log(System.Logger.Level.WARNING, "at-most-once message " + e.id() + " of flow " + e.flow() + " failed and is dropped", dropped);
                    }
                });
            }
        });
    }

    /** {@link #atLeastOnce(Admission, String, Function, Function, MessageStore, WorkQueue, Timer, Duration)} without a partition key. */
    static Delivery atLeastOnce(Admission admission, String flow, Function<Envelope, Snapshot> ingress, MessageStore store, WorkQueue queue,
                                Timer timer, Duration saveTimeout) {
        return atLeastOnce(admission, flow, ingress, e -> Optional.empty(), store, queue, timer, saveTimeout);
    }

    /**
     * At-least-once over a {@link SequentialIntake}: the envelope's body is persisted in the body's own store, {@code ingress}
     * gives the snapshot to save (payload kept), {@code store} saves it, {@code queue} takes the item for {@code flow} (see
     * {@link #atLeastOnce(Admission, String, Function, Function, Function, Intake, Timer, Duration)}), without a dedupe key.
     */
    static Delivery atLeastOnce(Admission admission, String flow, Function<Envelope, Snapshot> ingress, Function<Envelope, Optional<String>> partitionKey,
                                MessageStore store, WorkQueue queue, Timer timer, Duration saveTimeout) {
        return atLeastOnce(admission, flow, ingress, partitionKey, e -> Optional.empty(), new SequentialIntake(store, queue), timer, saveTimeout);
    }

    /**
     * At-least-once: {@code intake} saves the envelope as it arrived, with the snapshot {@code ingress} gives (payload kept), for
     * {@code flow} (D-082); {@code timer} bounds the whole save by {@code saveTimeout} and runs the stop's deadline. The item's
     * partition key is what {@code partitionKey} gives the envelope as it arrived, on the caller's thread, once the save timeout
     * was scheduled and before the save is asked (D-075, superseding D-049 b): the flow's {@code partitionBy} extractKey
     * expression, compiled at build time. The save's dedupe key is what {@code dedupeKey} gives that envelope, read just before
     * the save is asked — no value or {@code ""} is none (D-084), a failure to read it (a prop of its name but another type) a
     * retryable Nack with nothing saved — and the Accepted names the id the save answers. The save's
     * {@code abandoned} answers whether the answer is decided already — by the save timeout or the stop's deadline.
     */
    static Delivery atLeastOnce(Admission admission, String flow, Function<Envelope, Snapshot> ingress, Function<Envelope, Optional<String>> partitionKey,
                                Function<Envelope, Optional<String>> dedupeKey, Intake intake, Timer timer, Duration saveTimeout) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(ingress, "ingress");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(dedupeKey, "dedupeKey");
        Objects.requireNonNull(intake, "intake");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(saveTimeout, "saveTimeout");
        return new Delivery(admission, timer, Producers.NONE, new Mode() {
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
                answer.future().onComplete(decided -> timeout.cancel());   // whatever decided it: the save, the key, the stop's deadline
                // the partition key first, inside the save's window, on the envelope as it arrived (D-075)
                String key;
                try {
                    key = partitionKey.apply(e).filter(k -> !k.isEmpty()).orElse(null);
                } catch (Throwable failure) {   // an Error too: whatever the key's expression throws is an answer, never an exception out of apply
                    if (!unreadable(failure)) {
                        answer.tryComplete(retryable(failure));
                        return answer.future();
                    }
                    key = null;   // the body cannot be read or breaks a rule: saved without a key, its run parks it
                }
                if (key != null && key.length() > MAX_PARTITION_KEY_CHARS) {
                    answer.tryComplete(new Ack.Nack("partition key of " + key.length() + " characters is over the bound of " + MAX_PARTITION_KEY_CHARS
                            + " characters (D-075)", false));
                    return answer.future();
                }
                // the component's dedupe prop as it arrived (D-084): no value or "" is no key
                String dedupe;
                try {
                    dedupe = dedupeKey.apply(e).filter(k -> !k.isEmpty()).orElse(null);
                } catch (Throwable failure) {   // a prop of the name but another type, an Error too: an answer, never an exception out of apply
                    answer.tryComplete(retryable(failure));
                    return answer.future();
                }
                // the one save (D-082); a save still running once the answer is decided finds itself abandoned and queues nothing
                Future<String> saved;
                try {
                    saved = intake.save(e, ingress.apply(e), flow, key, dedupe, () -> answer.future().isComplete());
                } catch (RuntimeException failure) {   // an intake that throws instead of failing its future: the same failed save
                    saved = Future.failedFuture(failure);
                }
                saved.onComplete(done -> {
                    if (done.failed()) {
                        answer.tryComplete(retryable(done.cause()));
                        return;
                    }
                    String id = done.result();   // the arrival's own, or the message its dedupe key names
                    if (!id.equals(e.id())) {
                        LOG.log(System.Logger.Level.INFO, "message " + e.id() + " of flow " + flow + " was not saved: its dedupe key names message "
                                + id + ", saved earlier; accepted as " + id + " (D-084)");
                    }
                    answer.tryComplete(new Ack.Accepted(id));
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
     * its kind, a reply still returned is kept), a pending at-least-once answer becomes that Nack (its save is abandoned
     * from then on). A zero deadline cancels at once. A sync or at-most-once flight stays in flight until its run's {@link Trail}
     * closed, and the deadline does not cancel that close: a flight whose trail is still closing at the deadline (its run
     * ended before it, or was interrupted by it) is left to close, and stop completes only after that close ends — a close
     * that never ends holds the stop open, with no bound yet (FIX-49). The future completes when nothing is in flight any
     * more — at once when nothing is — and the deadline's timer is cancelled then. A second call returns the same future,
     * whatever its deadline.
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

    /**
     * Closes the producers the builder created for this delivery's pipeline (FIX-30) once its {@link #stop} has completed — no
     * run of the pipeline is left to send through them then — each once: every call waits for the stop and then for the one
     * close of the producers the first call started, so a later call returns a new future over that same close. A producer whose
     * close fails is logged at WARNING and the others close all the same; the future completes, never fails, once every
     * producer's close has settled. An at-least-once delivery owns no producer (its {@link FlowWorker} does).
     *
     * @throws IllegalStateException before {@link #stop}: a close waits for a stop, it never starts one
     */
    public Future<Void> close() {
        Promise<Void> stop;
        synchronized (lock) {
            stop = stopped;
        }
        if (stop == null) throw new IllegalStateException("a delivery is closed after its stop: call stop(deadline) first");
        return stop.future().compose(done -> producers.close());
    }

    /** The stop's deadline: every sync and at-most-once run in flight is interrupted, every pending at-least-once answer is the cancel's Nack. */
    private void cancelInFlight() {
        List<Flight> inFlight;
        Duration after;
        synchronized (lock) {
            cancelling = true;
            after = deadline;
            inFlight = List.copyOf(flights);
            // under the lock: a thread that left its delivery is null, so no interrupt reaches it
            for (Flight f : inFlight) if (f.thread != null) f.thread.interrupt();
        }
        // outside the lock: completing an answer runs the consumer's callbacks, and the flight lands
        for (Flight f : inFlight) if (f.answer != null) f.answer.tryComplete(cancelled(f.flow, after));
    }

    /** The key's expression could not read the body or the body breaks a rule: the message is saved without a key (D-075). */
    private static boolean unreadable(Throwable failure) {
        return failure instanceof Fault f && (f.kind() == Fault.Kind.MALFORMED || f.kind() == Fault.Kind.INVALID);
    }

    /** A retryable Nack naming {@code failure}: its message, else (none) the failure itself. */
    private static Ack.Nack retryable(Throwable failure) {
        return new Ack.Nack(Objects.toString(failure.getMessage(), failure.toString()), true);
    }

    private static Ack.Nack cancelled(String flow, Duration deadline) {
        return new Ack.Nack("cancelled: flow " + flow + " stopping after " + deadline, true);
    }
}
