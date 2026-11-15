package io.flottila.core.record;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a step left behind (ARCHITECTURE §8 write path): the envelope as the step handed it on — or as the step was given it
 * when it failed — with the step's number and name, outcome, timing and the body by reference. Never the bytes.
 *
 * <p>A row is keyed by (message, step, attempt): each attempt of a same-id rerun keeps its own rows (FIX-175).
 *
 * @param messageId the message
 * @param flow the flow
 * @param step the step's number (ingress is 0)
 * @param attempt the run of the message this row belongs to, at least 1: a first run's is 1, a rerun's its {@link Envelope#ATTEMPT}
 *                (D-066); a stored row's is its column's, whatever its props say
 * @param stepName the step's name ({@code ingress} for 0)
 * @param outcome how the step ended
 * @param error the failure, present iff {@link Outcome#FAILED}
 * @param startedAt when the step started, on the runtime's clock
 * @param wallNanos the step's wall time, never negative
 * @param cpuNanos the step's CPU time, or {@link #CPU_UNKNOWN}
 * @param headers the envelope's headers
 * @param props the envelope's props
 * @param keys the envelope's business keys (what {@code findByKey} searches)
 * @param bodyHash the body's content hash (dedup, identity)
 * @param bodySize the body's size in bytes
 * @param bodyKey where the body lives; empty when the flow records metadata only
 * @param versions the configuration versions the message ran with ({@code flow} = {@code id@version}, later partner directory and routing table)
 * @since 0.1
 */
public record Snapshot(String messageId, String flow, int step, int attempt, String stepName, Outcome outcome, Optional<String> error, Instant startedAt,
                       long wallNanos, long cpuNanos, Headers headers, Props props, Keys keys, Hash bodyHash, long bodySize,
                       Optional<BlobKey> bodyKey, Map<String, String> versions) {

    /** How a step ended. @since 0.1 */
    public enum Outcome { OK, FILTERED, FAILED }

    /** {@link #cpuNanos()} when the platform cannot measure the current thread's CPU time. */
    public static final long CPU_UNKNOWN = -1L;

    public Snapshot {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(props, "props");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(bodyHash, "bodyHash");
        Objects.requireNonNull(bodyKey, "bodyKey");
        if (step < 0) throw new IllegalArgumentException("step < 0");
        if (wallNanos < 0) throw new IllegalArgumentException("wallNanos < 0");
        if (cpuNanos < CPU_UNKNOWN) throw new IllegalArgumentException("cpuNanos must be a reading or CPU_UNKNOWN, was " + cpuNanos);
        if (bodySize < 0) throw new IllegalArgumentException("bodySize < 0");
        if ((outcome == Outcome.FAILED) != error.isPresent()) throw new IllegalArgumentException("a FAILED snapshot names its error and no other does");
        if (attempt < 1) throw new IllegalArgumentException("attempt must be at least 1, was " + attempt);
        versions = Map.copyOf(versions);
    }

    /**
     * A snapshot whose attempt is its props' {@link Envelope#ATTEMPT}: 1 when absent (a first run carries none, a rerun carries 2 on,
     * D-066); one that is not an int of at least 1 is an IllegalArgumentException.
     *
     * @since 0.1
     */
    public Snapshot(String messageId, String flow, int step, String stepName, Outcome outcome, Optional<String> error, Instant startedAt,
                    long wallNanos, long cpuNanos, Headers headers, Props props, Keys keys, Hash bodyHash, long bodySize,
                    Optional<BlobKey> bodyKey, Map<String, String> versions) {
        this(messageId, flow, step, attemptOf(props), stepName, outcome, error, startedAt, wallNanos, cpuNanos, headers, props, keys, bodyHash, bodySize,
                bodyKey, versions);
    }

    /** {@code props}' attempt; one that is not an int of at least 1 is an IllegalArgumentException. */
    private static int attemptOf(Props props) {
        Object attempt = Objects.requireNonNull(props, "props").asMap().get(Envelope.ATTEMPT.name());
        if (attempt == null) return 1;
        if (!(attempt instanceof Integer k) || k < 1) throw new IllegalArgumentException("attempt must be an int of at least 1, was " + attempt);
        return k;
    }
}
