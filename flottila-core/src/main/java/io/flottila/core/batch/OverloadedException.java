package io.flottila.core.batch;

/**
 * The writer's bounded backlog is full: the caller is told at once instead of waiting, so ingress can
 * answer 503 + Retry-After (ARCHITECTURE §4: overflow surfaces as rejection, never as a dead stream).
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
