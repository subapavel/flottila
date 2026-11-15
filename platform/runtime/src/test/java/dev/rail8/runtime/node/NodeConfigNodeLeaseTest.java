package dev.rail8.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;

import static dev.rail8.runtime.node.NodeConfigTest.minimal;
import static dev.rail8.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a acceptance 1 (D-101 (1)): the node lease's keys, read by NodeConfig only — {@code RAIL8_NODE_LEASE} (PT30S;
 * {@code > 0}, {@code ≤ PT10M}) and {@code RAIL8_NODE_LEASE_RENEW} (PT5S; {@code > 0}, {@code ≤} the lease / 4); a value
 * out of range is an {@link IllegalArgumentException} naming the key.
 *
 * <p>The API assumed (P2-15a, test-author): {@code NodeConfig.nodeLease()} and {@code NodeConfig.nodeLeaseRenew()}, each a
 * {@link Duration}.
 */
class NodeConfigNodeLeaseTest {

    static Map<String, String> env() {
        return with(minimal(), "RAIL8_JOURNAL_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    @Test
    void theNodeLeaseKeysHaveTheirDefaults() {
        NodeConfig c = NodeConfig.from(env());
        assertEquals(Duration.ofSeconds(30), c.nodeLease());
        assertEquals(Duration.ofSeconds(5), c.nodeLeaseRenew());
    }

    @Test
    void theNodeLeaseKeysAreReadUnderTheirNamesAtTheirEdges() {
        assertEquals(Duration.ofMinutes(10), NodeConfig.from(with(env(), "RAIL8_NODE_LEASE", "PT10M")).nodeLease(), "the upper edge");
        assertEquals(Duration.ofMillis(1), NodeConfig.from(with(with(env(), "RAIL8_NODE_LEASE", "PT0.001S"), "RAIL8_NODE_LEASE_RENEW", "PT0.00025S"))
                .nodeLease(), "any positive lease, with a renew of at most a quarter of it");
        assertEquals(Duration.ofMillis(7_500), NodeConfig.from(with(env(), "RAIL8_NODE_LEASE_RENEW", "PT7.5S")).nodeLeaseRenew(),
                "a quarter of the default lease");
        NodeConfig minute = NodeConfig.from(with(with(env(), "RAIL8_NODE_LEASE", "PT1M"), "RAIL8_NODE_LEASE_RENEW", "PT15S"));
        assertEquals(Duration.ofMinutes(1), minute.nodeLease());
        assertEquals(Duration.ofSeconds(15), minute.nodeLeaseRenew(), "a quarter of the lease given");
        assertEquals(Duration.ofNanos(1), NodeConfig.from(with(env(), "RAIL8_NODE_LEASE_RENEW", "PT0.000000001S")).nodeLeaseRenew(),
                "any positive renew period");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "RAIL8_NODE_LEASE, PT0S",
            "RAIL8_NODE_LEASE, -PT30S",
            "RAIL8_NODE_LEASE, PT10M0.001S",
            "RAIL8_NODE_LEASE, PT1H",
            "RAIL8_NODE_LEASE, 30",
            "RAIL8_NODE_LEASE, forever",
            "RAIL8_NODE_LEASE_RENEW, PT0S",
            "RAIL8_NODE_LEASE_RENEW, -PT5S",
            "RAIL8_NODE_LEASE_RENEW, PT7.501S",
            "RAIL8_NODE_LEASE_RENEW, PT30S",
            "RAIL8_NODE_LEASE_RENEW, 5",
    })
    void aNodeLeaseKeyOutOfItsRangeIsRefusedNamingIt(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(env(), key, value)));
        assertTrue(e.getMessage().contains(key), "names " + key + ": " + e.getMessage());
    }

    @Test
    void aRenewPeriodAboveAQuarterOfTheLeaseGivenIsRefusedNamingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(with(env(), "RAIL8_NODE_LEASE", "PT1M"), "RAIL8_NODE_LEASE_RENEW", "PT15.001S")));
        assertTrue(e.getMessage().contains("RAIL8_NODE_LEASE_RENEW"), e.getMessage());
        IllegalArgumentException shorter = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(env(), "RAIL8_NODE_LEASE", "PT8S")),   // the default renew PT5S > PT8S / 4
                "a shorter lease refuses the default renew period it no longer fits");
        assertTrue(shorter.getMessage().contains("RAIL8_NODE_LEASE"), shorter.getMessage());
    }
}
