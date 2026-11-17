package dev.rail8.runtime.journal;

/**
 * The journal failed and stopped (fail-stop): a write or a force returned an error, so no channel it
 * failed on is forced again and every append fails with this until a restart's CRC scan recovers the journal. Retryable
 * for the sender (an intake answers 503): the message was not saved. Reads go on.
 *
 * @since 0.1
 */
public final class JournalFailedException extends RuntimeException {

    public JournalFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
