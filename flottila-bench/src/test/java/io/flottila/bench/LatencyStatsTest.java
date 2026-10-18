package io.flottila.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Percentiles by nearest-rank over the recorded samples; what bench/RESULTS.md reports. */
class LatencyStatsTest {

    @Test
    void nearestRankPercentilesOverAllSamples() {
        LatencyStats s = new LatencyStats(10);
        for (long v : new long[] {5, 1, 9, 3, 7, 2, 8, 4, 10, 6}) s.record(v * 1_000_000L);   // 1..10 ms
        assertEquals(10, s.count());
        assertEquals(5.0, s.percentileMillis(50), 1e-9);
        assertEquals(10.0, s.percentileMillis(95), 1e-9);
        assertEquals(10.0, s.percentileMillis(99), 1e-9);
        assertEquals(1.0, s.percentileMillis(0), 1e-9);
        assertEquals(10.0, s.maxMillis(), 1e-9);
        assertEquals(5.5, s.meanMillis(), 1e-9);
    }

    @Test
    void oneSampleAndTheRoundingOfTheRank() {
        LatencyStats s = new LatencyStats(1);
        s.record(2_500_000L);
        assertEquals(2.5, s.percentileMillis(50), 1e-9);
        assertEquals(2.5, s.percentileMillis(99), 1e-9);
        LatencyStats three = new LatencyStats(3);
        three.record(1_000_000L); three.record(2_000_000L); three.record(3_000_000L);
        assertEquals(2.0, three.percentileMillis(50), 1e-9, "rank ceil(0.5*3)=2 → the 2nd sample");
        assertEquals(3.0, three.percentileMillis(67), 1e-9, "rank ceil(0.67*3)=3");
    }

    @Test
    void capacityIsFiniteAndEmptyStatsRefuseToReport() {
        LatencyStats s = new LatencyStats(2);
        s.record(1); s.record(2);
        assertThrows(IllegalStateException.class, () -> s.record(3), "a bench records exactly its planned samples");
        assertThrows(IllegalStateException.class, () -> new LatencyStats(2).percentileMillis(50));
        assertThrows(IllegalArgumentException.class, () -> new LatencyStats(0));
    }
}
