package io.flottila.runtime.node;

import io.flottila.runtime.journal.JournalConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static io.flottila.runtime.node.NodeConfigTest.minimal;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-4c3 packet items 5 and 6: the journal keys this task adds to the node's configuration, each with its default, finite and
 * range-checked, a refusal an {@link IllegalArgumentException} naming the key (NodeConfig is the only env reader) —
 * {@code FLOTTILA_JOURNAL_MAX_BYTES} (16 GiB, CORE-3 item 11: the start's free-space check compares it with the file system),
 * any positive size; {@code FLOTTILA_JOURNAL_READ_CHANNELS} (64: the open-channel cache of the reads, CORE-3 item 8), 1..1024 —
 * one file descriptor each, the bound keeps a node's descriptors finite.
 *
 * <p>The API assumed (CORE-4c3, test-author): {@code JournalConfig.maxBytes()}, {@code JournalConfig.readChannels()}; the
 * default 64 and the range 1..1024 are the test-author's proposal for the architect.
 */
class NodeConfigJournalLimitsTest {

    static Map<String, String> env() {
        return with(minimal(), "FLOTTILA_JOURNAL_KEY", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    }

    @Test
    void theNewJournalKeysHaveTheirDefaults() {
        JournalConfig j = NodeConfig.from(env()).journal();
        assertEquals(16L << 30, j.maxBytes(), "FLOTTILA_JOURNAL_MAX_BYTES 16 GiB");
        assertEquals(64, j.readChannels(), "FLOTTILA_JOURNAL_READ_CHANNELS 64");
    }

    @Test
    void theNewJournalKeysAreReadUnderTheirNamesAtTheirEdges() {
        assertEquals(1L << 50, NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_MAX_BYTES", String.valueOf(1L << 50))).journal().maxBytes(),
                "a size above 2^32: read as a long");
        assertEquals(1, NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_MAX_BYTES", "1")).journal().maxBytes());
        assertEquals(1, NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_READ_CHANNELS", "1")).journal().readChannels());
        assertEquals(1024, NodeConfig.from(with(env(), "FLOTTILA_JOURNAL_READ_CHANNELS", "1024")).journal().readChannels());
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "FLOTTILA_JOURNAL_MAX_BYTES, 0",
            "FLOTTILA_JOURNAL_MAX_BYTES, -1",
            "FLOTTILA_JOURNAL_MAX_BYTES, unlimited",
            "FLOTTILA_JOURNAL_MAX_BYTES, 9223372036854775808",
            "FLOTTILA_JOURNAL_READ_CHANNELS, 0",
            "FLOTTILA_JOURNAL_READ_CHANNELS, -1",
            "FLOTTILA_JOURNAL_READ_CHANNELS, 1025",
            "FLOTTILA_JOURNAL_READ_CHANNELS, 4294967297",
            "FLOTTILA_JOURNAL_READ_CHANNELS, many",
    })
    void aNewJournalKeyOutOfItsRangeIsRefusedNamingIt(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(env(), key, value)));
        assertTrue(e.getMessage().contains(key), "names " + key + ": " + e.getMessage());
    }
}
