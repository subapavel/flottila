package io.flottila.sim;

/** What the workload does in a tick (D-101); explicit codes, append-only. */
public enum Action {
    /** A send to the sync flow: Reply = partner(transform(body)), a retryable Nack exactly when the model's in-flight is the limit. */
    SEND_SYNC(1),
    /** A send to the at-least-once flow under one of the config's keys. */
    SEND_ALO(2),
    /** A Nacked payload sent again some ticks later, under a new id. */
    RESEND(3),
    /** A poll by worker A or B of the at-least-once flow. */
    POLL(4);

    private final int code;

    Action(int code) {
        this.code = code;
    }

    /** The action's explicit code: never its declaration order, never reused. */
    public int code() {
        return code;
    }
}
