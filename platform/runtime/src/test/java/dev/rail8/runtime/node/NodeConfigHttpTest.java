package dev.rail8.runtime.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static dev.rail8.runtime.node.NodeConfigTest.minimal;
import static dev.rail8.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node's HTTP port, shared by every in endpoint of an adapter with an HTTP prefix: {@code RAIL8_HTTP_HOST} (127.0.0.1) and
 * {@code RAIL8_HTTP_PORT} (8080; 0..65535, 0 any free port). A port out of its range or unparseable is refused naming the key. A
 * port other than 0 that is the admin API's ({@code RAIL8_ADMIN_PORT}) or the peer link's ({@code RAIL8_PEER_PORT}) is refused
 * naming both keys, whatever the hosts: each of the three listens on a port of its own. {@code RAIL8_HTTP_ORIGIN} (none) is the
 * origin senders call the port at, behind a proxy or a published container port: {@code http} or {@code https}, a host and an
 * optional port, and nothing else.
 */
class NodeConfigHttpTest {

    @Test
    void theHttpKeysHaveTheirDefaults() {
        assertEquals(new NodeConfig.Http("127.0.0.1", 8080, Optional.empty()), NodeConfig.from(minimal()).http(), "loopback, port 8080, no advertised origin");
        assertEquals(NodeConfig.Http.DEFAULTS, NodeConfig.from(minimal()).http());
    }

    @Test
    void theHttpKeysAreReadUnderTheirNames() {
        Map<String, String> e = with(with(minimal(), "RAIL8_HTTP_HOST", "0.0.0.0"), "RAIL8_HTTP_PORT", "9080");
        assertEquals(new NodeConfig.Http("0.0.0.0", 9080, Optional.empty()), NodeConfig.from(e).http());
    }

    @ParameterizedTest(name = "RAIL8_HTTP_PORT={0}")
    @ValueSource(ints = {0, 1, 65535})
    void bothBoundsOfThePortAreAccepted(int port) {
        assertEquals(port, NodeConfig.from(with(minimal(), "RAIL8_HTTP_PORT", String.valueOf(port))).http().port(), "read as given");
    }

    @ParameterizedTest(name = "RAIL8_HTTP_PORT={0}")
    @ValueSource(strings = {"-1", "65536", "eighty", ""})
    void aPortOutOfItsRangeOrUnparseableIsRefusedNamingTheKey(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(minimal(), "RAIL8_HTTP_PORT", value)));
        assertTrue(String.valueOf(e.getMessage()).contains("RAIL8_HTTP_PORT"), "names the key: " + e.getMessage());
    }

    @ParameterizedTest(name = "{0}={1}, RAIL8_HTTP_PORT={1}")
    @CsvSource({
            "RAIL8_ADMIN_PORT, 9081",
            "RAIL8_PEER_PORT, 9083",
    })
    void anHttpPortThatIsTheAdminOrPeerPortIsRefusedNamingBoth(String other, String port) {
        Map<String, String> e = with(with(minimal(), other, port), "RAIL8_HTTP_PORT", port);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(e));
        assertEquals("RAIL8_HTTP_PORT " + port + " is " + other + " too: the node's HTTP port, its admin API and its peer link each listen on a port of their own",
                refused.getMessage());
    }

    @Test
    void theDefaultAdminAndPeerPortsAreRefusedAsTheHttpPortWhateverTheHosts() {
        NodeConfig defaults = NodeConfig.from(minimal());
        Map<String, String> onAdmin = with(with(minimal(), "RAIL8_HTTP_HOST", "10.0.0.1"), "RAIL8_HTTP_PORT", String.valueOf(defaults.admin().port()));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(onAdmin)).getMessage().contains("RAIL8_ADMIN_PORT"),
                "the admin API's default port, on another host");
        Map<String, String> onPeer = with(minimal(), "RAIL8_HTTP_PORT", String.valueOf(defaults.peer().port()));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(onPeer)).getMessage().contains("RAIL8_PEER_PORT"),
                "the peer link's default port");
    }

    @Test
    void portZeroIsNeverAClash() {
        Map<String, String> e = with(with(with(minimal(), "RAIL8_HTTP_PORT", "0"), "RAIL8_ADMIN_PORT", "0"), "RAIL8_PEER_PORT", "0");
        NodeConfig c = NodeConfig.from(e);
        assertEquals(0, c.http().port(), "any free port: the three bind ports of their own");
        assertEquals(0, c.admin().port());
        assertEquals(0, c.peer().port());
    }

    @ParameterizedTest(name = "RAIL8_HTTP_ORIGIN={0}")
    @ValueSource(strings = {"http://localhost:8080", "https://rail8.example.com", "https://10.0.0.5:443", "http://[::1]:8080"})
    void anOriginIsReadAsGiven(String origin) {
        assertEquals(Optional.of(origin), NodeConfig.from(with(minimal(), "RAIL8_HTTP_ORIGIN", origin)).http().origin());
    }

    @ParameterizedTest(name = "RAIL8_HTTP_ORIGIN={0}")
    @ValueSource(strings = {"", "localhost:8080", "ftp://host", "https://", "https://host/", "https://host/rail8", "https://host?x=1",
            "https://host#top", "https://user@host", "https://host:99999", "http://ho st", "HTTPS://host"})
    void anOriginWithAnythingButASchemeAHostAndAPortIsRefusedNamingTheKey(String origin) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(minimal(), "RAIL8_HTTP_ORIGIN", origin)));
        assertTrue(String.valueOf(e.getMessage()).startsWith("RAIL8_HTTP_ORIGIN "), "names the key: " + e.getMessage());
    }
}
