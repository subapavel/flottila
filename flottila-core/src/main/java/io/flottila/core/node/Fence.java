package io.flottila.core.node;

import java.util.function.BooleanSupplier;

/**
 * A node's self-fence read as time (D-101 (2), D-113 draft): {@link #openNanos()} is what is left until it closes on the node's
 * monotonic clock, live at each read; it is open exactly while that is positive. It never becomes an {@link java.time.Instant}:
 * the monotonic clock has no wall-clock origin. A sender bounded by it cuts its send once the fence has nothing left.
 *
 * @since 0.1
 */
public interface Fence extends BooleanSupplier {

    /** Nanoseconds left until the fence closes, re-read at each call; zero or less: closed (negative: how long ago). */
    long openNanos();

    /** Open while {@link #openNanos()} is positive. */
    @Override
    default boolean getAsBoolean() {
        return openNanos() > 0;
    }
}
