package io.flottila.testkit;

import io.flottila.api.Timer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fake timer fires when the fake clock reaches a task's due time — never by wall time. */
class FakeTimerTest {

    final FakeClock clock = FakeClock.at(Instant.EPOCH);
    final FakeTimer timer = new FakeTimer(clock);
    final List<String> fired = new ArrayList<>();

    @Test
    void nextDelayIsTheDistanceToTheEarliestPendingTaskAndFailsWhenNothingIsPending() {
        assertThrows(IllegalStateException.class, timer::nextDelay);
        timer.schedule(Duration.ofMillis(30), () -> fired.add("later"));
        timer.schedule(Duration.ofMillis(10), () -> fired.add("sooner"));
        assertEquals(Duration.ofMillis(10), timer.nextDelay());
        clock.advance(Duration.ofMillis(4));
        assertEquals(Duration.ofMillis(6), timer.nextDelay(), "measured from the clock's current instant");
        clock.advance(timer.nextDelay());
        assertEquals(List.of("sooner"), fired);
        assertEquals(Duration.ofMillis(20), timer.nextDelay());
        clock.advance(timer.nextDelay());
        assertEquals(List.of("sooner", "later"), fired);
        assertThrows(IllegalStateException.class, timer::nextDelay, "nothing left");
    }

    @Test
    void firesExactlyWhenTheClockReachesTheDueTimeNotBefore() {
        timer.schedule(Duration.ofMillis(50), () -> fired.add("a"));
        clock.advance(Duration.ofMillis(49));
        assertEquals(List.of(), fired);
        assertEquals(1, timer.pending());
        clock.advance(Duration.ofMillis(1));
        assertEquals(List.of("a"), fired);
        assertEquals(0, timer.pending());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(List.of("a"), fired, "a one-shot timer fires once");
    }

    @Test
    void dueTasksFireInDueOrderEvenWhenOneAdvanceCoversSeveral() {
        timer.schedule(Duration.ofMillis(30), () -> fired.add("later"));
        timer.schedule(Duration.ofMillis(10), () -> fired.add("sooner"));
        timer.schedule(Duration.ofMillis(10), () -> fired.add("sooner-2"));
        clock.advance(Duration.ofMillis(100));
        assertEquals(List.of("sooner", "sooner-2", "later"), fired);
    }

    @Test
    void cancelledTasksNeverFireAndCancelReportsWhetherItWasPending() {
        Timer.Cancellable h = timer.schedule(Duration.ofMillis(10), () -> fired.add("x"));
        assertTrue(h.cancel());
        assertFalse(h.cancel(), "second cancel finds nothing");
        clock.advance(Duration.ofMillis(10));
        assertEquals(List.of(), fired);
        Timer.Cancellable fired1 = timer.schedule(Duration.ZERO, () -> fired.add("zero"));
        clock.advance(Duration.ZERO);
        assertEquals(List.of("zero"), fired, "a zero delay fires on the next clock movement, even a zero one");
        assertFalse(fired1.cancel(), "already fired");
    }

    @Test
    void aTaskMayScheduleAnotherTaskWhileFiring() {
        timer.schedule(Duration.ofMillis(10), () -> {
            fired.add("first");
            timer.schedule(Duration.ofMillis(5), () -> fired.add("chained"));
        });
        clock.advance(Duration.ofMillis(10));
        assertEquals(List.of("first"), fired, "the chained task is due at 15 ms");
        clock.advance(Duration.ofMillis(5));
        assertEquals(List.of("first", "chained"), fired);
    }

    @Test
    void setAlsoFiresDueTasks() {
        timer.schedule(Duration.ofSeconds(1), () -> fired.add("a"));
        clock.set(Instant.EPOCH.plusSeconds(2));
        assertEquals(List.of("a"), fired);
    }

    @Test
    void negativeDelayIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> timer.schedule(Duration.ofMillis(-1), () -> { }));
    }
}
