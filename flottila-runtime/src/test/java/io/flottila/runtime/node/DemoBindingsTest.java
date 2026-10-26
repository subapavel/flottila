package io.flottila.runtime.node;

import io.flottila.schemas.Documents;
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
 * P1-09d1a (D-057 amendment, D-059): the demo package deploys to a node with the default configuration, so none of its
 * endpoints listens on or sends to the port the node's admin server binds by default — not in its config, not in an
 * environment's override.
 */
class DemoBindingsTest {

    static final Path ENDPOINTS = Path.of("..", "packages", "demo", "endpoints");

    @Test
    void noDemoEndpointUsesTheAdminDefaultPort() throws IOException {
        int admin = NodeConfig.from(Map.of("FLOTTILA_ENV", "dev", "HOSTNAME", "node-1")).admin().port();
        Map<String, List<Integer>> ports = new TreeMap<>();
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
            for (JsonObject config : configs) {
                if (config.containsKey("port")) found.add(config.getInteger("port"));
                if (config.containsKey("url")) found.add(port(URI.create(config.getString("url"))));
            }
            ports.put(endpoint.getString("id"), found);
        }
        for (String id : List.of("http-in", "partner-domestic", "partner-foreign")) {
            assertTrue(ports.containsKey(id) && !ports.get(id).isEmpty(), "fixture: the demo endpoint " + id + " names a port: " + ports);
        }
        for (Map.Entry<String, List<Integer>> e : ports.entrySet()) {
            assertFalse(e.getValue().contains(admin), "demo endpoint " + e.getKey() + " uses the admin server's default port " + admin + ": " + ports);
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
