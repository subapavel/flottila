package io.rail8.runtime.node;

import io.rail8.runtime.journal.JournalConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static io.rail8.runtime.node.NodeConfigTest.MIB;
import static io.rail8.runtime.node.NodeConfigTest.minimal;
import static io.rail8.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal's keys in the node's configuration (CORE-4c1 packet Scope 6, CORE-3 items 1, 3, 4 and 9):
 * {@code RAIL8_JOURNAL_DIR} ({@code <dataDir>/journal}) and the journal limits, each with its design default, each
 * finite and range-checked, a refusal an {@link IllegalArgumentException} naming the key (NodeConfig's rule);
 * {@code RAIL8_JOURNAL_RECORD_MAX} at most {@code RAIL8_BLOB_MEMORY_TIER} (CORE-3 item 5: "RECORD_MAX ≤ memory tier,
 * checked at config"). The node id is the journal's.
 *
 * <p>The API these cases compile against (proposed by the test-author, CORE-4c1 handback): {@code JournalConfig
 * NodeConfig.journal()}. Every env here carries a master key, so the cases hold whether or not {@code from} reads it.
 */
class NodeConfigJournalTest {

    static Map<String, String> env() {
        return with(minimal(), "RAIL8_JOURNAL_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    @Test
    void theJournalLivesUnderTheDataDirectoryWithTheDesignDefaults() {
        JournalConfig j = NodeConfig.from(with(env(), "RAIL8_DATA_DIR", "/srv/flottila")).journal();
        assertAll(
                () -> assertEquals(Path.of("/srv/flottila", "journal"), j.dir(), "RAIL8_JOURNAL_DIR defaults to <dataDir>/journal"),
                () -> assertEquals(64 * MIB, j.segmentBytes(), "RAIL8_JOURNAL_SEGMENT_BYTES 64 MiB"),
                () -> assertEquals(65_536, j.segmentRecords(), "RAIL8_JOURNAL_SEGMENT_RECORDS 65 536"),
                () -> assertEquals(Duration.ofSeconds(1), j.segmentAge(), "RAIL8_JOURNAL_SEGMENT_AGE PT1S"),
                () -> assertEquals(16 * MIB, j.pendingBytes(), "RAIL8_JOURNAL_PENDING_BYTES 16 MiB"),
                () -> assertEquals(Duration.ofSeconds(5), j.appendWait(), "RAIL8_JOURNAL_APPEND_WAIT PT5S"),
                () -> assertEquals(256 * 1024L, j.recordMax(), "RAIL8_JOURNAL_RECORD_MAX 256 KiB"),
                () -> assertEquals("node-1", j.node(), "the node id"));
    }

    @Test
    void everyJournalKeyIsReadUnderItsOwnName() {
        Map<String, String> env = env();
        env.put("RAIL8_JOURNAL_DIR", "/mnt/journal-pv");
        env.put("RAIL8_JOURNAL_SEGMENT_BYTES", String.valueOf(8 * MIB));
        env.put("RAIL8_JOURNAL_SEGMENT_RECORDS", "1000");
        env.put("RAIL8_JOURNAL_SEGMENT_AGE", "PT0.25S");
        env.put("RAIL8_JOURNAL_PENDING_BYTES", String.valueOf(4 * MIB));
        env.put("RAIL8_JOURNAL_APPEND_WAIT", "PT2S");
        env.put("RAIL8_JOURNAL_RECORD_MAX", "65536");

        JournalConfig j = NodeConfig.from(env).journal();

        assertEquals(new JournalConfig(Path.of("/mnt/journal-pv"), 8 * MIB, 1000, Duration.ofMillis(250), 4 * MIB, Duration.ofSeconds(2), 65_536, "node-1"), j);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "RAIL8_JOURNAL_SEGMENT_BYTES, 0",
            "RAIL8_JOURNAL_SEGMENT_BYTES, -1",
            "RAIL8_JOURNAL_SEGMENT_BYTES, lots",
            "RAIL8_JOURNAL_SEGMENT_RECORDS, 0",
            "RAIL8_JOURNAL_SEGMENT_RECORDS, 4294967296",
            "RAIL8_JOURNAL_SEGMENT_AGE, PT0S",
            "RAIL8_JOURNAL_SEGMENT_AGE, PT1H0.001S",
            "RAIL8_JOURNAL_SEGMENT_AGE, 1s",
            "RAIL8_JOURNAL_PENDING_BYTES, 0",
            "RAIL8_JOURNAL_PENDING_BYTES, 262143",
            "RAIL8_JOURNAL_APPEND_WAIT, PT0.099S",
            "RAIL8_JOURNAL_APPEND_WAIT, PT1M0.001S",
            "RAIL8_JOURNAL_APPEND_WAIT, soon",
            "RAIL8_JOURNAL_RECORD_MAX, 4095",
            "RAIL8_JOURNAL_RECORD_MAX, 1048577",
            "RAIL8_JOURNAL_RECORD_MAX, x",
    })
    void aJournalKeyOutOfItsRangeIsRefusedNamingIt(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(env(), key, value)));
        assertTrue(e.getMessage().contains(key), "names " + key + ": " + e.getMessage());
    }

    @Test
    void theRecordMaxIsBoundByTheMemoryTierAndMayReachIt() {
        // the default memory tier is 1 MiB: one byte more is refused naming both keys, the tier itself is taken
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NodeConfig.from(with(env(), "RAIL8_JOURNAL_RECORD_MAX", String.valueOf(MIB + 1))));
        assertTrue(e.getMessage().contains("RAIL8_JOURNAL_RECORD_MAX") && e.getMessage().contains("RAIL8_BLOB_MEMORY_TIER"), e.getMessage());
        assertEquals(MIB, NodeConfig.from(with(env(), "RAIL8_JOURNAL_RECORD_MAX", String.valueOf(MIB))).journal().recordMax());

        Map<String, String> wide = with(with(env(), "RAIL8_BLOB_MEMORY_TIER", String.valueOf(4 * MIB)), "RAIL8_JOURNAL_RECORD_MAX", String.valueOf(2 * MIB));
        assertEquals(2 * MIB, NodeConfig.from(wide).journal().recordMax(), "a wider memory tier admits a larger record");
    }

    @Test
    void theJournalBoundsAreTakenAtTheirEdges() {
        Map<String, String> env = env();
        env.put("RAIL8_JOURNAL_SEGMENT_AGE", "PT1H");
        env.put("RAIL8_JOURNAL_APPEND_WAIT", "PT0.1S");
        env.put("RAIL8_JOURNAL_RECORD_MAX", "4096");
        env.put("RAIL8_JOURNAL_PENDING_BYTES", "4096");
        JournalConfig j = NodeConfig.from(env).journal();
        assertEquals(Duration.ofHours(1), j.segmentAge());
        assertEquals(Duration.ofMillis(100), j.appendWait());
        assertEquals(4096, j.recordMax());
        assertEquals(4096, j.pendingBytes(), "the pending bound may equal the record max");
        assertEquals(Duration.ofMinutes(1), NodeConfig.from(with(env(), "RAIL8_JOURNAL_APPEND_WAIT", "PT1M")).journal().appendWait());
    }
}
