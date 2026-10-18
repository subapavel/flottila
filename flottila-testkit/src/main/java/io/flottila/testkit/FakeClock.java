package io.flottila.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock that moves only when the test says so. Deadlines, leases and backoff are driven by
 * {@link #advance(Duration)}; time never goes backwards. A view created by {@link #withZone(ZoneId)}
 * shares the instant with the clock it came from.
 *
 * @since 0.1
 */
public final class FakeClock extends Clock {

    private final AtomicReference<Instant> now;
    private final java.util.List<Runnable> listeners;
    private final ZoneId zone;

    private FakeClock(AtomicReference<Instant> now, java.util.List<Runnable> listeners, ZoneId zone) {
        this.now = now;
        this.listeners = listeners;
        this.zone = zone;
    }

    /** A UTC clock standing at {@code start}. */
    public static FakeClock at(Instant start) {
        return new FakeClock(new AtomicReference<>(Objects.requireNonNull(start, "start")),
                new java.util.concurrent.CopyOnWriteArrayList<>(), ZoneOffset.UTC);
    }

    /** Moves the clock forward; a negative duration is refused. Listeners ({@link FakeTimer}) run afterwards. */
    public void advance(Duration by) {
        if (by.isNegative()) throw new IllegalArgumentException("cannot advance by a negative duration: " + by);
        now.updateAndGet(i -> i.plus(by));
        listeners.forEach(Runnable::run);
    }

    /** Jumps to {@code target}; an instant before the current one is refused. Listeners run afterwards. */
    public void set(Instant target) {
        Objects.requireNonNull(target, "target");
        now.updateAndGet(i -> {
            if (target.isBefore(i)) throw new IllegalArgumentException("cannot move the clock back from " + i + " to " + target);
            return target;
        });
        listeners.forEach(Runnable::run);
    }

    /** Called after every {@link #advance} and {@link #set}; shared by all zone views of this clock. */
    public void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override public Instant instant() { return now.get(); }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId newZone) { return new FakeClock(now, listeners, Objects.requireNonNull(newZone, "zone")); }
}
