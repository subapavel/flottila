package io.flottila.api;

import java.util.Objects;

/**
 * The consumer answered {@link Ack.Nack}: the message was not accepted, with the consumer's reason, and the send
 * fails with this — a producer never reports a NACK as a delivery. {@link #retryable()} is what the retry policy
 * looks at (a transport maps its own signals onto it, e.g. HTTP 503 and 429). A {@link Fault.Kind#DOWNSTREAM} fault.
 *
 * @since 0.1
 */
public final class NackException extends RuntimeException implements Fault {

    private final String reason;
    private final boolean retryable;

    public NackException(String reason, boolean retryable) {
        super("not accepted (" + (retryable ? "retryable" : "final") + "): " + reason);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.retryable = retryable;
    }

    public String reason() { return reason; }

    public boolean retryable() { return retryable; }

    /** {@link Fault.Kind#DOWNSTREAM}: the receiver said no. */
    @Override
    public Kind kind() { return Kind.DOWNSTREAM; }
}
