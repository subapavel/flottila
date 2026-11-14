package io.flottila.core.record;

import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Pipeline;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Wraps a step's stage so it leaves a {@link Snapshot} (ARCHITECTURE §6 recording, §8 write path): the step's own time is
 * measured from its start to the moment it hands an envelope on — for a stage that hands on several, to the first — and the
 * snapshot shows that envelope; a stage that hands nothing on ended the message ({@link Snapshot.Outcome#FILTERED}); one that
 * throws is recorded {@link Snapshot.Outcome#FAILED} with the envelope it was given, and the failure goes on. Each snapshot,
 * with the body it shows, goes to the run's {@link Trail} when one is bound (D-064, F-10): persisted and written at the run's
 * end, or earlier when more than {@value Trail#FLUSH_AT} are buffered — the step whose snapshot crosses that line waits for
 * that flush ({@link Trail}). Without one, the snapshot's kept body is persisted in its own store and the row written after that persist, keeping
 * the key only if it succeeded — handed on and not awaited: no step waits for a persist or a commit.
 *
 * @since 0.1
 */
public final class Recorder {

    private static final System.Logger LOG = System.getLogger(Recorder.class.getName());

    /** The name of step 0: {@code start(policy, INGRESS)} records what arrived. */
    public static final String INGRESS = "ingress";

    private final MessageStore store;
    private final Clock clock;
    private final Meter meter;
    private final Map<String, String> versions;

    public Recorder(MessageStore store, Clock clock, Meter meter, Map<String, String> versions) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meter = Objects.requireNonNull(meter, "meter");
        this.versions = Map.copyOf(versions);
    }

    /** The stage that records the envelope a pipeline starts with (at its own step, under {@code name}) and hands it on unchanged; a start is an end of the timeline like ingress. */
    public Stage start(RecordingPolicy policy, String name) {
        Objects.requireNonNull(name, "name");
        return (e, rest) -> {
            if (policy.records(0, false, Snapshot.Outcome.OK)) keep(startSnapshot(e, name, policy), e.body());
            return rest.apply(e);
        };
    }

    /**
     * The snapshot {@link #start} records for {@code e}: at the envelope's own step, under {@code name}, now, the body's key kept
     * as {@code policy} says. Also what an at-least-once delivery saves and awaits before its ACK (F-2, D-049): the same row,
     * written by the consumer's handler with the payload kept whatever the flow's recording level. It times nothing: wall 0 and
     * CPU {@link Snapshot#CPU_UNKNOWN}, never a measured-looking 0 (F-21).
     */
    public Snapshot startSnapshot(Envelope e, String name, RecordingPolicy policy) {
        Objects.requireNonNull(name, "name");
        return snapshot(e, e.step(), name, Snapshot.Outcome.OK, null, clock.instant(), 0, Snapshot.CPU_UNKNOWN, policy);
    }

    /** {@code stage} (already numbered) recorded under {@code policy}; {@code last} marks the flow's final step. */
    public Stage wrap(Stage stage, StepConfig step, boolean last, RecordingPolicy policy) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(policy, "policy");
        if (policy.level() == RecordingPolicy.Level.OFF) return stage;
        return (e, rest) -> {
            Instant started = clock.instant();
            long cpu0 = meter.cpuNanos();
            boolean[] handedOn = {false};
            Envelope result;
            try {
                result = stage.apply(e, out -> {
                    if (!handedOn[0]) {
                        handedOn[0] = true;
                        if (policy.records(step.index(), last, Snapshot.Outcome.OK)) record(out, step, Snapshot.Outcome.OK, null, started, cpu0, policy);
                    }
                    return rest.apply(out);
                });
            } catch (Throwable failure) {   // a checked one too: an interrupted await throws InterruptedException sneakily (D-056); an Error too, recorded and rethrown unchanged (D-111)
                if (!handedOn[0] && policy.records(step.index(), last, Snapshot.Outcome.FAILED)) {
                    try {
                        record(e, step, Snapshot.Outcome.FAILED, failure.toString(), started, cpu0, policy);
                    } catch (Throwable recordingFailure) {   // the original is what leaves, whatever recording did (D-111); a throwable cannot suppress itself
                        if (recordingFailure != failure) failure.addSuppressed(recordingFailure);
                        // The recording failure is deliberately NOT passed to the logger: a handler would have to format it (toString, stack), and
                        // formatting is what threw in the first place; nothing in this catch may be able to replace the original. The cost: this
                        // WARNING carries neither its message nor its stack, only its class, the message id and the step. A log handler that
                        // throws is FIX-127's decision (contained node-wide at the logging setup), not guarded here.
                        LOG.log(System.Logger.Level.WARNING, "Recorder: the FAILED row of message " + e.id() + " at step '" + step.name()
                                + "' was not recorded (" + recordingFailure.getClass().getName() + "); the step's own failure goes on unchanged");
                    }
                }
                throw failure;
            }
            if (!handedOn[0] && policy.records(step.index(), last, Snapshot.Outcome.FILTERED)) record(result, step, Snapshot.Outcome.FILTERED, null, started, cpu0, policy);
            return result;
        };
    }

    private void record(Envelope e, StepConfig step, Snapshot.Outcome outcome, String error, Instant started, long cpu0, RecordingPolicy policy) {
        long wall = Math.max(0, Duration.between(started, clock.instant()).toNanos());
        long cpu1 = meter.cpuNanos();
        long cpu = cpu0 == Snapshot.CPU_UNKNOWN || cpu1 == Snapshot.CPU_UNKNOWN ? Snapshot.CPU_UNKNOWN : Math.max(0, cpu1 - cpu0);
        keep(snapshot(e, step.index(), step.name(), outcome, error, started, wall, cpu, policy), e.body());
    }

    /** {@code s}, showing {@code body}, to the run's trail; without one, to a trail of its own, closed at once and not awaited. */
    private void keep(Snapshot s, Body body) {
        Optional<Trail> run = Trail.current();
        Trail trail = run.orElseGet(() -> new Trail(List.of()));
        trail.add(store, s, body);
        if (run.isEmpty()) trail.close();
    }

    private Snapshot snapshot(Envelope e, int step, String name, Snapshot.Outcome outcome, String error, Instant started, long wall, long cpu, RecordingPolicy policy) {
        return new Snapshot(e.id(), e.flow(), step, name, outcome, Optional.ofNullable(error), started, wall, cpu, e.headers(), e.props(), e.keys(),
                e.body().hash(), e.body().size(), policy.keepsBody() ? Optional.of(e.body().key()) : Optional.empty(), versions);
    }
}
