package io.flottila.sim;

import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * How the tests run the simulation: every run under the per-seed hang guard (D-101), the per-push seeds and ticks, and the
 * search for the seed that detects a broken world.
 */
final class SimRuns {

    private SimRuns() {}

    /** The per-seed hang guard; a run that is not done by then names its seed, config, ticks and world. */
    static final Duration PER_SEED = Duration.ofSeconds(30);

    /** Ticks of a per-push run. */
    static final int TICKS = 1_000;

    /** The per-push seeds: 0x1 .. 0x20. */
    static final List<SimSeed> PER_PUSH = LongStream.rangeClosed(1, 32).mapToObj(SimSeed::new).toList();

    static Verdict run(SimSeed seed, SimConfig config, int ticks, World world) {
        return assertTimeoutPreemptively(PER_SEED, () -> Simulation.run(seed, config, ticks, world),
                () -> "SIM HANG seed=0x" + Long.toHexString(seed.run()) + " ticks=" + ticks + " world=" + world.name()
                        + ": not done within " + PER_SEED);
    }

    /** The verdict of the first per-push seed {@code world} fails under V1; a failed test when it passes all 32. */
    static Verdict firstFailing(World world) {
        return firstFailing(world, SimConfig.V1);
    }

    /** The verdict of the first per-push seed {@code world} fails under {@code config}; a failed test when it passes all 32. */
    static Verdict firstFailing(World world, SimConfig config) {
        for (SimSeed seed : PER_PUSH) {
            Verdict v = run(seed, config, TICKS, world);
            if (!v.passed()) return v;
        }
        return fail("world " + world.name() + " passed all " + PER_PUSH.size() + " per-push seeds × " + TICKS + " ticks under " + config.name()
                + ": the oracle does not see its defect");
    }
}
