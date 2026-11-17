package dev.rail8.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.rail8.runtime.node.NodeConfigTest.minimal;
import static dev.rail8.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RAIL8_JQ_MAX_RECURSION_DEPTH}: the recursion depth of a jq step whose flow sets none, 10 000 unset, 1 to 4 194 304
 * as written; anything else refuses the configuration naming the key.
 */
class NodeConfigJqTest {

    @Test
    void theDepthIsTenThousandUnsetAndAsWrittenOtherwise() {
        assertEquals(10_000, NodeConfig.from(minimal()).jqMaxRecursionDepth());
        assertEquals(NodeConfig.JQ_MAX_RECURSION_DEPTH_DEFAULT, NodeConfig.from(minimal()).jqMaxRecursionDepth());
        assertEquals(1, NodeConfig.from(with(minimal(), "RAIL8_JQ_MAX_RECURSION_DEPTH", "1")).jqMaxRecursionDepth());
        assertEquals(4_194_304, NodeConfig.from(with(minimal(), "RAIL8_JQ_MAX_RECURSION_DEPTH", "4194304")).jqMaxRecursionDepth());
    }

    @Test
    void theDepthSurvivesACopyWithOtherSecrets() {
        NodeConfig deep = NodeConfig.from(with(minimal(), "RAIL8_JQ_MAX_RECURSION_DEPTH", "20000"));
        assertEquals(20_000, deep.withSecrets(deep.secrets()).jqMaxRecursionDepth());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "4194305", "deep", ""})
    void aDepthOutOfItsRangeOrUnparseableIsRefusedNamingTheKey(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(minimal(), "RAIL8_JQ_MAX_RECURSION_DEPTH", value)));
        assertTrue(e.getMessage().contains("RAIL8_JQ_MAX_RECURSION_DEPTH"), e.getMessage());
    }

    @Test
    void anInProcessConfigurationIsHeldToTheSameRange() {
        NodeConfig c = NodeConfig.from(minimal());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new NodeConfig(c.nodeId(), c.env(), c.admin(), c.dataDir(),
                c.stopDeadline(), c.maxPackageBytes(), c.pg(), c.blobs(), c.archives(), c.s3(), c.secrets(), c.leases(), c.retention(),
                c.cpuSampling(), c.targets(), c.journal(), c.journalKey(), c.journalSweep(), c.intakeGroup(), c.nodeLease(), c.nodeLeaseRenew(),
                c.retries(), c.peer(), c.journalCopies(), 0));
        assertTrue(e.getMessage().contains("RAIL8_JQ_MAX_RECURSION_DEPTH"), e.getMessage());
    }
}
