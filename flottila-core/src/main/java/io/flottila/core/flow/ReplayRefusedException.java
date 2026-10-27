package io.flottila.core.flow;

import java.util.Objects;

/**
 * A {@link Replay} refused a run before anything of it ran (D-067): no step, no row, no permit kept. An
 * {@link IllegalArgumentException}, so a caller that only knew that type still sees one; its {@link #reason()} is what the admin
 * API answers by. What a run itself throws is its outcome, never this.
 *
 * @since 0.1
 */
public final class ReplayRefusedException extends IllegalArgumentException {

    /** Why the run was refused. */
    public enum Reason {
        /** No snapshot is recorded for the message. */
        UNKNOWN_MESSAGE,
        /** No DLQ row for the message to retry. */
        NO_DLQ_ROW,
        /** The step to replay from is outside {@code 1..steps} of the flow. */
        STEP_OUT_OF_RANGE,
        /** The step before it left no snapshot (recording level). */
        NOT_RECORDED,
        /** The step before it was recorded metadata-only: no body to start from. */
        METADATA_ONLY,
        /** The parked step is beyond the flow deployed now. */
        STEP_BEYOND_FLOW,
        /** The input body is above the flow's in-flight byte budget: it can never be admitted. */
        TOO_LARGE,
        /** The flow's admission has no room for the run now. */
        BUSY,
        /** The replay was closed: it takes no new run. */
        CLOSED
    }

    private final Reason reason;

    ReplayRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() { return reason; }
}
