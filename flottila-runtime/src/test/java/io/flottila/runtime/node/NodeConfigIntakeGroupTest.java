package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.flottila.runtime.node.NodeConfigTest.minimal;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-6b packet item 7 (D-109): the intake group's keys, read by NodeConfig (the only env reader), each with its default, finite
 * and range-checked, a refusal an {@link IllegalArgumentException} naming the key — {@code FLOTTILA_INTAKE_GROUP_MAX} 64 (1..1024),
 * {@code FLOTTILA_INTAKE_GROUP_BYTES} 1 MiB (64 KiB..64 MiB), {@code FLOTTILA_INTAKE_GROUP_WINDOW} PT0.001S (PT0S..PT0.05S, whole
 * milliseconds), {@code FLOTTILA_INTAKE_GROUP_CONCURRENCY} 4 (1..64, below the intake pool {@code FLOTTILA_PG_POOL_INTAKE}: refused
 * naming both — CORE-17, D-130 draft amends D-109's bound "over FLOTTILA_PG_POOL_SIZE").
 *
 * <p>The API assumed (test-author): {@code NodeConfig.intakeGroup()} answering {@code record NodeConfig.IntakeGroup(int max, long
 * bytes, Duration window, int concurrency)}; {@code IntakeGroup.SINGLE} is a group of one with no window.
 */
class NodeConfigIntakeGroupTest {

    static final String MAX = "FLOTTILA_INTAKE_GROUP_MAX";
    static final String BYTES = "FLOTTILA_INTAKE_GROUP_BYTES";
    static final String WINDOW = "FLOTTILA_INTAKE_GROUP_WINDOW";
    static final String CONCURRENCY = "FLOTTILA_INTAKE_GROUP_CONCURRENCY";
    static final String POOL = "FLOTTILA_PG_POOL_SIZE";
    static final String INTAKE = "FLOTTILA_PG_POOL_INTAKE";

    static NodeConfig.IntakeGroup read(String... pairs) {
        Map<String, String> env = minimal();
        for (int i = 0; i < pairs.length; i += 2) with(env, pairs[i], pairs[i + 1]);
        return NodeConfig.from(env).intakeGroup();
    }

    @Test
    void defaultsAndBounds() {
        assertEquals(new NodeConfig.IntakeGroup(64, 1_048_576L, Duration.ofMillis(1), 4), read(), "the draft defaults: 64, 1 MiB, PT0.001S, 4");

        assertEquals(1, read(MAX, "1").max(), "1: no grouping");
        assertEquals(1024, read(MAX, "1024").max());
        assertEquals(65_536L, read(BYTES, "65536").bytes(), "64 KiB");
        assertEquals(67_108_864L, read(BYTES, "67108864").bytes(), "64 MiB");
        assertEquals(Duration.ZERO, read(WINDOW, "PT0S").window(), "no window: only on completion");
        assertEquals(Duration.ofMillis(50), read(WINDOW, "PT0.05S").window());
        assertEquals(Duration.ofMillis(7), read(WINDOW, "PT0.007S").window(), "a value between the edges, as given");
        assertEquals(1, read(CONCURRENCY, "1").concurrency());
        assertEquals(64, read(CONCURRENCY, "64", POOL, "1024", INTAKE, "65").concurrency(), "64 below an intake pool of 65");

        List<List<String>> refused = List.of(
                List.of(MAX, "0"), List.of(MAX, "1025"), List.of(MAX, "-1"), List.of(MAX, "many"),
                List.of(BYTES, "65535"), List.of(BYTES, "67108865"), List.of(BYTES, "0"), List.of(BYTES, "1MiB"),
                List.of(WINDOW, "PT0.051S"), List.of(WINDOW, "PT0.0015S"), List.of(WINDOW, "-PT0.001S"), List.of(WINDOW, "PT1S"), List.of(WINDOW, "soon"),
                List.of(CONCURRENCY, "0"), List.of(CONCURRENCY, "65"), List.of(CONCURRENCY, "four"));
        for (List<String> pair : refused) {
            String key = pair.get(0);
            String value = pair.get(1);
            // a pool large enough that only the key's own range can refuse it
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(POOL, "1024", key, value), key + "=" + value + " is refused");
            assertTrue(e.getMessage().contains(key), key + "=" + value + ": the refusal names the key: " + e.getMessage());
        }
    }

    /**
     * Architect (CORE-6b red approved; CORE-17 rewrite approved: the bound is over the intake pool now): an unset concurrency is
     * min(4, max(1, intake - 1)); an explicit one above the bound is refused.
     */
    @Test
    void anUnsetConcurrencyDefaultsBelowTheIntakePool() {
        assertEquals(3, read(INTAKE, "4").concurrency(), "intake 4 (of 21), concurrency unset: min(4, 3)");
        assertEquals(1, read(INTAKE, "1").concurrency(), "intake 1, concurrency unset: max(1, 0)");
        assertEquals(1, read(INTAKE, "2").concurrency(), "intake 2, concurrency unset: min(4, 1)");
        assertEquals(4, read(POOL, "32", INTAKE, "16").concurrency(), "intake 16 (of 32), concurrency unset: min(4, 15)");
        assertEquals(1, read(POOL, "4").concurrency(), "pool 4, intake unset (2): min(4, 1) — no longer min(4, 3) over the pool");
        assertEquals(3, read(POOL, "8").concurrency(), "pool 8, intake unset (4): min(4, 3)");
        assertEquals(4, read(POOL, "16").concurrency(), "pool 16, intake unset (5): min(4, 4)");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(INTAKE, "4", CONCURRENCY, "4"),
                "intake 4 with an explicit concurrency 4 is refused");
        assertTrue(e.getMessage().contains(CONCURRENCY) && e.getMessage().contains(INTAKE), "naming both keys: " + e.getMessage());
    }

    @Test
    void singleIsAGroupOfOneWithNoWindow() {
        assertEquals(1, NodeConfig.IntakeGroup.SINGLE.max(), "a group of one");
        assertEquals(Duration.ZERO, NodeConfig.IntakeGroup.SINGLE.window(), "that waits for nothing");
        assertEquals(new NodeConfig.IntakeGroup(1, 64L << 20, Duration.ZERO, 1024), NodeConfig.IntakeGroup.SINGLE,
                "by value (review r1, architect): one member, 64 MiB, no window, 1024 in flight");
    }

    @Test
    void concurrencyNotBelowTheIntakePoolIsRefusedNamingBoth() {
        for (String[] refused : new String[][] {{"4", "4"}, {"5", "4"}, {"16", "16"}, {"64", "16"}}) {
            // a total of 1024: only the intake pool can bound the concurrency
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(CONCURRENCY, refused[0], POOL, "1024", INTAKE, refused[1]),
                    "concurrency " + refused[0] + " over an intake pool of " + refused[1] + " is refused");
            assertTrue(e.getMessage().contains(CONCURRENCY) && e.getMessage().contains(INTAKE), "the refusal names both keys: " + e.getMessage());
        }
        assertEquals(3, read(CONCURRENCY, "3", INTAKE, "4").concurrency(), "one below the intake pool is accepted");
        assertEquals(4, read(INTAKE, "5").concurrency(), "the default 4 below an intake pool of 5");
        assertEquals(1, read(CONCURRENCY, "1", INTAKE, "1").concurrency(), "the bound is max(1, intake - 1): one over an intake pool of one");
        IllegalArgumentException two = assertThrows(IllegalArgumentException.class, () -> read(CONCURRENCY, "2", INTAKE, "1"),
                "two over an intake pool of one is above max(1, intake - 1)");
        assertTrue(two.getMessage().contains(CONCURRENCY) && two.getMessage().contains(INTAKE), "naming both keys: " + two.getMessage());
        IllegalArgumentException overIntake = assertThrows(IllegalArgumentException.class, () -> read(CONCURRENCY, "8", POOL, "9", INTAKE, "8"),
                "8 is below the total 9 (the old bound) but above max(1, 8 - 1) of the intake pool");
        assertTrue(overIntake.getMessage().contains(CONCURRENCY) && overIntake.getMessage().contains(INTAKE), "naming both keys: " + overIntake.getMessage());
    }
}
