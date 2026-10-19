package io.flottila.steps.validate;

import java.util.Objects;

/**
 * A message failed the {@code xsd} step: the body is not well-formed XML or does not conform to the schema.
 * Carries the message id and the step's name; the message is
 * {@code validation failed at step '<name>': <the validator's message>}. Not retryable — the same bytes fail
 * again.
 *
 * @since 0.1
 */
public final class XsdValidationException extends RuntimeException {

    private final String messageId;
    private final String step;

    public XsdValidationException(String messageId, String step, Throwable cause) {
        super("validation failed at step '" + step + "': " + cause.getMessage(), cause);
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.step = Objects.requireNonNull(step, "step");
    }

    public String messageId() { return messageId; }

    /** The step's name from the flow file. */
    public String step() { return step; }
}
