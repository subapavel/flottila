package io.rail8.runtime.node;

import io.rail8.core.flow.RetryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;

import static io.rail8.runtime.node.NodeConfigTest.minimal;
import static io.rail8.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14a P1 (D-135 draft, O1): the node's default schedule for an at-least-once flow that names no {@code delivery.retries} —
 * {@code RAIL8_RETRIES_COUNT} (3, 0..100) and {@code RAIL8_RETRIES_INTERVAL} (PT5M, PT1S..PT24H, ISO-8601), read by
 * NodeConfig only; the node default has no growth: multiplier 1, the interval its own cap. A value out of range is an
 * {@link IllegalArgumentException} naming the key.
 *
 * <p>The API assumed (CORE-14a, test-author): {@code NodeConfig.retries()} answering the node default as the core's
 * {@link RetryPolicy}{@code (count, interval, interval, 1.0)} — the policy a flow without {@code delivery.retries} runs under.
 */
class NodeConfigRetriesTest {

    static Map<String, String> env() {
        return with(minimal(), "RAIL8_JOURNAL_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    @Test
    void theRetriesKeysHaveTheirDefaults() {
        assertEquals(new RetryPolicy(3, Duration.ofMinutes(5), Duration.ofMinutes(5), 1.0), NodeConfig.from(env()).retries(),
                "3 retries 5 minutes apart, no growth (O1)");
    }

    @Test
    void theRetriesKeysAreReadUnderTheirNamesAtTheirEdges() {
        assertEquals(0, NodeConfig.from(with(env(), "RAIL8_RETRIES_COUNT", "0")).retries().attempts(), "0: park at the first failure");
        assertEquals(100, NodeConfig.from(with(env(), "RAIL8_RETRIES_COUNT", "100")).retries().attempts(), "the upper edge");
        assertEquals(new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(1), 1.0),
                NodeConfig.from(with(env(), "RAIL8_RETRIES_INTERVAL", "PT1S")).retries(), "the lower edge, its own cap");
        assertEquals(new RetryPolicy(3, Duration.ofHours(24), Duration.ofHours(24), 1.0),
                NodeConfig.from(with(env(), "RAIL8_RETRIES_INTERVAL", "PT24H")).retries(), "the upper edge");
        assertEquals(Duration.ofMillis(1_500), NodeConfig.from(with(env(), "RAIL8_RETRIES_INTERVAL", "PT1.5S")).retries().initial(),
                "a fraction of a second is kept, not truncated to whole seconds");
        NodeConfig both = NodeConfig.from(with(with(env(), "RAIL8_RETRIES_COUNT", "7"), "RAIL8_RETRIES_INTERVAL", "PT30S"));
        assertEquals(new RetryPolicy(7, Duration.ofSeconds(30), Duration.ofSeconds(30), 1.0), both.retries(), "each key read under its own name");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "RAIL8_RETRIES_COUNT, -1",
            "RAIL8_RETRIES_COUNT, 101",
            "RAIL8_RETRIES_COUNT, three",
            "RAIL8_RETRIES_COUNT, 2.5",
            "RAIL8_RETRIES_INTERVAL, PT0S",
            "RAIL8_RETRIES_INTERVAL, PT0.999S",
            "RAIL8_RETRIES_INTERVAL, -PT5M",
            "RAIL8_RETRIES_INTERVAL, PT24H0.001S",
            "RAIL8_RETRIES_INTERVAL, P2D",
            "RAIL8_RETRIES_INTERVAL, 5m",
            "RAIL8_RETRIES_INTERVAL, 300",
    })
    void aRetriesKeyOutOfItsRangeIsRefusedNamingIt(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(env(), key, value)));
        assertTrue(e.getMessage().contains(key), "names " + key + ": " + e.getMessage());
    }
}
