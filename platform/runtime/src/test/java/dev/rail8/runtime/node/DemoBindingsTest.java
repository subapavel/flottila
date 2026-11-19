package dev.rail8.runtime.node;

import dev.rail8.schemas.Documents;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo package deploys to a node with the default configuration, so none of its endpoints listens on or sends to a
 * port the node itself binds by default — the admin server's, the peer link's or the node's HTTP port — not in its config, not
 * in an environment's override. Its http in endpoints take requests on the node's HTTP port: each names an {@code address} and
 * no host, port or path of its own.
 */
class DemoBindingsTest {

    static final Path ENDPOINTS = Path.of("..", "..", "examples", "demo", "endpoints");

    @Test
    void noDemoEndpointUsesADefaultPortOfTheNode() throws IOException {
        NodeConfig defaults = NodeConfig.from(Map.of("RAIL8_ENV", "dev", "HOSTNAME", "node-1"));
        int admin = defaults.admin().port();
        int peer = defaults.peer().port();
        int http = defaults.http().port();
        Map<String, List<Integer>> ports = new TreeMap<>();
        List<String> httpIns = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(ENDPOINTS)) {
            files = s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path file : files) {
            JsonObject endpoint = Documents.read(file);
            List<JsonObject> configs = new ArrayList<>();
            configs.add(endpoint.getJsonObject("config", new JsonObject()));
            JsonObject environments = endpoint.getJsonObject("environments", new JsonObject());
            for (String env : environments.fieldNames()) configs.add(environments.getJsonObject(env).getJsonObject("config", new JsonObject()));
            List<Integer> found = new ArrayList<>();
            if ("http".equals(endpoint.getString("scheme")) && "in".equals(endpoint.getString("direction"))) {
                httpIns.add(endpoint.getString("id"));
                assertTrue(configs.getFirst().getValue("address") instanceof String,
                        "demo endpoint " + endpoint.getString("id") + " names its address on the node's HTTP port: " + configs);
                for (JsonObject config : configs) {
                    for (String own : List.of("host", "port", "path")) {
                        assertFalse(config.containsKey(own), "demo endpoint " + endpoint.getString("id") + " sets no " + own + " of its own: " + config);
                    }
                }
            }
            for (JsonObject config : configs) {
                if (config.containsKey("port")) found.add(config.getInteger("port"));
                if (config.containsKey("url")) found.add(port(URI.create(config.getString("url"))));
            }
            ports.put(endpoint.getString("id"), found);
        }
        for (String id : List.of("http-in", "http-in-async", "http-in-order")) {
            assertTrue(httpIns.contains(id), "fixture: " + id + " is an http in endpoint of the demo: " + httpIns);
        }
        for (String id : List.of("partner-domestic", "partner-foreign")) {
            assertTrue(ports.containsKey(id) && !ports.get(id).isEmpty(), "fixture: the demo endpoint " + id + " names a port: " + ports);
        }
        for (Map.Entry<String, List<Integer>> e : ports.entrySet()) {
            assertFalse(e.getValue().contains(admin), "demo endpoint " + e.getKey() + " uses the admin server's default port " + admin + ": " + ports);
            assertFalse(e.getValue().contains(peer), "demo endpoint " + e.getKey() + " uses the peer link's default port " + peer + ": " + ports);
            assertFalse(e.getValue().contains(http), "demo endpoint " + e.getKey() + " uses the node's default HTTP port " + http + ": " + ports);
        }
    }

    /** The URL's port, or its scheme's default. */
    static int port(URI url) {
        if (url.getPort() != -1) return url.getPort();
        return switch (url.getScheme()) {
            case "http" -> 80;
            case "https" -> 443;
            default -> throw new AssertionError("no default port known for " + url);
        };
    }
}
