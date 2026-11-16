package dev.rail8.core.queue;

/**
 * A restart of a parked message refused because a work row of it is still there (F-57, D-162 (5)): work item {@link #seq()} of
 * message {@link #messageId()} in the restart's flow — a park whose settle has not landed, its row left to its lease — so the
 * restart wrote nothing; once that row settled the restart finds the DLQ row as it was. It becomes a value of the act's answer in
 * FIX-276. The message names both.
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
