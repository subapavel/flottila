package dev.rail8.runtime.node;

import dev.rail8.runtime.journal.peer.PeerConfig;
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
 * CORE-5a acceptance A4 (packet S10, S11): the peer link's keys and the journal's copies, read by NodeConfig only —
 * {@code RAIL8_PEER_BIND} (0.0.0.0), {@code RAIL8_PEER_PORT} (8083; 0 = any free port; 0..65535; 8082 is the compose
 * async door), {@code RAIL8_PEER_HOST} (the node id), {@code RAIL8_PEER_TIMEOUT} (PT2S; PT0.1S..PT30S),
 * {@code RAIL8_PEER_READS} (16; 1..64 per peer, CORE-5a2 P3), {@code RAIL8_JOURNAL_COPIES} (1..2; unset 1 in dev and ci, 2 in every
 * deployed environment). A value out of its range or unparseable is an {@link IllegalArgumentException} naming the key;
 * {@code RAIL8_JOURNAL_KEY_GENERATE=true} with more than one copy is refused naming both keys (a generated key is one dev
 * node's; a peer could not read it, D-106) — with one copy it is not.
 *
 * <p>The API assumed (CORE-5a, test-author): {@code NodeConfig.peer()} → {@code dev.rail8.runtime.journal.peer.PeerConfig(
 * String bind, int port, String host, Duration timeout, int reads)}; {@code NodeConfig.journalCopies()} → {@code int}.
 */
class NodeConfigPeerTest {

    static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    static Map<String, String> env() {
        return with(minimal(), "RAIL8_JOURNAL_KEY", KEY);   // ci, node-1
    }

    static Map<String, String> deployed() {
        return with(with(env(), "RAIL8_ENV", "prod"), "RAIL8_ADMIN_TOKEN", "t0ken");
    }

    @Test
    void thePeerKeysHaveTheirDefaults() {
        NodeConfig c = NodeConfig.from(env());
        assertEquals(new PeerConfig("0.0.0.0", 8083, "node-1", Duration.ofSeconds(2), 16), c.peer(),
                "bind 0.0.0.0, port 8083, the node id as host, PT2S, 16 reads per peer");
        assertEquals("node-7", NodeConfig.from(with(env(), "RAIL8_NODE_ID", "node-7")).peer().host(), "the host defaults to this node's id");
    }

    @Test
    void thePeerKeysAreReadUnderTheirNames() {
        Map<String, String> e = env();
        e.put("RAIL8_PEER_BIND", "10.1.2.3");
        e.put("RAIL8_PEER_PORT", "9083");
        e.put("RAIL8_PEER_HOST", "rail8-1.internal");
        e.put("RAIL8_PEER_TIMEOUT", "PT5S");
        e.put("RAIL8_PEER_READS", "32");
        assertEquals(new PeerConfig("10.1.2.3", 9083, "rail8-1.internal", Duration.ofSeconds(5), 32), NodeConfig.from(e).peer());
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "RAIL8_PEER_PORT, 0",
            "RAIL8_PEER_PORT, 65535",
            "RAIL8_PEER_TIMEOUT, PT0.1S",
            "RAIL8_PEER_TIMEOUT, PT30S",
            "RAIL8_PEER_READS, 1",
            "RAIL8_PEER_READS, 64",
    })
    void bothBoundsOfEachPeerRangeAreAccepted(String key, String value) {
        PeerConfig p = NodeConfig.from(with(env(), key, value)).peer();
        Object read = switch (key) {
            case "RAIL8_PEER_PORT" -> String.valueOf(p.port());
            case "RAIL8_PEER_TIMEOUT" -> p.timeout().toString();
            default -> String.valueOf(p.reads());
        };
        assertEquals(value, read, key + " at its edge, read as given");
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "RAIL8_PEER_PORT, -1",
            "RAIL8_PEER_PORT, 65536",
            "RAIL8_PEER_PORT, peer",
            "RAIL8_PEER_TIMEOUT, PT0.099S",
            "RAIL8_PEER_TIMEOUT, PT30.001S",
            "RAIL8_PEER_TIMEOUT, PT0S",
            "RAIL8_PEER_TIMEOUT, -PT2S",
            "RAIL8_PEER_TIMEOUT, 2s",
            "RAIL8_PEER_READS, 0",
            "RAIL8_PEER_READS, 65",
            "RAIL8_PEER_READS, 257",
            "RAIL8_PEER_READS, many",
            "RAIL8_JOURNAL_COPIES, 0",
            "RAIL8_JOURNAL_COPIES, 3",
            "RAIL8_JOURNAL_COPIES, two",
    })
    void aPeerKeyOutOfItsRangeOrUnparseableIsRefusedNamingIt(String key, String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(env(), key, value)));
        assertTrue(String.valueOf(e.getMessage()).contains(key), "names " + key + ": " + e.getMessage());
    }

    /** CORE-5a2 A3 (test-author): 65 reads are refused naming the key and its range 1..64 (as "between 1 and 64" or "1..64"). */
    @Test
    void peerReadsAbove64AreRefusedNamingTheKeyAndItsRange() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(env(), "RAIL8_PEER_READS", "65")));
        String m = String.valueOf(e.getMessage());
        assertTrue(m.contains("RAIL8_PEER_READS") && (m.contains("between 1 and 64") || m.contains("1..64")), "names the key and 1..64: " + m);
        assertEquals(64, NodeConfig.from(with(env(), "RAIL8_PEER_READS", "64")).peer().reads(), "witness: 64 is read as given");
    }

    @Test
    void copiesDefaultToOneInDevAndCiAndToTwoWhenDeployed() {
        assertEquals(1, NodeConfig.from(env()).journalCopies(), "ci: one copy");
        assertEquals(1, NodeConfig.from(with(env(), "RAIL8_ENV", "dev")).journalCopies(), "dev: one copy");
        assertEquals(2, NodeConfig.from(deployed()).journalCopies(), "prod: two copies");
        assertEquals(2, NodeConfig.from(with(deployed(), "RAIL8_ENV", "test")).journalCopies(), "test is deployed: two copies");
        assertEquals(1, NodeConfig.from(with(deployed(), "RAIL8_JOURNAL_COPIES", "1")).journalCopies(), "1 as written, deployed too");
        assertEquals(2, NodeConfig.from(with(env(), "RAIL8_JOURNAL_COPIES", "2")).journalCopies(), "2 as written, in ci too");
    }

    @Test
    void aGeneratedKeyWithMoreThanOneCopyIsRefusedNamingBothKeys() {
        Map<String, String> generated = with(with(minimal(), "RAIL8_JOURNAL_KEY_GENERATE", "true"), "RAIL8_JOURNAL_COPIES", "2");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(generated));
        assertTrue(e.getMessage().contains("RAIL8_JOURNAL_KEY_GENERATE") && e.getMessage().contains("RAIL8_JOURNAL_COPIES"),
                "names both keys: " + e.getMessage());

        Map<String, String> deployedDefault = with(with(with(minimal(), "RAIL8_ENV", "prod"), "RAIL8_ADMIN_TOKEN", "t0ken"),
                "RAIL8_JOURNAL_KEY_GENERATE", "true");
        IllegalArgumentException unset = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(deployedDefault),
                "deployed, copies unset: two copies, so a generated key is refused too");
        assertTrue(unset.getMessage().contains("RAIL8_JOURNAL_COPIES"), "names the copies: " + unset.getMessage());

        assertEquals(1, NodeConfig.from(with(minimal(), "RAIL8_JOURNAL_KEY_GENERATE", "true")).journalCopies(),
                "witness: dev/ci default one copy, a generated key starts");
        assertEquals(1, NodeConfig.from(with(with(deployedDefault, "RAIL8_JOURNAL_COPIES", "1"), "RAIL8_ENV", "prod")).journalCopies(),
                "witness: one copy written, a generated key is not refused for its copies");
    }
}
