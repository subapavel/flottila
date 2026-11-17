package dev.rail8.runtime.journal;

/**
 * An append found no room under {@code RAIL8_JOURNAL_PENDING_BYTES} before its save was abandoned or
 * {@code RAIL8_JOURNAL_APPEND_WAIT} passed; nothing was reserved. Retryable for the sender (503).
 *
 * @since 0.1
 */
public final class JournalBusyException extends RuntimeException {

    public JournalBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
