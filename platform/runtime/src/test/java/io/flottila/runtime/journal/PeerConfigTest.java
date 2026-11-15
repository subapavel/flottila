package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An in-process {@link PeerConfig} is held to the ranges the env reader names (CORE-5a S11; implementer, beside
 * {@code NodeConfigPeerTest}, whose refusals come from the env reader first): each refusal names its key.
 */
class PeerConfigTest {

    @ParameterizedTest(name = "{0}: {1} {2} {3} {4} {5}")
    @CsvSource({
            "FLOTTILA_PEER_BIND, ' ', 8083, node-1, PT2S, 16",
            "FLOTTILA_PEER_HOST, 0.0.0.0, 8083, ' ', PT2S, 16",
            "FLOTTILA_PEER_PORT, 0.0.0.0, -1, node-1, PT2S, 16",
            "FLOTTILA_PEER_PORT, 0.0.0.0, 65536, node-1, PT2S, 16",
            "FLOTTILA_PEER_TIMEOUT, 0.0.0.0, 8083, node-1, PT0.099S, 16",
            "FLOTTILA_PEER_TIMEOUT, 0.0.0.0, 8083, node-1, PT30.001S, 16",
            "FLOTTILA_PEER_READS, 0.0.0.0, 8083, node-1, PT2S, 0",
            "FLOTTILA_PEER_READS, 0.0.0.0, 8083, node-1, PT2S, 65",
            "FLOTTILA_PEER_READS, 0.0.0.0, 8083, node-1, PT2S, 257",
    })
    void anInProcessConfigOutOfItsRangeIsRefusedNamingTheKey(String key, String bind, int port, String host, String timeout, int reads) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new PeerConfig(bind, port, host, Duration.parse(timeout), reads));
        assertTrue(e.getMessage().contains(key), "names " + key + ": " + e.getMessage());
    }

    /**
     * CORE-5a2 A3 (P3, test-author): an in-process config takes 64 reads and refuses 65, naming the key and its range 1..64
     * (assumed text: "between 1 and 64" as the other ranges, or "1..64").
     */
    @Test
    void anInProcessConfigTakes64ReadsAndRefuses65NamingTheKeyAndItsRange() {
        assertEquals(64, new PeerConfig("0.0.0.0", 8083, "node-1", Duration.ofSeconds(2), 64).reads(), "64 reads, the most");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new PeerConfig("0.0.0.0", 8083, "node-1", Duration.ofSeconds(2), 65));
        String m = String.valueOf(e.getMessage());
        assertTrue(m.contains("FLOTTILA_PEER_READS") && (m.contains("between 1 and 64") || m.contains("1..64")), "names the key and 1..64: " + m);
    }
}
