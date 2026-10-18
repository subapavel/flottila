package io.flottila.api;

import java.util.Objects;

/**
 * Where a body lives in the {@link BlobStore}: objects are keyed by message id + step (+ attachment
 * name), never by content hash, so retention can delete per message and streaming ingress does not
 * need the hash up front (ARCHITECTURE §5).
 *
 * @param messageId the message the bytes belong to
 * @param step the step after which this body exists (0 = ingress)
 * @param part {@value #BODY} for the main payload, otherwise the attachment name
 * @since 0.1
 */
public record BlobKey(String messageId, int step, String part) {

    /** The part name of the main payload. */
    public static final String BODY = "body";

    public BlobKey {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(part, "part");
        if (step < 0) throw new IllegalArgumentException("step < 0");
    }

    public static BlobKey body(String messageId, int step) {
        return new BlobKey(messageId, step, BODY);
    }

    public static BlobKey attachment(String messageId, int step, String name) {
        return new BlobKey(messageId, step, name);
    }
}
