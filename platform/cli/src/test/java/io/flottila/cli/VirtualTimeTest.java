package io.flottila.cli;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import jdk.management.VirtualThreadSchedulerMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-03b1 (D-080): a case's virtual time. A platform thread {@code flottila-clock} owns the clock's advances: it waits until the
 * world is quiet, then — settled? stop : flow time at the 10 min horizon? did not settle : advance the clock to the next due task
 * (a zero advance when one is due now). Nothing scheduled while quiet and not settled is stuck: did not settle, at once. A wall
 * guard bounds the whole wait.
 *
 * <p>The type these tests define (package-private in {@code io.flottila.cli}, P2-03b1 test-author within the packet):
 * <ul>
 *   <li>{@code VirtualTime.HORIZON} — {@code Duration.ofMinutes(10)};</li>
 *   <li>{@code new VirtualTime(FakeClock clock, FakeTimer timer, Quiescence quiescence, Duration wallGuard)} — the case's start
 *       is where {@code clock} stands when it is made; {@code timer} is the fake timer {@code clock} drives (its
 *       {@code nextDelay()} / {@code pending()}); the runner passes 60 s;</li>
 *   <li>{@code VirtualTime.Outcome settle(BooleanSupplier settled)} — drives the clock on a platform thread named
 *       {@code flottila-clock} and returns when the case settled ({@code settled} true on a quiet sample) or did not; the caller
 *       waits;</li>
 *   <li>{@code record Outcome(boolean settled, Duration flowTime, String reason)} — {@code flowTime} from the case's start to
 *       where the clock stands at the end; {@code reason} says what did not settle (the report's {@code did not settle: <what>}).</li>
 * </ul>
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VirtualTimeTest {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Duration GUARD = Duration.ofSeconds(60);

    final FakeClock clock = FakeClock.at(T0);
    final FakeTimer timer = new FakeTimer(clock);
    /** Every instant the clock stood at after an advance (zero advances included), in order. */
    final List<Instant> visited = new CopyOnWriteArrayList<>();

    { clock.onChange(() -> visited.add(clock.instant())); }

    static Quiescence idle() {
        return new Quiescence(() -> 0, () -> 0L, () -> "unchanged");
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("not within 10 s: " + what);
            Thread.onSpinWait();
        }
    }

    /**
     * One task 7 min 333 ms out: the clock goes there in one jump — never a fixed step (1 s steps would end at 7 min 1 s), never
     * small steps — the task runs on the platform thread {@code flottila-clock}, and the case settles at that flow time.
     */
    @Test
    void jumpsToTheNextDueTask() {
        Duration due = Duration.ofMinutes(7).plusMillis(333);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        timer.schedule(due, () -> ranOn.set(Thread.currentThread()));
        VirtualTime time = new VirtualTime(clock, timer, idle(), GUARD);

        VirtualTime.Outcome outcome = time.settle(() -> ranOn.get() != null);

        assertTrue(outcome.settled(), outcome.toString());
        assertEquals(due, outcome.flowTime(), outcome.toString());
        assertEquals(T0.plus(due), clock.instant());
        assertEquals(T0.plus(due), visited.getLast(), "visited " + visited);
        assertTrue(visited.stream().allMatch(i -> i.equals(T0) || i.equals(T0.plus(due))), "straight to the task, no step between: " + visited);
        Thread thread = ranOn.get();
        assertNotNull(thread);
        assertEquals("flottila-clock", thread.getName(), "the timer's task runs on the thread that advances the clock");
        assertFalse(thread.isVirtual(), "flottila-clock is a platform thread");
    }

    /**
     * While a virtual thread runs (spins, mounted) the clock stands still, however often the driver samples — the spinning thread
     * itself sees no other instant and the due task not run; once it ends the clock moves to the task and the case settles.
     */
    @Test
    void neverAdvancesWhileAVirtualThreadRuns() throws Exception {
        VirtualThreadSchedulerMXBean scheduler = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
        AtomicInteger samples = new AtomicInteger();
        Quiescence jvm = new Quiescence(() -> {
            samples.incrementAndGet();
            return scheduler.getMountedVirtualThreadCount();
        }, scheduler::getQueuedVirtualThreadCount, () -> "unchanged");
        AtomicBoolean fired = new AtomicBoolean();
        timer.schedule(Duration.ofSeconds(5), () -> fired.set(true));
        AtomicBoolean spinning = new AtomicBoolean();
        AtomicBoolean release = new AtomicBoolean();
        AtomicBoolean movedWhileRunning = new AtomicBoolean();
        Thread spinner = Thread.ofVirtual().name("spinner").start(() -> {
            spinning.set(true);
            while (!release.get()) {
                if (!clock.instant().equals(T0) || fired.get()) movedWhileRunning.set(true);
                Thread.onSpinWait();
            }
        });
        CompletableFuture<VirtualTime.Outcome> settling;
        try {
            until(spinning::get, "the spinner spins");
            VirtualTime time = new VirtualTime(clock, timer, jvm, GUARD);
            settling = CompletableFuture.supplyAsync(() -> time.settle(fired::get), task -> new Thread(task, "case").start());

            until(() -> samples.get() >= 50, "the driver sampled 50 times while the spinner ran");

            assertEquals(T0, clock.instant(), "not advanced while a virtual thread runs");
            assertFalse(fired.get(), "the due task did not run");
            assertFalse(settling.isDone(), "still waiting");
        } finally {
            release.set(true);
            spinner.join();
        }

        VirtualTime.Outcome outcome = settling.get(10, TimeUnit.SECONDS);

        assertFalse(movedWhileRunning.get(), "the spinning thread saw the clock move");
        assertTrue(outcome.settled(), outcome.toString());
        assertEquals(Duration.ofSeconds(5), outcome.flowTime(), outcome.toString());
        assertTrue(fired.get());
    }

    /** A task due now (zero delay) runs by a zero advance: the case settles at flow time zero, the clock where it stood. */
    @Test
    void zeroAdvanceFiresDueTasks() {
        AtomicBoolean fired = new AtomicBoolean();
        timer.schedule(Duration.ZERO, () -> fired.set(true));
        VirtualTime time = new VirtualTime(clock, timer, idle(), GUARD);

        VirtualTime.Outcome outcome = time.settle(fired::get);

        assertTrue(fired.get(), "the due task ran");
        assertTrue(outcome.settled(), outcome.toString());
        assertEquals(Duration.ZERO, outcome.flowTime(), outcome.toString());
        assertEquals(T0, clock.instant());
    }

    /**
     * Quiet, not settled and nothing scheduled: nothing can ever happen — did not settle, at once (not after the wall guard,
     * an hour here), the clock where it stood.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void stuckWithNothingScheduledFails() {
        VirtualTime time = new VirtualTime(clock, timer, idle(), Duration.ofHours(1));

        VirtualTime.Outcome outcome = time.settle(() -> false);

        assertFalse(outcome.settled(), outcome.toString());
        assertEquals(Duration.ZERO, outcome.flowTime(), outcome.toString());
        assertFalse(outcome.reason().isBlank(), "says what did not settle: " + outcome);
        assertEquals(T0, clock.instant());
    }

    /** The settled condition is taken only on a quiet sample: an answer complete while a virtual thread still runs does not end the case. */
    @Test
    void settledIsTakenOnlyWhenQuiet() {
        AtomicInteger mountedCalls = new AtomicInteger();
        Quiescence busyFirst = new Quiescence(() -> mountedCalls.incrementAndGet() <= 20 ? 1 : 0, () -> 0L, () -> "unchanged");
        VirtualTime time = new VirtualTime(clock, timer, busyFirst, GUARD);

        VirtualTime.Outcome outcome = time.settle(() -> true);

        assertTrue(outcome.settled(), outcome.toString());
        assertEquals(Duration.ZERO, outcome.flowTime(), outcome.toString());
        assertTrue(mountedCalls.get() > 21, "settled after " + mountedCalls.get() + " samples; the first 20 had a virtual thread mounted");
    }

    /** The horizon is 10 min of flow time; a task due after it never runs and the clock never passes it: did not settle. */
    @Test
    void aTaskBeyondTheHorizonNeverRuns() {
        assertEquals(Duration.ofMinutes(10), VirtualTime.HORIZON);
        AtomicBoolean fired = new AtomicBoolean();
        timer.schedule(Duration.ofMinutes(11), () -> fired.set(true));
        VirtualTime time = new VirtualTime(clock, timer, idle(), GUARD);

        VirtualTime.Outcome outcome = time.settle(fired::get);

        assertFalse(fired.get(), "the task beyond the horizon ran");
        assertFalse(outcome.settled(), outcome.toString());
        assertFalse(outcome.reason().isBlank(), outcome.toString());
        assertTrue(outcome.flowTime().compareTo(VirtualTime.HORIZON) <= 0, outcome.toString());
        assertFalse(clock.instant().isAfter(T0.plus(VirtualTime.HORIZON)), "the clock passed the horizon: " + clock.instant());
    }

    /**
     * Work that never settles but keeps the timer busy (a 1 s poll, as a mock consumer's round): the clock steps from task to task
     * up to the horizon and stops there — did not settle at flow time exactly 10 min.
     */
    @Test
    void aBusyCaseStopsAtTheHorizon() {
        AtomicInteger rounds = new AtomicInteger();
        Runnable[] round = new Runnable[1];
        round[0] = () -> {
            rounds.incrementAndGet();
            timer.schedule(Duration.ofSeconds(1), round[0]);
        };
        timer.schedule(Duration.ofSeconds(1), round[0]);
        VirtualTime time = new VirtualTime(clock, timer, idle(), GUARD);

        VirtualTime.Outcome outcome = time.settle(() -> false);

        assertFalse(outcome.settled(), outcome.toString());
        assertEquals(VirtualTime.HORIZON, outcome.flowTime(), outcome.toString());
        assertEquals(T0.plus(VirtualTime.HORIZON), clock.instant());
        assertEquals(600, rounds.get(), "one round per second of flow time");
    }

    /** Never quiet (a virtual thread that never stops running): the wall guard ends the case — did not settle, the clock never moved. */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theWallGuardEndsACaseThatIsNeverQuiet() {
        AtomicBoolean fired = new AtomicBoolean();
        timer.schedule(Duration.ofSeconds(1), () -> fired.set(true));
        VirtualTime time = new VirtualTime(clock, timer, new Quiescence(() -> 1, () -> 0L, () -> "unchanged"), Duration.ofMillis(200));

        VirtualTime.Outcome outcome = time.settle(fired::get);

        assertFalse(outcome.settled(), outcome.toString());
        assertFalse(outcome.reason().isBlank(), outcome.toString());
        assertEquals(Duration.ZERO, outcome.flowTime(), outcome.toString());
        assertFalse(fired.get());
        assertEquals(T0, clock.instant());
    }
}
