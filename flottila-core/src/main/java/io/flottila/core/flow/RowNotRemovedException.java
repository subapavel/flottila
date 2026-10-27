package io.flottila.core.flow;

/**
 * A DLQ retry went through as a new message, but the parked row could not be removed after it (D-067): the row stays, the new
 * message's rows are written. Not the run's failure — that one is thrown as the run threw it. The message names both ids; the
 * removal's failure is the cause.
 *
 * @since 0.1
 */
public final class RowNotRemovedException extends RuntimeException {

    RowNotRemovedException(String messageId, String replayId, Throwable failure) {
        super("the retry of " + messageId + " went through as message " + replayId + ", but the DLQ row of " + messageId
                + " could not be removed: " + failure, failure);
    }
}
