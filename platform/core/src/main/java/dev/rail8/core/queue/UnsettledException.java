package dev.rail8.core.queue;

/**
 * Thrown when the restart of a parked message is refused because a work row of the message is still there: work item
 * {@link #seq()} of message {@link #messageId()} in the restart's flow, typically a park whose settle has not landed yet and
 * whose row waits for its lease to run out. The restart writes nothing; once that row is settled, a new restart finds the
 * dead-letter row unchanged. The exception's message names both the message id and the seq.
 *
 * @since 0.1
 */
public final class UnsettledException extends RuntimeException {

    private final String messageId;
    private final long seq;

    public UnsettledException(String messageId, long seq) {
        super("the restart of message " + messageId + " is refused: its work item " + seq + " is not settled yet");
        this.messageId = messageId;
        this.seq = seq;
    }

    /** The message whose restart was refused. */
    public String messageId() {
        return messageId;
    }

    /** The seq of the message's work row that is still there. */
    public long seq() {
        return seq;
    }
}
