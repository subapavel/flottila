package dev.rail8.core.flow;

import java.util.Objects;

/**
 * A run stopped: its time limit passed, or an operator cancelled its message. Thrown by the step the run was in, or by the
 * next step it would have started; its message is the stop's {@link Runs.Why#text()}, and its cause, when it has one, is what
 * the step was doing failing at the stop (an interrupted wait, say).
 *
 * @since 0.1
 */
public final class RunStoppedException extends RuntimeException {

    private final transient Runs.Why why;

    public RunStoppedException(Runs.Why why, Throwable cause) {
        super(Objects.requireNonNull(why, "why").text(), cause);
        this.why = why;
    }

    /** Why the run stopped. */
    public Runs.Why why() {
        return why;
    }
}
