package io.flottila.core.batch;

/**
 * The writer's bounded backlog is full: a {@code save} is told at once instead of waiting, so ingress can
 * answer 503 + Retry-After (ARCHITECTURE §4: overflow surfaces as rejection, never as a dead stream); a
 * {@code saveWaiting} item fails with it when it still waits for room after the writer's {@code maxWait} (D-071).
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
