package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-17 (D-130 draft), the in-process seams the env-reading cases ({@link NodeConfigPoolsTest}) do not reach: a node built in
 * process over a {@link NodeConfig.Pg} takes the intake groups' defaults over the intake pool, as {@link NodeConfig#from} does,
 * the record's log line states both shares, and a total below its floor is refused for itself, never blamed on a share the
 * operator did not set.
 */
class NodeConfigPoolsInProcessTest {

    /** The constructors without intake-group keys default them over the intake pool, the bound the canonical constructor checks. */
    @ParameterizedTest(name = "poolSize {0}")
    @ValueSource(ints = {2, 4, 8, 16})
    void anInProcessNodeDefaultsItsGroupsOverTheIntakePool(int poolSize) {
        NodeConfig read = NodeConfig.from(NodeConfigTest.with(NodeConfigTest.minimal(), "FLOTTILA_PG_POOL_SIZE", String.valueOf(poolSize)));
        NodeConfig.Pg pg = new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", poolSize);
        NodeConfig built = new NodeConfig(read.nodeId(), read.env(), read.admin(), read.dataDir(), read.stopDeadline(), read.maxPackageBytes(), pg,
                read.blobs());
        assertEquals(read.intakeGroup(), built.intakeGroup(), "the groups an in-process node defaults are those an env of FLOTTILA_PG_POOL_SIZE "
                + poolSize + " reads");
    }

    /**
     * The same defaults as literals (review r1: the case above is parity with {@link NodeConfig#from} only): the draft defaults
     * 64, 1 MiB, PT0.001S, and min(4, max(1, intake - 1)) groups in flight, worked by hand over the formula's share —
     * 2 → intake 1 → 1; 4 → 2 → 1; 8 → 4 → 3; 16 → 5 → 4.
     */
    @ParameterizedTest(name = "poolSize {0} → concurrency {1}")
    @CsvSource({"2, 1", "4, 1", "8, 3", "16, 4"})
    void anInProcessNodeDefaultsItsGroupsToTheWorkedValues(int poolSize, int concurrency) {
        NodeConfig read = NodeConfig.from(NodeConfigTest.minimal());
        NodeConfig.Pg pg = new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", poolSize);
        NodeConfig built = new NodeConfig(read.nodeId(), read.env(), read.admin(), read.dataDir(), read.stopDeadline(), read.maxPackageBytes(), pg,
                read.blobs());
        assertEquals(new NodeConfig.IntakeGroup(64, 1_048_576L, Duration.ofMillis(1), concurrency), built.intakeGroup(),
                "an in-process node of poolSize " + poolSize + ": 64, 1 MiB, PT0.001S, " + concurrency + " in flight");
    }

    @Test
    void theLogLineStatesBothShares() {
        NodeConfig.Pg pg = new NodeConfig.Pg("db", 5432, "flottila", "flottila", "pw", 12, NodeConfig.Pg.SslMode.DISABLE, Optional.empty(),
                NodeConfig.Pg.Durability.LOCAL, 3);
        String line = pg.toString();
        assertAll(
                () -> assertTrue(line.contains("poolSize=" + pg.poolSize()), "the total: " + line),
                () -> assertTrue(line.contains("intakePoolSize=" + pg.intakePoolSize()), "the intake pool's share: " + line));
    }

    /** P5: {@code FLOTTILA_PG_POOL_SIZE} below 2 is refused by its own range; an unset {@code FLOTTILA_PG_POOL_INTAKE} is not named. */
    @ParameterizedTest(name = "FLOTTILA_PG_POOL_SIZE {0}")
    @ValueSource(strings = {"1", "0", "-1"})
    void aTotalBelowTwoIsRefusedForItself(String poolSize) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(NodeConfigTest.with(NodeConfigTest.minimal(), "FLOTTILA_PG_POOL_SIZE", poolSize)), "refused: " + poolSize);
        assertAll(
                () -> assertTrue(e.getMessage().contains("FLOTTILA_PG_POOL_SIZE"), "names the key: " + e.getMessage()),
                () -> assertFalse(e.getMessage().contains("FLOTTILA_PG_POOL_INTAKE"), "not the share nobody set: " + e.getMessage()));
    }
}
