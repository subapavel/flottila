package io.flottila.runtime.journal;

/**
 * The journal has no room for a record under {@code FLOTTILA_JOURNAL_MAX_BYTES} (CORE-3 item 11): an intake whose record would
 * take the journal's bytes past max − max/16, or a step body (a park's included) past max; nothing was reserved or written.
 * Retryable for the sender (an intake answers 503 + Retry-After): the bytes fall once the sweeper removes segments no row pins.
 *
 * @since 0.1
 */
public final class JournalFullException extends RuntimeException {

    public JournalFullException(String message) {
        super(message);
    }
}
