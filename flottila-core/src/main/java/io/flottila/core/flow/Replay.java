package io.flottila.core.flow;

import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.IdGenerator;
import io.flottila.api.Pipeline;
import io.flottila.api.Views;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Snapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Replay from any recorded step (ARCHITECTURE §6 recording, GATE check 7): steps {@code n..end} of the flow run as a
 * <em>new</em> message marked {@link Envelope#REPLAY_OF} on the envelope step {@code n−1} handed on — taken from its
 * snapshot ({@link #from}), or from the DLQ row of the failed step ({@link #retry}), which carries that envelope by
 * reference so a retry needs no snapshot. The new message leaves its own snapshots, the first one its input
 * ({@value #REPLAY_INPUT}). Built by {@link FlowBuilder#replay}.
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

    Replay(FlowDefinition flow, IntFunction<Pipeline> tail, MessageStore store, Dlq dlq, BlobStore blobs, IdGenerator ids) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.tail = Objects.requireNonNull(tail, "tail");
        this.store = Objects.requireNonNull(store, "store");
        this.dlq = dlq;
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /** Runs steps {@code step..end} on the recorded output of step {@code step − 1} of {@code messageId}, as a new message. */
    public Envelope from(String messageId, int step) {
        Objects.requireNonNull(messageId, "messageId");
        checkStep(step);
        List<Snapshot> timeline = store.timeline(messageId).await();
        if (timeline.isEmpty()) throw new IllegalArgumentException("no snapshots for message '" + messageId + "'");
        Snapshot input = timeline.stream().filter(s -> s.step() == step - 1 && s.outcome() == Snapshot.Outcome.OK).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("step " + (step - 1) + " of message " + messageId + " was not recorded (recording level); replay from step " + step + " needs it"));
        if (input.bodyKey().isEmpty()) throw new IllegalArgumentException("step " + (step - 1) + " of message " + messageId + " was recorded metadata-only: no reachable body to replay");
        Envelope start = new Envelope(ids.next(), flow.id(), step - 1, input.headers(), input.props().with(Envelope.REPLAY_OF, messageId), input.keys(),
                Body.of(blobs, input.bodyKey().get(), input.bodyHash(), input.bodySize()), Map.of(), new Views());
        return tail.apply(step).apply(start);
    }

    /** Replays the parked step of {@code messageId} on the parked input; the row is removed once the new message went through. */
    public Envelope retry(String messageId) {
        Objects.requireNonNull(messageId, "messageId");
        if (dlq == null) throw new IllegalStateException("flow '" + flow.id() + "' has no DLQ to retry from");
        Parked parked = dlq.get(messageId).await().orElseThrow(() -> new IllegalArgumentException("no DLQ row for message '" + messageId + "'"));
        Envelope start = new Envelope(ids.next(), flow.id(), parked.step() - 1, parked.headers(), parked.props().with(Envelope.REPLAY_OF, messageId), parked.keys(),
                Body.of(blobs, parked.bodyKey(), parked.bodyHash(), parked.bodySize()), Map.of(), new Views());
        Envelope out = tail.apply(parked.step()).apply(start);
        dlq.remove(messageId).await();
        return out;
    }

    private void checkStep(int step) {
        int n = flow.steps().size();
        if (step < 1 || step > n) throw new IllegalArgumentException("replay step must be 1.." + n + " for flow '" + flow.id() + "', was " + step);
    }

}
