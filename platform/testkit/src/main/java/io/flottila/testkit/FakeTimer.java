package io.flottila.testkit;

import io.flottila.api.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link Timer} driven by a {@link FakeClock}: a task fires when the clock reaches its due time,
 * never by wall time. Due tasks run in due order after every {@code advance} or {@code set}; a task
 * may schedule further tasks while it runs.
 *
 * @since 0.1
 */
public final class FakeTimer implements Timer {

    private final FakeClock clock;
    private final List<Scheduled> pending = new ArrayList<>();
    private long sequence;

    public FakeTimer(FakeClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        clock.onChange(this::fireDue);
    }

    @Override public synchronized Cancellable schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (delay.isNegative()) throw new IllegalArgumentException("negative delay: " + delay);
        Scheduled s = new Scheduled(clock.instant().plus(delay), sequence++, task);
        pending.add(s);
        return () -> {
            synchronized (this) { return pending.remove(s); }
        };
    }

    /** Tasks scheduled and not yet fired or cancelled. */
    public synchronized int pending() {
        return pending.size();
    }

    /**
     * How far the clock has to advance for the earliest pending task to fire: what a test passes to
     * {@code clock.advance} to drive a backoff, a lease or a deadline without guessing the delay.
     * Zero when it is already due; {@link IllegalStateException} when nothing is pending.
     */
    public synchronized Duration nextDelay() {
        Instant now = clock.instant();
        Instant earliest = pending.stream().map(Scheduled::due).min(Instant::compareTo)
                .orElseThrow(() -> new IllegalStateException("no task is pending"));
        return earliest.isBefore(now) ? Duration.ZERO : Duration.between(now, earliest);
    }

    private void fireDue() {
        while (true) {
            Scheduled next;
            synchronized (this) {
                Instant now = clock.instant();
                next = pending.stream()
                        .filter(s -> !s.due.isAfter(now))
                        .min((a, b) -> a.due.equals(b.due) ? Long.compare(a.seq, b.seq) : a.due.compareTo(b.due))
                        .orElse(null);
                if (next == null) return;
                pending.remove(next);
            }
            next.task.run();
        }
    }

    private record Scheduled(Instant due, long seq, Runnable task) {}
}
