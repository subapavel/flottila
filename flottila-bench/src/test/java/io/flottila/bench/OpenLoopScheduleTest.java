package io.flottila.bench;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15: the open loop's schedule, fixed in advance. Request i is due at the instant the cumulative offered count reaches i:
 * during the ramp the rate grows linearly from 0 to {@code rate} (N(t) = rate·t²/(2·ramp), so t_i = sqrt(2·ramp·i/rate)), after
 * it the rate is constant (t_i = ramp + (i − rate·ramp/2)/rate). The window is [ramp, ramp + window): it holds rate × window
 * requests, and nothing is scheduled after it. Every expected value below is worked out by hand from those two formulas.
 */
class OpenLoopScheduleTest {

    static final Duration NO_RAMP = Duration.ZERO;

    @Test
    void aRampThenConstantScheduleDuesEachRequestAtItsFixedOffset() {
        OpenLoop.Schedule s = new OpenLoop.Schedule(1000, Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertEquals(500, s.rampRequests(), "rate × ramp / 2: the ramp's area at a linear rise from 0 to 1000/s over 1 s");
        assertEquals(1000, s.windowRequests(), "rate × window: 1000/s for 1 s");
        assertEquals(1500, s.total(), "the ramp's and the window's, nothing after the window");

        assertNear(0, s.dueNanos(0), "the first request is due at the start");
        assertNear(44_721_360, s.dueNanos(1), "sqrt(2 · 1 s · 1 / 1000) = 44.72136 ms");
        assertNear(100_000_000, s.dueNanos(5), "sqrt(2 · 5 / 1000) = 0.1 s");
        assertNear(500_000_000, s.dueNanos(125), "sqrt(2 · 125 / 1000) = 0.5 s");
        assertNear(800_000_000, s.dueNanos(320), "sqrt(2 · 320 / 1000) = 0.8 s");
        assertNear(998_999_499, s.dueNanos(499), "sqrt(0.998) s, the ramp's last request");
        assertNear(1_000_000_000, s.dueNanos(500), "the window's first request at the ramp's end");
        assertNear(1_001_000_000, s.dueNanos(501), "then one every 1 ms");
        assertNear(1_999_000_000, s.dueNanos(1499), "the window's last request, 1 ms before its end");

        assertFalse(s.inWindow(0), "a ramp request is outside the window");
        assertFalse(s.inWindow(499), "the ramp's last request is outside the window");
        assertTrue(s.inWindow(500), "the window starts with the request due at the ramp's end");
        assertTrue(s.inWindow(1499));
        assertFalse(s.inWindow(1500), "the request that would be due at the window's end is not scheduled");
    }

    @Test
    void withoutARampTheRateIsConstantFromTheStartAndTheWindowHoldsEveryRequest() {
        OpenLoop.Schedule s = new OpenLoop.Schedule(4, NO_RAMP, Duration.ofSeconds(1));
        assertEquals(0, s.rampRequests());
        assertEquals(4, s.windowRequests());
        assertEquals(4, s.total());
        assertNear(0, s.dueNanos(0), "due at the start");
        assertNear(250_000_000, s.dueNanos(1), "1/4 s apart");
        assertNear(750_000_000, s.dueNanos(3), "the last of the four");
        assertTrue(s.inWindow(0), "no ramp: the first request is the window's");
        assertTrue(s.inWindow(3));
        assertFalse(s.inWindow(4), "due at 1 s, the window's end: not scheduled");

        OpenLoop.Schedule thirds = new OpenLoop.Schedule(3, NO_RAMP, Duration.ofSeconds(1));
        assertNear(333_333_333, thirds.dueNanos(1), "1/3 s, not rounded to a millisecond");
        assertNear(666_666_667, thirds.dueNanos(2), "2/3 s");
    }

    @Test
    void theLargestRunsCountsAndOffsetsAreLongs() {
        OpenLoop.Schedule s = new OpenLoop.Schedule(100_000, Duration.ofMinutes(10), Duration.ofHours(1));
        assertEquals(30_000_000L, s.rampRequests(), "100 000/s × 600 s / 2");
        assertEquals(360_000_000L, s.windowRequests(), "100 000/s × 3600 s");
        assertEquals(390_000_000L, s.total());
        assertNear(600_000_000_000L, s.dueNanos(30_000_000L), "the ramp's end, 600 s: past an int of nanoseconds");
        assertNear(4_199_999_990_000L, s.dueNanos(389_999_999L), "600 s + 3600 s − 10 µs, the last request");
        assertFalse(s.inWindow(390_000_000L));
    }

    /** {@code actual} within 1 ns of {@code expected} (an irrational offset rounds either way). */
    static void assertNear(long expected, long actual, String what) {
        assertTrue(Math.abs(expected - actual) <= 1, what + ": expected " + expected + " ns ± 1, was " + actual);
    }
}
