package io.flottila.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run a failure line's replay command names (D-101): {@code -Dsim.seed=0x… -Dsim.config=… -Dsim.ticks=… -Dsim.world=…};
 * without them seed 0x1, V1, 1 000 ticks, the correct world. It fails with the verdict's report when the run fails.
 */
class SimReplayTest {

    @Test
    void theNamedRunPasses() {
        SimSeed seed = SimSeed.parse(System.getProperty("sim.seed", "0x1"));
        SimConfig config = SimConfig.named(System.getProperty("sim.config", "V1"));
        int ticks = Integer.parseInt(System.getProperty("sim.ticks", Integer.toString(SimRuns.TICKS)));
        World world = Worlds.named(System.getProperty("sim.world", "correct"));
        Verdict v = SimRuns.run(seed, config, ticks, world);
        assertEquals(world.name(), v.world());
        assertTrue(v.passed(), v::report);
    }
}
