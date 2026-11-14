package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.flottila.runtime.node.NodeConfigTest.minimal;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-17 P2, P4, P5 (D-130 draft, F-27 I4): the node's Postgres connections in two pools by role. {@code FLOTTILA_PG_POOL_SIZE}
 * is the total of both pools, 2..1024 (21, F-33: 5 intake + 16 work); {@code FLOTTILA_PG_POOL_INTAKE} the intake pool's share, 1..1023 and below the total;
 * unset, {@code max(1, min(5, poolSize / 2))}; the work pool keeps the rest. Every refusal comes at start, an
 * {@link IllegalArgumentException} naming its key(s).
 *
 * <p>The API assumed (architect, CORE-17 packet): {@code NodeConfig.Pg} gains a last component {@code int intakePoolSize}, the
 * 9/8/6-argument constructors take {@code Pg.intakeDefault(poolSize)}; {@code Pg.workPoolSize()} is {@code poolSize - intakePoolSize}.
 */
class NodeConfigPoolsTest {

    static final String POOL = "FLOTTILA_PG_POOL_SIZE";
    static final String INTAKE = "FLOTTILA_PG_POOL_INTAKE";
    static final String CONCURRENCY = "FLOTTILA_INTAKE_GROUP_CONCURRENCY";

    static NodeConfig read(String... pairs) {
        Map<String, String> env = minimal();
        for (int i = 0; i < pairs.length; i += 2) with(env, pairs[i], pairs[i + 1]);
        return NodeConfig.from(env);
    }

    /** F-33 (architect, CORE-17 r3): the default total is 21, so the work pool keeps the 16 a node had before the split. */
    @Test
    void defaultsSplitFiveSixteen() {
        NodeConfig c = read();
        assertAll(
                () -> assertEquals(21, c.pg().poolSize(), "FLOTTILA_PG_POOL_SIZE unset: 21 connections in both pools together"),
                () -> assertEquals(5, c.pg().intakePoolSize(), "FLOTTILA_PG_POOL_INTAKE unset: 5 of them for the intake"),
                () -> assertEquals(16, c.pg().workPoolSize(), "the other 16 for the work pool"),
                () -> assertEquals(4, c.intakeGroup().concurrency(), "the concurrency unset: min(4, max(1, 5 - 1))"));
    }

