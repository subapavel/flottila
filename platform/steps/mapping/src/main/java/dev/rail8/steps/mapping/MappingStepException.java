package dev.rail8.steps.mapping;

import dev.rail8.api.Fault;

import java.util.Objects;

/**
 * A message failed in a {@code mapping} step: the body could not be read ({@link Fault.Kind#MALFORMED}), the output passed
 * the flow's body limit ({@link Fault.Kind#INVALID}), or the run met a data error ({@link Fault.Kind#INTERNAL}: a data error
 * need not come from the input, so the flow retries it). Carries the message id; the text names the step and says what
 * happened.
 *
 * @since 0.1
 */
public final class MappingStepException extends RuntimeException implements Fault {

    private final String messageId;
    private final Kind kind;

    public MappingStepException(String messageId, Kind kind, String what, Throwable cause) {
        super(what + " (message " + messageId + ")", cause);
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String messageId() { return messageId; }

    @Override
    public Kind kind() { return kind; }
}
