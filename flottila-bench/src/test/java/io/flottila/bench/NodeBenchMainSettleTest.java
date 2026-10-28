package io.flottila.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window's settle (FIX-67, F-13) against a fake {@link NodeBenchMain.Probe}: the landed query, the node's /health
 * {@code snapshotWriteFailures}, the clock and the pause between polls are the probe's, so no case sleeps or needs a node. The
 * fake pause advances the fake clock; the counter and the landed answer are functions of how many pauses have passed, so
 * the cases hold however often a poll reads them.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodeBenchMainSettleTest {

    static final Duration DRAIN = Duration.ofMinutes(10);
    static final long BASELINE = 3;
    static final long RISEN = 41;

    /** The fake world one settle runs in: pauses advance the clock by {@code step} nanos. */
    static final class World {
        final long step;
        long now = 7_000_000_000_123L;   // an arbitrary origin: the deadline is relative to the probe's clock
        int pauses;
        int healthReads;
        final List<Long> landedAsked = new ArrayList<>();
        int landedAfterPauses = Integer.MAX_VALUE;
        int risesAfterPauses = Integer.MAX_VALUE;
        long base = BASELINE;
        long risenTo = RISEN;

        World(long step) {
            this.step = step;
        }

        NodeBenchMain.Probe probe() {
            return new NodeBenchMain.Probe(recorded -> {
                landedAsked.add(recorded);
                return pauses >= landedAfterPauses;
            }, () -> {
                healthReads++;
                return pauses >= risesAfterPauses ? risenTo : base;
            }, () -> now, () -> {
                pauses++;
                now += step;
            });
        }
    }

    static NodeBenchMain.Harness harness(String scenario) {
        // no Vert.x, no pool, no client: everything settle reads or waits on is the probe's
        return new NodeBenchMain.Harness(NodeBenchMain.options(new String[] {"--scenario", scenario}), null, null, null, null, 0, 0);
    }

    @Test
    void aSyncSettleFailsAtTheFirstPollThatSeesSnapshotWriteFailuresRiseNamingTheCounterAndItsValue() {
        World w = new World(TimeUnit.MILLISECONDS.toNanos(5));
        w.risesAfterPauses = 2;   // the window has not landed and never will: only the counter can end the wait early
        long start = w.now;

        RuntimeException failed = assertThrows(RuntimeException.class, () -> harness("sync").settle(57, BASELINE, DRAIN, w.probe()));

        String m = failed.getMessage();
        assertTrue(m != null && m.contains("snapshotWriteFailures"), "names the counter: " + m);
        assertTrue(m.contains(String.valueOf(RISEN)), "names the counter's value " + RISEN + ": " + m);
        assertFalse(m.contains("did not land"), "the rise, not the drain timeout: " + m);
        assertEquals(2, w.pauses, "failed at the first poll after the rise, without waiting again");
        assertTrue(w.now - start < DRAIN.toNanos(), "long before the drain bound");
        assertTrue(w.landedAsked.stream().allMatch(r -> r == 57), "the landed query asks for the recorded target: " + w.landedAsked);
    }

    @Test
    void aSyncSettleWhoseCounterStaysAtTheWindowsStartValueWaitsUntilTheWindowLanded() {
        World w = new World(TimeUnit.MILLISECONDS.toNanos(5));
        w.landedAfterPauses = 3;   // the counter never moves from BASELINE

        assertDoesNotThrow(() -> harness("sync").settle(57, BASELINE, DRAIN, w.probe()));

        assertEquals(3, w.pauses, "polled until the window landed, then returned");
        assertTrue(w.healthReads > 0, "a sync settle reads the counter (else the fail-fast case above could not hold)");
    }

    @Test
    void aSyncSettleFailsWhenTheCounterRisesFromZeroByOne() {
        World w = new World(TimeUnit.MILLISECONDS.toNanos(5));
        w.base = 0;
        w.risesAfterPauses = 1;
        w.risenTo = 1;

        RuntimeException failed = assertThrows(RuntimeException.class, () -> harness("sync").settle(9, 0, DRAIN, w.probe()));

        assertTrue(failed.getMessage() != null && failed.getMessage().contains("snapshotWriteFailures"), failed.getMessage());
        assertEquals(1, w.pauses, "one write failure in the window is a rise: the window's own start value is the baseline");
    }

    @Test
    void aSyncSettleFailsWhenTheCounterRoseEvenIfTheWindowLandedInTheSamePoll() {
        // a landed count of distinct ids can hide a message that lost some of its rows: the rise still fails the window
        World w = new World(TimeUnit.MILLISECONDS.toNanos(5));
        w.risesAfterPauses = 1;
        w.landedAfterPauses = 1;

        RuntimeException failed = assertThrows(RuntimeException.class, () -> harness("sync").settle(57, BASELINE, DRAIN, w.probe()));

        assertTrue(failed.getMessage() != null && failed.getMessage().contains("snapshotWriteFailures"), failed.getMessage());
        assertTrue(failed.getMessage().contains(String.valueOf(RISEN)), "names the value: " + failed.getMessage());
        assertEquals(1, w.pauses, "the poll that saw both the landing and the rise failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ack", "worker"})
    void anAtLeastOnceSettleIgnoresARisingCounterAndWaitsUntilTheWindowLanded(String scenario) {
        // the counter also counts at-least-once ingress refusals, which are not losses: no fail-fast on it here
        World w = new World(TimeUnit.MILLISECONDS.toNanos(5));
        w.risesAfterPauses = 0;   // risen from the first poll on
        w.landedAfterPauses = 2;

        assertDoesNotThrow(() -> harness(scenario).settle(57, BASELINE, DRAIN, w.probe()));

        assertEquals(2, w.pauses, scenario + " polled until the window landed");
        assertTrue(w.landedAsked.stream().allMatch(r -> r == 57), "the landed query asks for the recorded target: " + w.landedAsked);
    }

    @Test
    void theSameRisingProbeFailsASyncSettle() {
        // the witness for the case above: the probe it ignores is one a sync settle refuses
        World w = new World(TimeUnit.MILLISECONDS.toNanos(5));
        w.risesAfterPauses = 0;
        w.landedAfterPauses = 2;

        RuntimeException failed = assertThrows(RuntimeException.class, () -> harness("sync").settle(57, BASELINE, DRAIN, w.probe()));

        assertTrue(failed.getMessage() != null && failed.getMessage().contains("snapshotWriteFailures"), failed.getMessage());
        assertEquals(0, w.pauses, "risen at the first poll: no wait at all");
    }

    @Test
    void aSettleThatNeverLandsFailsAtTheDrainBoundOnTheProbesClock() {
        // one second per pause: ten minutes are 600 pauses; a millis/nanos slip ends after 0 or 600 000
        World w = new World(TimeUnit.SECONDS.toNanos(1));

        IllegalStateException late = assertThrows(IllegalStateException.class, () -> harness("sync").settle(57, BASELINE, DRAIN, w.probe()));

        assertEquals("57 recorded messages and an empty work queue did not land within PT10M", late.getMessage());
        assertTrue(w.pauses == 600 || w.pauses == 601, "the bound is the probe's clock + 10 min: " + w.pauses + " pauses");
    }
}
