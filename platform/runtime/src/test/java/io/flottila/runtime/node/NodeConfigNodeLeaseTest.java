package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Map;

import static io.flottila.runtime.node.NodeConfigTest.minimal;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-15a acceptance 1 (D-101 (1)): the node lease's keys, read by NodeConfig only — {@code FLOTTILA_NODE_LEASE} (PT30S;
 * {@code > 0}, {@code ≤ PT10M}) and {@code FLOTTILA_NODE_LEASE_RENEW} (PT5S; {@code > 0}, {@code ≤} the lease / 4); a value
 * out of range is an {@link IllegalArgumentException} naming the key.
 *
 * <p>The API assumed (P2-15a, test-author): {@code NodeConfig.nodeLease()} and {@code NodeConfig.nodeLeaseRenew()}, each a
 * {@link Duration}.
 */
class NodeConfigNodeLeaseTest {

    static Map<String, String> env() {
        return with(minimal(), "FLOTTILA_JOURNAL_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    @Test
    void theNodeLeaseKeysHaveTheirDefaults() {
        NodeConfig c = NodeConfig.from(env());
        assertEquals(Duration.ofSeconds(30), c.nodeLease());
        assertEquals(Duration.ofSeconds(5), c.nodeLeaseRenew());
    }

    @Test
    void theNodeLeaseKeysAreReadUnderTheirNamesAtTheirEdges() {
        assertEquals(Duration.ofMinutes(10), NodeConfig.from(with(env(), "FLOTTILA_NODE_LEASE", "PT10M")).nodeLease(), "the upper edge");
        assertEquals(Duration.ofMillis(1), NodeConfig.from(with(with(env(), "FLOTTILA_NODE_LEASE", "PT0.001S"), "FLOTTILA_NODE_LEASE_RENEW", "PT0.00025S"))
                .nodeLease(), "any positive lease, with a renew of at most a quarter of it");
        assertEquals(Duration.ofMillis(7_500), NodeConfig.from(with(env(), "FLOTTILA_NODE_LEASE_RENEW", "PT7.5S")).nodeLeaseRenew(),
                "a quarter of the default lease");
        NodeConfig minute = NodeConfig.from(with(with(env(), "FLOTTILA_NODE_LEASE", "PT1M"), "FLOTTILA_NODE_LEASE_RENEW", "PT15S"));
        assertEquals(Duration.ofMinutes(1), minute.nodeLease());
        assertEquals(Duration.ofSeconds(15), minute.nodeLeaseRenew(), "a quarter of the lease given");
        assertEquals(Duration.ofNanos(1), NodeConfig.from(with(env(), "FLOTTILA_NODE_LEASE_RENEW", "PT0.000000001S")).nodeLeaseRenew(),
                "any positive renew period");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "FLOTTILA_NODE_LEASE, PT0S",
            "FLOTTILA_NODE_LEASE, -PT30S",
            "FLOTTILA_NODE_LEASE, PT10M0.001S",
            "FLOTTILA_NODE_LEASE, PT1H",
            "FLOTTILA_NODE_LEASE, 30",
            "FLOTTILA_NODE_LEASE, forever",
            "FLOTTILA_NODE_LEASE_RENEW, PT0S",
            "FLOTTILA_NODE_LEASE_RENEW, -PT5S",
            "FLOTTILA_NODE_LEASE_RENEW, PT7.501S",
            "FLOTTILA_NODE_LEASE_RENEW, PT30S",
            "FLOTTILA_NODE_LEASE_RENEW, 5",
    })
    void aNodeLeaseKeyOutOfItsRangeIsRefusedNamingIt(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(env(), key, value)));
        assertTrue(e.getMessage().contains(key), "names " + key + ": " + e.getMessage());
    }

    @Test
    void aRenewPeriodAboveAQuarterOfTheLeaseGivenIsRefusedNamingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(with(env(), "FLOTTILA_NODE_LEASE", "PT1M"), "FLOTTILA_NODE_LEASE_RENEW", "PT15.001S")));
        assertTrue(e.getMessage().contains("FLOTTILA_NODE_LEASE_RENEW"), e.getMessage());
        IllegalArgumentException shorter = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(env(), "FLOTTILA_NODE_LEASE", "PT8S")),   // the default renew PT5S > PT8S / 4
                "a shorter lease refuses the default renew period it no longer fits");
        assertTrue(shorter.getMessage().contains("FLOTTILA_NODE_LEASE"), shorter.getMessage());
    }
}
