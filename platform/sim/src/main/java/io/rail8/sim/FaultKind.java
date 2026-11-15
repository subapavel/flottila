package io.rail8.sim;

/** The faults a run injects (D-143); explicit codes, append-only. */
public enum FaultKind {
    /** The partner holds a call 1..8 ticks. */
    DELAY(1),
    /** The partner refuses a call before its effect: retries, then a DLQ row at the flow's first step (step 1, the ingress, D-135). */
    REFUSE(2),
    /** The store refuses the ingress record or the enqueue: a retryable Nack, nothing queued. */
    STORE_REFUSE(3);

    private final int code;

    FaultKind(int code) {
        this.code = code;
    }

    /** The kind's explicit code, part of every per-call draw: never its declaration order, never reused. */
    public int code() {
        return code;
    }
}
