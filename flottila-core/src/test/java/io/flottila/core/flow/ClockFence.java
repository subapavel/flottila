package io.flottila.core.flow;

import io.flottila.core.node.Fence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * P2-15b: a node fence on the fake clock, as {@code NodeLease} reads it — {@code openNanos()} is what is left until
 * {@link #until} ({@code renewTo} moves it, as a renewed lease does), 0 once {@link #supersede}d — without a membership, so a
 * case sets the fence's end to the instant it needs.
 */
final class ClockFence implements Fence {

    private final Clock clock;
    private volatile Instant until;
    private volatile boolean superseded;

    ClockFence(Clock clock, Instant until) {
        this.clock = clock;
        this.until = until;
    }

    void renewTo(Instant next) { until = next; }

    void supersede() { superseded = true; }

    @Override public long openNanos() {
        return superseded ? 0 : Duration.between(clock.instant(), until).toNanos();
    }
}
