package io.flottila.steps.xslt;

import io.flottila.api.Fault;

import java.util.Objects;

/**
 * A message failed in an XML step: the body is not well-formed XML, an XPath raised a dynamic error, or a
 * transformation failed. Carries the message id; the cause is Saxon's exception with its error code.
 *
 * <p>Its {@link Fault.Kind} is decided where it is thrown, by one rule: {@link Fault.Kind#MALFORMED} where the step could not
 * parse its <em>input</em> (the body is not well-formed XML), {@link Fault.Kind#INTERNAL} everywhere else — a transformation
 * or an XPath that raised an error is the flow's failure, not the sender's (D-049).
 *
 * @since 0.1
 */
public final class XmlException extends RuntimeException implements Fault {

    private final String messageId;
    private final Kind kind;

    public XmlException(String messageId, Kind kind, String what, Throwable cause) {
        super(what + " (message " + messageId + "): " + cause.getMessage(), cause);
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String messageId() { return messageId; }

    /** {@link Fault.Kind#MALFORMED} when the input could not be parsed, {@link Fault.Kind#INTERNAL} otherwise. */
    @Override
    public Kind kind() { return kind; }
}
