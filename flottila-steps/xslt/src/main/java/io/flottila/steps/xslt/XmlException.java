package io.flottila.steps.xslt;

import java.util.Objects;

/**
 * A message failed in an XML step: the body is not well-formed XML, an XPath raised a dynamic error, or a
 * transformation failed. Carries the message id; the cause is Saxon's exception with its error code.
 *
 * @since 0.1
 */
public final class XmlException extends RuntimeException {

    private final String messageId;

    public XmlException(String messageId, String what, Throwable cause) {
        super(what + " (message " + messageId + "): " + cause.getMessage(), cause);
        this.messageId = Objects.requireNonNull(messageId, "messageId");
    }

    public String messageId() { return messageId; }
}
