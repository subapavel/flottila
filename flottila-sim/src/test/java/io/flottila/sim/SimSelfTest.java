package io.flottila.sim;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static io.flottila.sim.Check.ACKED_IS_FINAL;
import static io.flottila.sim.Check.ADMISSION_BOUNDS;
import static io.flottila.sim.Check.DRAINED;
import static io.flottila.sim.Check.KEY_ORDER;
import static io.flottila.sim.Check.PREDICTED;
import static io.flottila.sim.Check.SNAPSHOT_PER_STEP;
import static io.flottila.sim.Pins.pinned;
import static io.flottila.sim.SimRuns.TICKS;
import static io.flottila.sim.SimRuns.firstFailing;
import static io.flottila.sim.SimRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-101 self-test: the correct world passes every per-push seed with each check above its floor, and each broken variant —
 * built from public seams only — fails its exact set of checks on some per-push seed.
 */
class SimSelfTest {

    /** The checks P2-10a's workload exercises (ONE_ID_PER_DEDUPE_KEY and REPLAY_YIELDS_RECORDED come with P2-10b). */
    static final Set<Check> CHECKED_IN_10A = EnumSet.of(PREDICTED, ADMISSION_BOUNDS, KEY_ORDER, ACKED_IS_FINAL, SNAPSHOT_PER_STEP, DRAINED);

    /**
     * PLACEHOLDER (landing): per check, half the smallest per-seed compared count of the 32 per-push seeds at landing, as
     * recorded in bench/RESULTS.md §P2-10; lowering one needs a D-line.
     */
    static final Map<Check, Long> FLOORS = Map.of(PREDICTED, 592L, ADMISSION_BOUNDS, 159L, KEY_ORDER, 694L, ACKED_IS_FINAL, 313L,
            SNAPSHOT_PER_STEP, 555L, DRAINED, 624L);

    static List<SimSeed> perPush() {
        return SimRuns.PER_PUSH;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("perPush")
    void correctWorldPassesEverySeedAboveEachFloor(SimSeed seed) {
        Verdict v = run(seed, SimConfig.V1, TICKS, World.CORRECT);
        assertTrue(v.passed(), v::report);
        for (Check c : CHECKED_IN_10A) {
            long floor = pinned(FLOORS.get(c), "floor of " + c);
            long compared = v.compared().getOrDefault(c, 0L);
            assertTrue(compared >= floor, () -> seed + ": " + c + " compared " + compared + " times, below its floor " + floor + "\n" + v.report());
        }
    }

    @Test
    void ackBeforeSaveFailsPredictedAndAckedIsFinal() {
        Verdict v = firstFailing(new AckBeforeSave());
        assertEquals(EnumSet.of(PREDICTED, ACKED_IS_FINAL), v.failedChecks(), v::report);
    }

    @Test
    void reorderingFastPathFailsKeyOrder() {
        Verdict v = firstFailing(new ReorderingFastPath());
        assertEquals(EnumSet.of(KEY_ORDER), v.failedChecks(), v::report);
    }

    @Test
    void secondAdmissionFailsAdmissionBounds() {
        Verdict v = firstFailing(new DoubleAdmission());
        assertEquals(EnumSet.of(ADMISSION_BOUNDS), v.failedChecks(), v::report);
    }

    @Test
    void renumberedIngressFailsSnapshotPerStep() {
        Verdict v = firstFailing(new RenumberedIngress());
        assertEquals(EnumSet.of(SNAPSHOT_PER_STEP), v.failedChecks(), v::report);
    }

    @Test
    void overClaimingQueueFailsAdmissionBounds() {
        Verdict v = firstFailing(new OverClaim());
        assertEquals(EnumSet.of(ADMISSION_BOUNDS), v.failedChecks(), v::report);
        assertTrue(v.failures().stream().anyMatch(f -> f.check() == ADMISSION_BOUNDS && f.detail().startsWith("worker ")),
                () -> "the over-claim was caught by a flow bound, not by a worker's run limit:\n" + v.report());
    }

    /**
     * No DELAY: a message stays open at the partner past its own call only while it retries after a refused call, so the only
     * overlap this variant can show is a second message of the key during the first one's retries.
     */
    @Test
    void aKeyClaimedDuringItsRetriesFailsKeyOrder() {
        Verdict v = firstFailing(new KeyBlindClaim(), SimConfig.V1.withRate(FaultKind.DELAY, 0.0));
        assertEquals(EnumSet.of(KEY_ORDER), v.failedChecks(), v::report);
    }
}
