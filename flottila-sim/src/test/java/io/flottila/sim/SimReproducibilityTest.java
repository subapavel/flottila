package io.flottila.sim;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static io.flottila.sim.Pins.pinned;
import static io.flottila.sim.SimRuns.TICKS;
import static io.flottila.sim.SimRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-101: a seed is the whole run; the streams do not move each other. */
class SimReproducibilityTest {

    /** PLACEHOLDER (landing): the trace digest of seed 0x1, V1, 1 000 ticks, the correct world. */
    private static final String GOLDEN_DIGEST_SEED_1 = "sha256-64:a2265034349a7816";

    @Test
    void sameSeedSameDigestAndVerdict() {
        SimSeed one = new SimSeed(1);
        Verdict first = run(one, SimConfig.V1, TICKS, World.CORRECT);
        Verdict other = run(new SimSeed(2), SimConfig.V1, TICKS, World.CORRECT);   // between the two: nothing carries over
        Verdict again = run(one, SimConfig.V1, TICKS, World.CORRECT);
        assertEquals(first, again, "seed 0x1 run twice in one JVM");
        assertTrue(total(first.compared()) > 0, "the digest of a run that compared nothing pins nothing");
        assertNotEquals(first.traceDigest(), other.traceDigest(), "seeds 0x1 and 0x2 ran the same trace");
        assertEquals(pinned(GOLDEN_DIGEST_SEED_1, "trace digest of seed 0x1 V1 1000 ticks correct"), first.traceDigest(),
                "seed 0x1's trace moved: every pinned seed moves with it");
    }

    @Property(tries = 8, shrinking = ShrinkingMode.OFF)
    void sameSeedSameDigestAndVerdictOverAnySeed(@ForAll long seed) {
        Verdict first = run(new SimSeed(seed), SimConfig.V1, 250, World.CORRECT);
        Verdict again = run(new SimSeed(seed), SimConfig.V1, 250, World.CORRECT);
        assertEquals(first, again, "seed 0x" + Long.toHexString(seed) + " run twice in one JVM");
    }

    @Test
    void aWeightChangeLeavesFaultPlanAndPerCallFaultsUnmoved() {
        SimSeed seed = new SimSeed(3);
        SimConfig moved = SimConfig.V1.withWeight(Action.SEND_SYNC, 2 * SimConfig.V1.weight(Action.SEND_SYNC) + 1);
        Observed before = new Observed();
        Observed after = new Observed();
        Verdict a = run(seed, SimConfig.V1, TICKS, before);
        Verdict b = run(seed, moved, TICKS, after);
        assertNotEquals(a.traceDigest(), b.traceDigest(), "the weight change did not move the workload: the case proves nothing");
        assertTrue(before.conflicts.isEmpty(), () -> "one call, two decisions in one run: " + before.conflicts);
        assertTrue(after.conflicts.isEmpty(), () -> "one call, two decisions in one run: " + after.conflicts);

        Set<Call> common = new HashSet<>(before.decisions.keySet());
        common.retainAll(after.decisions.keySet());
        assertFalse(common.isEmpty(), "no call was made in both runs");
        assertTrue(common.stream().anyMatch(before.decisions::get), "no fault hit among the calls made in both runs");
        for (Call c : common) {
            assertEquals(before.decisions.get(c), after.decisions.get(c), () -> c + " decided otherwise after a weight change");
        }
    }

    private static long total(Map<Check, Long> compared) {
        return compared.values().stream().mapToLong(Long::longValue).sum();
    }

    /** A per-call fault decision's identity: the tick is not part of it. */
    record Call(FaultKind kind, String messageId, int attempt, int callOrdinal) {}

    /** The correct world, recording every per-call fault decision. */
    static final class Observed implements World {
        final Map<Call, Boolean> decisions = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<String> conflicts = new ConcurrentLinkedQueue<>();

        @Override public String name() { return "correct"; }

        @Override public void onDraw(Draw d) {
            Call c = new Call(d.kind(), d.messageId(), d.attempt(), d.callOrdinal());
            Boolean earlier = decisions.putIfAbsent(c, d.hit());
            if (earlier != null && earlier != d.hit()) conflicts.add(c + " at tick " + d.tick());
        }
    }
}
