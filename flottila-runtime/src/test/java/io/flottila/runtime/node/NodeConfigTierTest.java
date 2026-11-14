package io.flottila.runtime.node;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memory tier and the journal's record bound (CORE-4c1 r1, architect; the implementer's): {@code FLOTTILA_BLOB_MEMORY_TIER}
 * is at least 4096 and refused under its own key below; {@code FLOTTILA_JOURNAL_RECORD_MAX} defaults to 256 KiB or the tier
 * when that is smaller, so a small tier needs no record bound of its own — from the env and in the constructors alike; one
 * given keeps its value.
 */
class NodeConfigTierTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "4095"})
    void aMemoryTierBelow4096IsRefusedUnderItsOwnKey(String tier) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(NodeConfigJournalTest.env(), "FLOTTILA_BLOB_MEMORY_TIER", tier)));

        assertTrue(e.getMessage().startsWith("FLOTTILA_BLOB_MEMORY_TIER") && e.getMessage().contains("4096"), "names its key and its least value: " + e.getMessage());
    }

    @ParameterizedTest(name = "tier {0} -> record max {1}")
    @CsvSource({"4096, 4096", "131072, 131072", "262143, 262143", "262144, 262144", "262145, 262144", "1048576, 262144"})
    void theRecordMaxDefaultsTo256KiBOrTheTierWhenThatIsSmaller(long tier, long recordMax) {
        NodeConfig c = NodeConfig.from(with(NodeConfigJournalTest.env(), "FLOTTILA_BLOB_MEMORY_TIER", String.valueOf(tier)));

        assertEquals(tier, c.blobs().memoryTier());
        assertEquals(recordMax, c.journal().recordMax());
    }

    @ParameterizedTest
    @ValueSource(longs = {4096, 65536})
    void aGivenRecordMaxKeepsItsValueUnderASmallTier(long recordMax) {
        Map<String, String> env = with(with(NodeConfigJournalTest.env(), "FLOTTILA_BLOB_MEMORY_TIER", "131072"), "FLOTTILA_JOURNAL_RECORD_MAX", String.valueOf(recordMax));

        assertEquals(recordMax, NodeConfig.from(env).journal().recordMax());
    }

    @ParameterizedTest
    @ValueSource(longs = {4096, 131072})
    void aNodeConfigBuiltWithoutTheJournalKeysTakesTheSameDefault(long tier) {
        NodeConfig read = NodeConfig.from(with(NodeConfigJournalTest.env(), "FLOTTILA_BLOB_MEMORY_TIER", String.valueOf(tier)));

        NodeConfig built = new NodeConfig(read.nodeId(), read.env(), read.admin(), read.dataDir(), read.stopDeadline(), read.maxPackageBytes(), read.pg(), read.blobs());

        assertEquals(tier, built.journal().recordMax(), "the constructors default the record bound by the tier as from() does");
    }
}
