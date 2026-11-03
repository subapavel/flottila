package io.flottila.sim;

import io.flottila.sim.Verdict.Failure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.flottila.sim.Check.EMPTY;
import static io.flottila.sim.Check.KEY_ORDER;
import static io.flottila.sim.Check.PREDICTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-101 verdicts: Σcompared > 0 or EMPTY; a failure names the run and how to replay it. */
class SimVerdictTest {

    private static final SimSeed SEED = new SimSeed(0x2aL);

    private static Verdict verdict(List<Failure> failures, Map<Check, Long> compared) {
        return new Verdict(SEED, SimConfig.V1, 1000, "correct", failures, compared, "digest");
    }

    @Test
    void aRunComparingNothingIsEmpty() {
        Verdict nothing = verdict(List.of(), Map.of());
        assertFalse(nothing.passed(), "a run with no failure that compared nothing passed");
        assertEquals(Set.of(EMPTY), nothing.failedChecks());
        assertTrue(nothing.report().contains("check=EMPTY"), nothing.report());

        Verdict zeros = verdict(List.of(), Map.of(PREDICTED, 0L, KEY_ORDER, 0L));
        assertFalse(zeros.passed(), "checks present with zero comparisons are nothing compared");
        assertEquals(Set.of(EMPTY), zeros.failedChecks());

        Verdict one = verdict(List.of(), Map.of(PREDICTED, 0L, KEY_ORDER, 1L));
        assertTrue(one.passed(), "one comparison and no failure passes");
        assertEquals(Set.of(), one.failedChecks());

        Verdict failed = verdict(List.of(new Failure(KEY_ORDER, 412, "two ids of key k1 at the partner")), Map.of(KEY_ORDER, 5L));
        assertFalse(failed.passed());
        assertEquals(Set.of(KEY_ORDER), failed.failedChecks(), "a failure with comparisons is that check alone, not EMPTY");
    }

    @Test
    void aFailureNamesSeedConfigTicksWorldAndReplayCommand() {
        Verdict v = new Verdict(new SimSeed(0xfedcba9876543210L), SimConfig.V1, 1000, "reordering-fast-path",
                List.of(new Failure(KEY_ORDER, 412, "m-3 reached the partner before m-2 of key k1"),
                        new Failure(PREDICTED, 7, "expected Accepted, was Nack")),
                Map.of(KEY_ORDER, 9L, PREDICTED, 30L), "digest");
        List<String> lines = v.report().lines().toList();
        assertTrue(lines.contains("SIM FAIL seed=0xfedcba9876543210 config=V1 ticks=1000 world=reordering-fast-path check=KEY_ORDER tick=412: "
                + "m-3 reached the partner before m-2 of key k1"), () -> String.join("\n", lines));
        assertTrue(lines.contains("SIM FAIL seed=0xfedcba9876543210 config=V1 ticks=1000 world=reordering-fast-path check=PREDICTED tick=7: "
                + "expected Accepted, was Nack"), () -> String.join("\n", lines));
        String replay = "mvn -B -ntp -q -pl flottila-sim -am test -Dtest=SimReplayTest -Dsurefire.failIfNoSpecifiedTests=false"
                + " -Dsim.seed=0xfedcba9876543210 -Dsim.config=V1 -Dsim.ticks=1000 -Dsim.world=reordering-fast-path";
        assertEquals(1, lines.stream().filter(replay::equals).count(), () -> "one replay command in:\n" + String.join("\n", lines));

        // the seed and the config as printed read back as the run's (the top bit set: unsigned hex)
        assertEquals(v.seed(), SimSeed.parse("0xfedcba9876543210"));
        assertEquals("0xfedcba9876543210", v.seed().hex());
        assertEquals("0x2a", SEED.hex(), "no padding");
        assertEquals(SEED, SimSeed.parse("0x2a"));
        assertEquals("V1", SimConfig.V1.name());
        assertEquals(SimConfig.V1, SimConfig.named("V1"));
    }
}
