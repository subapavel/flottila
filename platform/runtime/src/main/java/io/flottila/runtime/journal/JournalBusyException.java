package io.flottila.runtime.journal;

/**
 * An append found no room under {@code FLOTTILA_JOURNAL_PENDING_BYTES} before its save was abandoned or
 * {@code FLOTTILA_JOURNAL_APPEND_WAIT} passed (CORE-3 item 3); nothing was reserved. Retryable for the sender (503).
 *
 * @since 0.1
 */
public final class JournalBusyException extends RuntimeException {

    public JournalBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
