package io.flottila.api;

/**
 * A body an operator gave up: the start of the node that held it accepted its loss with {@code FLOTTILA_JOURNAL_ACCEPT_LOSS}
 * (D-105), so it can never be read again. Not the miss signal ({@link BlobNotFoundException}: nothing stored, never written
 * or removed by retention, D-096) — the message's rows say a body was stored — and never retryable: no other node and no
 * later try holds it. A {@link BlobStore}'s {@code read}, {@code open} and {@code exists} fail with it; a message replay, a DLQ
 * replay or a same-id DLQ retry from such a body is refused.
 *
 * @since 0.1
 */
public final class BodyLostException extends RuntimeException {

    private final BlobKey key;

    public BodyLostException(BlobKey key) {
        super("body lost by ACCEPT_LOSS: the body under " + key + " was given up when FLOTTILA_JOURNAL_ACCEPT_LOSS accepted its loss; it can never be read again");
        this.key = key;
    }

    /** The key whose body was given up. */
    public BlobKey key() { return key; }
}
