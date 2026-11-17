package dev.rail8.steps.jq;

import dev.rail8.api.Fault;

import java.util.Objects;

/**
 * A message failed in a {@code jq} step: the body is not one JSON document ({@link Fault.Kind#MALFORMED}), or the filter
 * raised an error, gave no result or more than one, or ran out of a budget ({@link Fault.Kind#INTERNAL}: the flow's
 * failure, not the sender's). Carries the message id; the text names the step and says what happened.
 *
 * @since 0.1
 */
public final class JqStepException extends RuntimeException implements Fault {

    private final String messageId;
    private final Kind kind;

    public JqStepException(String messageId, Kind kind, String what, Throwable cause) {
        super(what + " (message " + messageId + ")", cause);
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String messageId() { return messageId; }

    /** {@link Fault.Kind#MALFORMED} when the body could not be read as JSON, {@link Fault.Kind#INTERNAL} otherwise. */
    @Override
    public Kind kind() { return kind; }
}
