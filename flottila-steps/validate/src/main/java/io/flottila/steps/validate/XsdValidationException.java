package io.flottila.steps.validate;

import io.flottila.api.Fault;

import java.util.Objects;

/**
 * A message failed the {@code xsd} step: the body is not well-formed XML or does not conform to the schema.
 * Carries the message id and the step's name; the message is
 * {@code validation failed at step '<name>': <the validator's message>}. Not retryable — the same bytes fail
 * again. Its {@link Fault.Kind} is decided at the throw site: {@link Fault.Kind#MALFORMED} when the body could not be
 * parsed (not well-formed XML — the sender's malformed input, whichever step meets it first), {@link Fault.Kind#INVALID}
 * when it was read and broke the schema (D-049 a).
 *
 * @since 0.1
 */
public final class XsdValidationException extends RuntimeException implements Fault {

    private final String messageId;
    private final String step;
    private final Kind kind;

    public XsdValidationException(String messageId, String step, Kind kind, Throwable cause) {
        super("validation failed at step '" + step + "': " + cause.getMessage(), cause);
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.step = Objects.requireNonNull(step, "step");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String messageId() { return messageId; }

    /** The step's name from the flow file. */
    public String step() { return step; }

    /** {@link Fault.Kind#MALFORMED} when the body could not be parsed, {@link Fault.Kind#INVALID} when it broke the schema. */
    @Override
    public Kind kind() { return kind; }
}
