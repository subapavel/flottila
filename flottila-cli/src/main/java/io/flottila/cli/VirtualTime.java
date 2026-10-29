package io.flottila.cli;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * A case's virtual time (D-080). A platform thread of its own, {@code flottila-clock} — a tool thread, not a node executor
 * (F-16) — is the only one that advances the case's {@link FakeClock}, so the timer's tasks run on it: it waits until the world
 * is {@linkplain Quiescence#quiet() quiet}, then stops when the case has settled; else, at the {@link #HORIZON} of flow time, it
 * stops unsettled; else, with nothing scheduled, nothing can ever happen and it stops unsettled at once; else it advances the
 * clock to the timer's next due task (a zero advance runs the tasks due now), never past the horizon, and waits again. A wall
 * guard bounds the whole wait: a world that never turns quiet ends unsettled. The thread spins, it never sleeps.
 */
final class VirtualTime {

    /** The flow time after which a case that has not settled never will: the clock never passes it. */
    static final Duration HORIZON = Duration.ofMinutes(10);

    /**
     * How a case's wait ended.
     *
     * @param settled whether the case settled
     * @param flowTime from the case's start to where the clock stands at the end
     * @param reason what did not settle; empty when the case settled
     */
    record Outcome(boolean settled, Duration flowTime, String reason) {}

    private final FakeClock clock;
    private final FakeTimer timer;
    private final Quiescence quiescence;
    private final Duration wallGuard;
    private final Instant start;

    /**
     * @param clock the case's clock: the case starts where it stands now
     * @param timer the fake timer {@code clock} drives
     * @param wallGuard how long, in wall time, the whole wait may take
     */
    VirtualTime(FakeClock clock, FakeTimer timer, Quiescence quiescence, Duration wallGuard) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.quiescence = Objects.requireNonNull(quiescence, "quiescence");
        this.wallGuard = Objects.requireNonNull(wallGuard, "wallGuard");
        this.start = clock.instant();
    }

    /**
     * Drives the clock on {@code flottila-clock} until the case settled — {@code settled} is asked on quiet samples only — or did
     * not; the calling thread waits for it.
     */
    Outcome settle(BooleanSupplier settled) {
        return CompletableFuture.supplyAsync(() -> drive(settled), task -> Thread.ofPlatform().name("flottila-clock").start(task)).join();
    }

    private Outcome drive(BooleanSupplier settled) {
        long deadline = System.nanoTime() + wallGuard.toNanos();
        while (true) {
            if (System.nanoTime() - deadline > 0) return unsettled("no quiet moment within " + wallGuard.toMillis() + " ms of wall time");
            if (!quiescence.quiet()) {
                Thread.onSpinWait();
                continue;
            }
            if (settled.getAsBoolean()) return new Outcome(true, flowTime(), "");
            Duration flowTime = flowTime();
            if (flowTime.compareTo(HORIZON) >= 0) return unsettled("flow time reached the horizon");
            if (timer.pending() == 0) return unsettled("nothing is scheduled");
            Duration room = HORIZON.minus(flowTime);
            Duration next = timer.nextDelay();
            clock.advance(next.compareTo(room) > 0 ? room : next);
        }
    }

    private Outcome unsettled(String reason) {
        return new Outcome(false, flowTime(), reason);
    }

    private Duration flowTime() {
        return Duration.between(start, clock.instant());
    }
}
