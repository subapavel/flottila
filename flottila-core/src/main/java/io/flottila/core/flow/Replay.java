package io.flottila.core.flow;

import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.IdGenerator;
import io.flottila.api.Pipeline;
import io.flottila.api.Props;
import io.flottila.api.Views;
import io.flottila.core.admission.Admission;
import io.flottila.core.admission.AdmissionRefusedException;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.flow.ReplayRefusedException.Reason;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.core.record.Trail;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Replay from any recorded step (ARCHITECTURE §6 recording, GATE check 7): steps {@code n..end} of the flow run as a
 * <em>new</em> message marked {@link Envelope#REPLAY_OF} on the envelope step {@code n−1} handed on — taken from its
 * snapshot ({@link #from}), or from the DLQ row of the failed step ({@link #retry}), which carries that envelope by
 * reference so a retry needs no snapshot. The new message is a first run: the {@link Envelope#ATTEMPT} of the run that left
 * that envelope is dropped (D-066). It leaves its own snapshots, the first one its input ({@value #REPLAY_INPUT}), through a
 * {@link Trail} of its own: {@link #from} and {@link #retry} return once its step bodies are durable and its rows written.
 * Built by {@link FlowBuilder#replay}.
 *
 * <p>A run is first checked — the step, the message's snapshot or DLQ row, the input body against the flow's byte budget — then
 * takes a permit of the flow's {@link Admission} for one request and its input's bytes, without waiting, and gives it back once
 * its trail closed, whatever the run did (D-067). Every refusal before the run is a {@link ReplayRefusedException} naming its
 * {@link ReplayRefusedException.Reason}, in that order: the step out of range; the message unknown (no DLQ row); not recorded,
 * metadata-only (the parked step beyond the flow); too large; then, with the permit, closed or busy. What the run throws is its
 * outcome. A run blocks on its stores and its trail's close: call it on a virtual thread, never an event loop. {@link #close}
 * refuses new runs and closes the producers the replay's steps send through once the runs in flight ended.
 *
 * @since 0.1
 */
public final class Replay {

    /** The step name of a replay's first snapshot: the input it was given. */
    public static final String REPLAY_INPUT = "replay";

    private final FlowDefinition flow;
    private final IntFunction<Pipeline> tail;
    private final MessageStore store;
    private final Dlq dlq;
    private final BlobStore blobs;
    private final IdGenerator ids;
    private final Admission admission;
    private final Producers producers;
    private final Object lock = new Object();
    private final Promise<Void> drained = Promise.promise();
    private int holds = 1;          // guarded by lock: the replay's own until close, and one per run between its permit and its release
    private boolean closed;         // guarded by lock

    Replay(FlowDefinition flow, IntFunction<Pipeline> tail, MessageStore store, Dlq dlq, BlobStore blobs, IdGenerator ids, Admission admission,
           Producers producers) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.tail = Objects.requireNonNull(tail, "tail");
        this.store = Objects.requireNonNull(store, "store");
        this.dlq = dlq;
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.producers = Objects.requireNonNull(producers, "producers");
    }

    /** {@link #from(String, int, String)} as a new message whose id the runtime's generator mints. */
    public Envelope from(String messageId, int step) {
        return from(messageId, step, ids::next);
    }

    /**
     * Runs steps {@code step..end} on the recorded output of step {@code step − 1} of {@code messageId}, as the new message
     * {@code replayId}; no id is minted.
     *
     * @throws ReplayRefusedException before anything ran (see the class comment for the order)
     */
    public Envelope from(String messageId, int step, String replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return from(messageId, step, () -> replayId);
    }

    private Envelope from(String messageId, int step, IdGenerator replayId) {
        Objects.requireNonNull(messageId, "messageId");
        int n = flow.steps().size();
        if (step < 1 || step > n) throw refused(Reason.STEP_OUT_OF_RANGE, "replay step must be 1.." + n + " for flow '" + flow.id() + "', was " + step);
        List<Snapshot> timeline = store.timeline(messageId).await();
        if (timeline.isEmpty()) throw refused(Reason.UNKNOWN_MESSAGE, "no snapshots for message '" + messageId + "'");
        Optional<Snapshot> recorded = timeline.stream().filter(s -> s.step() == step - 1 && s.outcome() == Snapshot.Outcome.OK).findFirst();
        if (recorded.isEmpty()) {
            throw refused(Reason.NOT_RECORDED, "step " + (step - 1) + " of message " + messageId + " was not recorded (recording level); replay from step " + step + " needs it");
        }
        Snapshot input = recorded.get();
        if (input.bodyKey().isEmpty()) throw refused(Reason.METADATA_ONLY, "step " + (step - 1) + " of message " + messageId + " was recorded metadata-only: no reachable body to replay");
        Admission.Permit permit = admit(input.bodySize());
        try {
            return run(tail.apply(step), rebuild(input, replayId.next(), flow.id(), replayOf(input.props(), messageId), blobs));
        } finally {
            release(permit);
        }
    }

    /** {@link #retry(String, String)} as a new message whose id the runtime's generator mints. */
    public Envelope retry(String messageId) {
        return retry(messageId, ids::next);
    }

    /**
     * Replays the parked step of {@code messageId} on the parked input as the new message {@code replayId}, no id minted; the row
     * is removed once the new message went through — a removal that fails then is a {@link RowNotRemovedException}.
     *
     * @throws ReplayRefusedException before anything ran (see the class comment for the order)
     * @throws IllegalStateException  for a replay built without a DLQ
     */
    public Envelope retry(String messageId, String replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return retry(messageId, () -> replayId);
    }

    private Envelope retry(String messageId, IdGenerator replayId) {
        Objects.requireNonNull(messageId, "messageId");
        if (dlq == null) throw new IllegalStateException("flow '" + flow.id() + "' has no DLQ to retry from");
        Optional<Parked> row = dlq.get(messageId).await();
        if (row.isEmpty()) throw refused(Reason.NO_DLQ_ROW, "no DLQ row for message '" + messageId + "'");
        Parked parked = row.get();
        int n = flow.steps().size();
        if (parked.step() > n) {
            throw refused(Reason.STEP_BEYOND_FLOW, "message " + messageId + " was parked at step " + parked.step() + ", beyond the " + n + " steps of flow '"
                    + flow.id() + "' as deployed now");
        }
        Admission.Permit permit = admit(parked.bodySize());
        Envelope out;
        String id;
        try {
            id = replayId.next();
            Envelope start = new Envelope(id, flow.id(), parked.step() - 1, parked.headers(), replayOf(parked.props(), messageId), parked.keys(),
                    Body.of(blobs, parked.bodyKey(), parked.bodyHash(), parked.bodySize()), Map.of(), new Views());
            out = run(tail.apply(parked.step()), start);
        } finally {
            release(permit);
        }
        try {
            dlq.remove(messageId).await();
        } catch (Exception failed) {   // the store's failure, a checked one thrown sneakily by the await included
            throw new RowNotRemovedException(messageId, id, failed);
        }
        return out;
    }

    /**
     * Refuses new runs from now on (CLOSED) and closes the producers the replay's steps send through once every run in flight
     * ended — at once when none is; each producer once (FIX-30), a failed close logged. Every call returns that close; it never
     * fails.
     */
    public Future<Void> close() {
        boolean first;
        synchronized (lock) {
            first = !closed;
            closed = true;
        }
        if (first) release(null);   // the replay's own hold
        return drained.future().compose(idle -> producers.close());
    }

    /**
     * A run's permit — one request and {@code bytes} of the flow's admission, never waited for — and its hold on the replay's
     * producers; refused when the bytes never fit, the replay is closed, or there is no room now.
     */
    private Admission.Permit admit(long bytes) {
        if (bytes > admission.maxBytes()) {
            throw refused(Reason.TOO_LARGE, "an input of " + bytes + " bytes can never be admitted: flow '" + flow.id() + "' has a byte budget of " + admission.maxBytes());
        }
        synchronized (lock) {
            if (closed) throw refused(Reason.CLOSED, "the replay of flow '" + flow.id() + "' is closed: the flow was replaced or the node is stopping");
            Optional<Admission.Permit> permit = admission.tryAcquire(bytes);
            if (permit.isEmpty()) {
                throw refused(Reason.BUSY, "flow '" + flow.id() + "' has no room for a replay now: " + new AdmissionRefusedException(bytes, admission.inFlightRequests(),
                        admission.inFlightBytes(), admission.maxRequests(), admission.maxBytes()).getMessage());
            }
            holds++;
            return permit.get();
        }
    }

    /** Gives {@code permit} (null: none, the replay's own hold) back, then its hold; the last hold after close drains the replay. */
    private void release(Admission.Permit permit) {
        if (permit != null) permit.close();
        boolean last;
        synchronized (lock) {
            last = --holds == 0;
        }
        if (last) drained.complete();
    }

    private static ReplayRefusedException refused(Reason reason, String message) {
        return new ReplayRefusedException(reason, message);
    }

    /**
     * {@code pipeline} on {@code start} under a {@link Trail} of its own, seeded with {@code start}'s body — durable already: a
     * recorded row's or a parked one's — and returning, or throwing, only once that trail closed (D-064, F-10).
     */
    private static Envelope run(Pipeline pipeline, Envelope start) {
        Trail trail = new Trail(List.of(start.body().key()));
        try {
            return trail.apply(pipeline, start);
        } finally {
            trail.close().toCompletionStage().toCompletableFuture().join();   // never fails; waits through an interrupt
        }
    }

    /**
     * The envelope {@code input} shows, as message {@code id} of {@code flow} with {@code props}: at the snapshot's step, its
     * headers and keys, the body by its recorded key, no attachments, fresh views. What a replay starts from and what the
     * {@link FlowWorker} resumes an at-least-once message from (its {@code ingress} snapshot, same id).
     */
    static Envelope rebuild(Snapshot input, String id, String flow, Props props, BlobStore blobs) {
        return new Envelope(id, flow, input.step(), input.headers(), props, input.keys(),
                Body.of(blobs, input.bodyKey().orElseThrow(), input.bodyHash(), input.bodySize()), Map.of(), new Views());
    }

    /**
     * The props a replay of {@code messageId} starts with: those it was given, marked {@link Envelope#REPLAY_OF}, without the
     * {@link Envelope#ATTEMPT} of the run that left them — a replay is a new message's first run (D-066).
     */
    private static Props replayOf(Props props, String messageId) {
        Map<String, Object> carried = new HashMap<>(props.asMap());
        carried.remove(Envelope.ATTEMPT.name());
        return new Props(carried).with(Envelope.REPLAY_OF, messageId);
    }

}
