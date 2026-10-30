package io.flottila.runtime.store;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P2-07 (D-088): {@code Retention.Config} is validated — nothing unlimited, nothing absent: a max age and an interval above
 * zero, a batch of at least one row and at most 10000 (review r1). The node's ranges (PT1H..P366D, PT1M..P1D, 1..10000) are {@code NodeConfig}'s
 * ({@code NodeConfigTest}); every value here lies inside them.
 */
class RetentionConfigTest {

    static final Duration WEEK = Duration.ofDays(7);
    static final Duration HOUR = Duration.ofHours(1);

    @Test
    void aConfigHoldsWhatItWasGiven() {
        Retention.Config c = new Retention.Config(Duration.ofDays(30), Duration.ofMinutes(5), 250);
        assertEquals(List.of(Duration.ofDays(30), Duration.ofMinutes(5), 250), List.of(c.maxAge(), c.interval(), c.batch()));
    }

    @Test
    void aConfigRefusesNoAgeNoIntervalAndNoBatch() {
        assertThrows(NullPointerException.class, () -> new Retention.Config(null, HOUR, 1000), "no max age");
        assertThrows(NullPointerException.class, () -> new Retention.Config(WEEK, null, 1000), "no interval");
        for (Duration none : List.of(Duration.ZERO, Duration.ofDays(-7))) {
            assertThrows(IllegalArgumentException.class, () -> new Retention.Config(none, HOUR, 1000), "max age " + none);
            assertThrows(IllegalArgumentException.class, () -> new Retention.Config(WEEK, none, 1000), "interval " + none);
        }
        for (int none : new int[] {0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new Retention.Config(WEEK, HOUR, none), "batch " + none);
        }
    }

    @Test
    void aConfigRefusesABatchAboveTenThousandRows() {
        assertEquals(10_000, new Retention.Config(WEEK, HOUR, 10_000).batch(), "10000 rows is the largest batch");
        for (int over : new int[] {10_001, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Retention.Config(WEEK, HOUR, over), "batch " + over);
        }
    }
}
