package dev.rail8.core.batch;

/**
 * Thrown when a writer's bounded backlog is full. A {@code save} fails with it at once instead of waiting, so ingress can
 * answer 503 with Retry-After: overload shows up as a rejection, never as a stalled stream. A {@code saveWaiting} item fails
 * with it when it still waits for room after the writer's {@code maxWait}.
 *
 * @since 0.1
 */
public final class OverloadedException extends RuntimeException {

    private final long capacity;

    public OverloadedException(long capacity) {
        super("writer backlog full (" + capacity + " items pending)");
        this.capacity = capacity;
    }

    /** The backlog capacity that was reached. */
    public long capacity() {
        return capacity;
    }
}
