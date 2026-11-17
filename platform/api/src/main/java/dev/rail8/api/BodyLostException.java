package dev.rail8.api;

/**
 * A body an operator gave up: the node that held it was started with {@code RAIL8_JOURNAL_ACCEPT_LOSS}, accepting its
 * loss, so it can never be read again. This is not the miss signal ({@link BlobNotFoundException}: nothing was ever
 * stored, or retention removed it), because the message's rows say a body was stored. It is never retryable: no other
 * node and no later try holds the body. A {@link BlobStore}'s {@code read}, {@code open} and {@code exists} fail with it; a same-id DLQ retry from such
 * a body is refused.
 *
 * @since 0.1
 */
public final class BodyLostException extends RuntimeException {

    private final BlobKey key;

    public BodyLostException(BlobKey key) {
        super("body lost by ACCEPT_LOSS: the body under " + key + " was given up when RAIL8_JOURNAL_ACCEPT_LOSS accepted its loss; it can never be read again");
        this.key = key;
    }

    /** The key whose body was given up. */
    public BlobKey key() { return key; }
}
