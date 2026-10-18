package io.flottila.core.dlq;

import io.flottila.api.BlobKey;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.api.StepConfig;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A message parked in the dead letter queue (ARCHITECTURE §6: "final failure → DLQ row (by reference to the body)"):
 * the failed step's input envelope by reference — enough to retry that step without any snapshot — with the step,
 * the error and the versions the message ran with. Never the bytes.
 *
 * @param messageId the message; one row per message
 * @param step the step that failed (1..n; ingress never parks)
 * @param stepName its name
 * @param error the failure, as text
 * @param parkedAt when, on the runtime's clock
 * @param bodyKey where the step's input body lives
 * @since 0.1
 */
public record Parked(String messageId, String flow, int step, String stepName, String error, Instant parkedAt, Headers headers, Props props, Keys keys,
                     Hash bodyHash, long bodySize, BlobKey bodyKey, Map<String, String> versions) {

    public Parked {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(stepName, "stepName");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(parkedAt, "parkedAt");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(props, "props");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(bodyHash, "bodyHash");
        Objects.requireNonNull(bodyKey, "bodyKey");
        if (step < 1) throw new IllegalArgumentException("a parked step is 1 or more (ingress never parks), was " + step);
        if (bodySize < 0) throw new IllegalArgumentException("bodySize < 0");
        versions = Map.copyOf(versions);
    }

    /** The row for {@code failure} of {@code step}, whose input was {@code e}. */
    public static Parked of(Envelope e, StepConfig step, Throwable failure, Instant at, Map<String, String> versions) {
        return new Parked(e.id(), e.flow(), step.index(), step.name(), failure.toString(), at, e.headers(), e.props(), e.keys(),
                e.body().hash(), e.body().size(), e.body().key(), versions);
    }
}