    /** The formula max(1, min(5, poolSize / 2)), worked by hand: 2, 3 → 1; 4 → 2; 8 → 4; 10, 16, 21, 1024 → 5. */
    @ParameterizedTest(name = "poolSize {0} → intake {1}, work {2}")
    @CsvSource({"2, 1, 1", "3, 1, 2", "4, 2, 2", "8, 4, 4", "10, 5, 5", "16, 5, 11", "21, 5, 16", "1024, 5, 1019"})
    void derivedIntakeDefault(int poolSize, int intake, int work) {
        assertEquals(intake, NodeConfig.Pg.intakeDefault(poolSize), "intakeDefault(" + poolSize + ")");
        NodeConfig.Pg fromEnv = read(POOL, String.valueOf(poolSize)).pg();
        assertAll(
                () -> assertEquals(poolSize, fromEnv.poolSize(), "FLOTTILA_PG_POOL_SIZE " + poolSize + " is accepted"),
                () -> assertEquals(intake, fromEnv.intakePoolSize(), "FLOTTILA_PG_POOL_INTAKE unset over " + poolSize),
                () -> assertEquals(work, fromEnv.workPoolSize(), "the work pool keeps the rest of " + poolSize));

        NodeConfig.Pg six = new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", poolSize);
        NodeConfig.Pg eight = new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", poolSize, NodeConfig.Pg.SslMode.DISABLE, Optional.empty());
        NodeConfig.Pg nine = new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", poolSize, NodeConfig.Pg.SslMode.DISABLE, Optional.empty(),
                NodeConfig.Pg.Durability.HA);
        assertAll(
                () -> assertEquals(intake, six.intakePoolSize(), "the 6-argument constructor takes the formula"),
                () -> assertEquals(work, six.workPoolSize(), "6-argument: the rest is work"),
                () -> assertEquals(intake, eight.intakePoolSize(), "the 8-argument constructor takes the formula"),
                () -> assertEquals(intake, nine.intakePoolSize(), "the 9-argument constructor takes the formula"),
                () -> assertEquals(work, nine.workPoolSize(), "9-argument: the rest is work"));
    }

    @Test
    void explicitIntake() {
        NodeConfig c = read(POOL, "32", INTAKE, "9");
        assertAll(
                () -> assertEquals(32, c.pg().poolSize()),
                () -> assertEquals(9, c.pg().intakePoolSize(), "FLOTTILA_PG_POOL_INTAKE as given, not the formula's 5"),
                () -> assertEquals(23, c.pg().workPoolSize(), "32 - 9"),
                () -> assertEquals(4, c.intakeGroup().concurrency(), "the concurrency unset: min(4, max(1, 9 - 1))"),
                () -> assertEquals(8, read(POOL, "32", INTAKE, "9", CONCURRENCY, "8").intakeGroup().concurrency(),
                        "an explicit concurrency up to the intake pool's bound max(1, 9 - 1) is accepted"));

        NodeConfig.Pg edgeLow = read(POOL, "2", INTAKE, "1").pg();
        NodeConfig.Pg edgeHigh = read(POOL, "1024", INTAKE, "1023").pg();
        NodeConfig.Pg unsetTotal = read(INTAKE, "20").pg();
        assertAll(
                () -> assertEquals(List.of(1, 1), List.of(edgeLow.intakePoolSize(), edgeLow.workPoolSize()), "the least split: one each"),
                () -> assertEquals(List.of(1023, 1), List.of(edgeHigh.intakePoolSize(), edgeHigh.workPoolSize()), "1023 below 1024: the work pool keeps one"),
                () -> assertEquals(List.of(21, 20, 1), List.of(unsetTotal.poolSize(), unsetTotal.intakePoolSize(), unsetTotal.workPoolSize()),
                        "FLOTTILA_PG_POOL_INTAKE 20 below the default total 21"));

        NodeConfig.Pg canonical = new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 16, NodeConfig.Pg.SslMode.VERIFY_CA,
                Optional.of(Path.of("/etc/flottila/pg-ca.pem")), NodeConfig.Pg.Durability.LOCAL, 7);
        assertAll(
                () -> assertEquals(7, canonical.intakePoolSize(), "the canonical constructor takes the share as given"),
                () -> assertEquals(9, canonical.workPoolSize(), "16 - 7"));
    }

    /** P5: "Pg ctor refuses nothing new" — the split is refused at start, by the env reader ({@link #refusesNamingTheKey}), not by the record. */
    @Test
    void thePgConstructorRefusesNoSplit() {
        assertDoesNotThrow(() -> new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 4, NodeConfig.Pg.SslMode.DISABLE, Optional.empty(),
                NodeConfig.Pg.Durability.LOCAL, 4), "an in-process caller's split is not checked by the record");
    }

    /** An in-process node over {@code pg}: the shortest NodeConfig constructor, every other part as an env without them reads it. */
    static NodeConfig inProcess(NodeConfig.Pg pg) {
        NodeConfig base = read();
        return new NodeConfig(base.nodeId(), base.env(), base.admin(), base.dataDir(), base.stopDeadline(), base.maxPackageBytes(), pg, base.blobs());
    }

    static NodeConfig.Pg canonical(int poolSize, int intakePoolSize) {
        return new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", poolSize, NodeConfig.Pg.SslMode.DISABLE, Optional.empty(),
                NodeConfig.Pg.Durability.LOCAL, intakePoolSize);
    }

    /**
     * P5 for an in-process node (review r1): the record refuses no split ({@link #thePgConstructorRefusesNoSplit}), the node
     * built over it does — an intake share below 1 or not below the total leaves a pool with no connection, refused naming both
     * keys. poolSize 1 over the 6/8/9-argument constructors: intakeDefault(1) = max(1, min(5, 0)) = 1, the work pool 1 - 1 = 0.
     */
    @Test
    void anInProcessNodeRefusesASplitThatLeavesAPoolNoConnection() {
        Map<String, NodeConfig.Pg> refused = new LinkedHashMap<>();
        refused.put("6-argument, poolSize 1 (intake 1, work 0)", new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 1));
        refused.put("8-argument, poolSize 1 (intake 1, work 0)", new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 1,
                NodeConfig.Pg.SslMode.DISABLE, Optional.empty()));
        refused.put("9-argument, poolSize 1 (intake 1, work 0)", new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 1,
                NodeConfig.Pg.SslMode.DISABLE, Optional.empty(), NodeConfig.Pg.Durability.HA));
        refused.put("canonical 4/4 (work 0)", canonical(4, 4));
        refused.put("canonical 4/0 (intake 0)", canonical(4, 0));
        assertAll(refused.entrySet().stream().map(c -> () -> {   // every case reported, not only the first
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> inProcess(c.getValue()),
                    c.getKey() + ": an in-process node over this split is refused");
            assertTrue(e.getMessage().contains(POOL) && e.getMessage().contains(INTAKE), c.getKey() + ": the refusal names both keys: " + e.getMessage());
        }));
    }

    /** The edges of the in-process guard: intake 1 and intake poolSize - 1 are each a split with a connection in both pools. */
    @Test
    void anInProcessNodeAcceptsEverySplitWithAConnectionInBothPools() {
        for (int[] split : new int[][] {{4, 1}, {4, 3}, {2, 1}}) {
            NodeConfig c = assertDoesNotThrow(() -> inProcess(canonical(split[0], split[1])), "canonical " + split[0] + "/" + split[1] + " is accepted");
            assertEquals(List.of(split[0], split[1]), List.of(c.pg().poolSize(), c.pg().intakePoolSize()), "the split as given");
        }
        NodeConfig six = assertDoesNotThrow(() -> inProcess(new NodeConfig.Pg("db", 5432, "flottila", "flottila", "", 2)),
                "6-argument poolSize 2: intake 1, work 1");
        assertEquals(List.of(1, 1), List.of(six.pg().intakePoolSize(), six.pg().workPoolSize()), "poolSize 2 splits 1/1");
    }

    @Test
    void refusesNamingTheKey() {
        // one key's own value: its range or its form
        List<List<String>> own = List.of(
                List.of(POOL, "many"), List.of(POOL, "1"), List.of(POOL, "0"), List.of(POOL, "-1"), List.of(POOL, "1025"),
                List.of(INTAKE, "few"), List.of(INTAKE, "0"), List.of(INTAKE, "-1"));
        for (List<String> pair : own) {
            String key = pair.get(0);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(pair.get(0), pair.get(1)), key + "=" + pair.get(1) + " is refused");
            assertTrue(e.getMessage().contains(key), key + "=" + pair.get(1) + ": the refusal names the key: " + e.getMessage());
        }
        IllegalArgumentException above = assertThrows(IllegalArgumentException.class, () -> read(POOL, "1024", INTAKE, "1024"),
                "FLOTTILA_PG_POOL_INTAKE 1024 is above 1..1023");
        assertTrue(above.getMessage().contains(INTAKE), "names FLOTTILA_PG_POOL_INTAKE: " + above.getMessage());

        // the intake's share not below the total: the work pool keeps at least one; names both
        for (String[] split : new String[][] {{"16", "16"}, {"4", "5"}, {"2", "2"}, {"1023", "1023"}, {null, "21"}}) {
            String[] pairs = split[0] == null ? new String[] {INTAKE, split[1]} : new String[] {POOL, split[0], INTAKE, split[1]};
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(pairs),
                    "FLOTTILA_PG_POOL_INTAKE " + split[1] + " over FLOTTILA_PG_POOL_SIZE " + split[0] + " is refused");
            assertTrue(e.getMessage().contains(POOL) && e.getMessage().contains(INTAKE), "the refusal names both keys: " + e.getMessage());
        }

        // the concurrency above max(1, intake - 1): names it and FLOTTILA_PG_POOL_INTAKE, whether the share was given or derived
        for (String[] over : new String[][] {{"16", "2", "2"}, {"16", "5", "5"}, {null, null, "5"}, {"4", null, "2"}, {"9", "8", "8"}}) {
            Map<String, String> env = minimal();
            if (over[0] != null) with(env, POOL, over[0]);
            if (over[1] != null) with(env, INTAKE, over[1]);
            with(env, CONCURRENCY, over[2]);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(env),
                    "concurrency " + over[2] + " over an intake pool of " + (over[1] == null ? "the formula over " + over[0] : over[1]) + " is refused");
            assertTrue(e.getMessage().contains(CONCURRENCY) && e.getMessage().contains(INTAKE), "the refusal names both keys: " + e.getMessage());
        }
    }
}
