package io.flottila.core.dlq;

import java.util.Objects;

/**
 * The message was parked in the DLQ, durably, after a step failed: what an at-least-once consumer acknowledges on
 * (the message is kept, not lost). The step's failure is the cause.
 *
 * @since 0.1
 */
public final class ParkedException extends RuntimeException {

    private final transient Parked parked;

    public ParkedException(Parked parked, Throwable failure) {
        super("message " + parked.messageId() + " parked at step " + parked.step() + " (" + parked.stepName() + "): " + parked.error(), failure);
        this.parked = Objects.requireNonNull(parked, "parked");
    }

    public Parked parked() { return parked; }
}
