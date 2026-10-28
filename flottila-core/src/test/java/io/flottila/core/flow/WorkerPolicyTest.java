package io.flottila.core.flow;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-01 (D-075): {@link WorkerPolicy} gains {@code maxChain} — how many items of one key a worker takes by the same-key fast
 * path after the item it claimed before the key goes back to the seq-ordered claim. At least 0 (0 = the fast path is off); the
 * 3-argument form and {@link WorkerPolicy#DEFAULT} use 32.
 *
 * <p>The shape these tests assume (P2-01 test-author): {@code WorkerPolicy(Duration lease, int maxAttempts, Duration pollInterval,
 * int maxChain)} with the accessor {@code maxChain()}, and the 3-argument constructor kept.
 */
class WorkerPolicyTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration TICK = Duration.ofSeconds(1);

    @Test
    void negativeMaxChainRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> new WorkerPolicy(LEASE, 5, TICK, -1),
                "a negative chain bound is no bound");
        assertTrue(refused.getMessage().contains("maxChain"), "the refusal names maxChain: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("-1"), "and the value it was given: " + refused.getMessage());
        assertEquals(0, new WorkerPolicy(LEASE, 5, TICK, 0).maxChain(), "0 is allowed: the fast path is off");
        assertEquals(1, new WorkerPolicy(LEASE, 5, TICK, 1).maxChain());
    }

    @Test
    void threeArgFormUsesDefaultChain() {
        assertEquals(32, new WorkerPolicy(LEASE, 5, TICK).maxChain(), "the 3-argument form keeps a key for at most 32 fast-path items");
        assertEquals(new WorkerPolicy(LEASE, 5, TICK, 32), new WorkerPolicy(LEASE, 5, TICK), "it is the 4-argument form with 32");
        assertEquals(new WorkerPolicy(Duration.ofSeconds(30), 5, Duration.ofSeconds(1), 32), WorkerPolicy.DEFAULT, "DEFAULT: 30 s, 5, 1 s, 32");
    }
}
