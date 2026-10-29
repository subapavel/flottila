package io.flottila.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P2-05: the gate stack {@code deploy/compose/docker-compose.yml}, checked as a document; no Docker is needed. */
class ComposeStackTest {

    private static final Path DIR = Path.of("..", "deploy", "compose").toAbsolutePath().normalize();
    private static final Path FILE = DIR.resolve("docker-compose.yml");
    private static final Set<String> GATE_SERVICES =
            Set.of("postgres", "valkey", "s3", "kafka", "sftp", "flottila", "echo", "deploy", "clickhouse");

    private static JsonNode services() throws IOException {
        assertTrue(Files.isRegularFile(FILE), FILE + " must exist");
        JsonNode services = new ObjectMapper(new YAMLFactory()).readTree(FILE.toFile()).path("services");
        assertTrue(services.isObject() && !services.isEmpty(), "the compose file has a services map");
        return services;
    }

    private static JsonNode service(String name) throws IOException {
        JsonNode service = services().get(name);
        assertTrue(service != null, "service " + name + " is present");
        return service;
    }

    /** The environment of a service, whether written as a map or as a KEY=value list. */
    private static Map<String, String> environment(JsonNode service) {
        Map<String, String> out = new HashMap<>();
        JsonNode env = service.path("environment");
        if (env.isObject()) {
            env.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        } else {
            for (JsonNode item : env) {
                String s = item.asText();
                int eq = s.indexOf('=');
                out.put(eq < 0 ? s : s.substring(0, eq), eq < 0 ? "" : s.substring(eq + 1));
            }
        }
        return out;
    }

    /** A command or test written as a string or as a list, as one space-joined string. */
    private static String joined(JsonNode node) {
        if (!node.isArray()) return node.asText();
        List<String> parts = new ArrayList<>();
        node.forEach(n -> parts.add(n.asText()));
        return String.join(" ", parts);
    }

    @Test
    void everyServiceOfGateCheck1IsPresent() throws IOException {
        List<String> names = new ArrayList<>();
        services().fieldNames().forEachRemaining(names::add);
        assertTrue(names.containsAll(GATE_SERVICES), "services present: " + names + ", required: " + GATE_SERVICES);
    }

    @Test
    void everyImageIsPinned() throws IOException {
        JsonNode services = services();
        int built = 0;
        for (var it = services.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode image = e.getValue().path("image");
            if (!image.isTextual()) {
                // a service built from the repo's Dockerfile (flottila) or sharing another's network may have no registry image
                assertTrue(e.getValue().has("build"), e.getKey() + " has neither an image nor a build");
                built++;
                continue;
            }
            String ref = image.asText();
            String lastSegment = ref.substring(ref.lastIndexOf('/') + 1);
            assertTrue(lastSegment.contains(":"), e.getKey() + ": image '" + ref + "' has no tag");
            String tag = lastSegment.substring(lastSegment.indexOf(':') + 1);
            assertFalse(tag.isBlank() || tag.contains("latest"), e.getKey() + ": image '" + ref + "' is not pinned");
        }
        assertTrue(built <= 1, "only the node image may be built, " + built + " services have no image");
        for (String name : List.of("postgres", "valkey", "s3", "kafka", "sftp", "echo", "clickhouse")) {
            assertTrue(service(name).path("image").isTextual(), name + " names a registry image");
        }
    }

    @Test
    void clickhouseOnlyUnderTheHistoryProfile() throws IOException {
        JsonNode profiles = service("clickhouse").path("profiles");
        assertTrue(profiles.isArray() && profiles.size() == 1 && "history".equals(profiles.get(0).asText()),
                "clickhouse is under profiles: [history], was " + profiles);
        for (var it = services().fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equals("clickhouse")) continue;
            assertFalse(e.getValue().has("profiles"), e.getKey() + " must start without a profile");
            assertFalse(e.getValue().path("depends_on").toString().contains("clickhouse"), e.getKey() + " must not wait for clickhouse");
        }
    }

    @Test
    void everyLongRunningServiceHasAHealthcheck() throws IOException {
        for (var it = services().fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equals("deploy")) continue; // the one-shot exits instead
            JsonNode test = e.getValue().path("healthcheck").path("test");
            assertFalse(test.isMissingNode() || joined(test).isBlank(), e.getKey() + " has a healthcheck with a test");
        }
        assertTrue(joined(service("postgres").path("healthcheck").path("test")).contains("pg_isready"), "postgres is checked with pg_isready");
        assertTrue(joined(service("flottila").path("healthcheck").path("test")).contains("/health"), "the node is checked on GET /health");
    }

    @Test
    void theNodeRunsDevWithS3ValkeyAndATokenFromTheEnv() throws IOException {
        JsonNode node = service("flottila");
        Map<String, String> env = environment(node);
        assertEquals("dev", env.get("FLOTTILA_ENV"));
        assertEquals("postgres", env.get("FLOTTILA_PG_HOST"));
        assertEquals("s3", env.get("FLOTTILA_ARCHIVE_STORE"));
        assertEquals("true", env.get("FLOTTILA_S3_PATH_STYLE"));
        assertTrue(env.getOrDefault("FLOTTILA_S3_ENDPOINT", "").startsWith("http://s3:"), "the S3 endpoint is the s3 service: " + env.get("FLOTTILA_S3_ENDPOINT"));
        assertEquals("redis://valkey:6379", env.get("FLOTTILA_VALKEY_URI"));
        assertEquals("0.0.0.0", env.get("FLOTTILA_ADMIN_HOST"));
        String token = env.getOrDefault("FLOTTILA_ADMIN_TOKEN", "");
        assertTrue(token.startsWith("${FLOTTILA_ADMIN_TOKEN:?") && token.endsWith("}"),
                "the token is required from the environment (${FLOTTILA_ADMIN_TOKEN:?...}), was '" + token + "'");
        JsonNode dependsOn = node.path("depends_on");
        for (String dependency : List.of("postgres", "valkey", "s3")) {
            assertEquals("service_healthy", dependsOn.path(dependency).path("condition").asText(), "the node waits for " + dependency + " healthy");
        }
    }

    @Test
    void theDeployServiceRunsFlottilaDeployAfterTheNodeIsHealthy() throws IOException {
        JsonNode deploy = service("deploy");
        String command = joined(deploy.path("command")) + " " + joined(deploy.path("entrypoint"));
        assertTrue(command.contains("deploy") && command.contains("/packages/demo"), "deploy runs 'flottila deploy /packages/demo': " + command);
        assertTrue(command.contains("http://flottila:8081"), "deploy targets the node's admin port: " + command);
        assertEquals("service_healthy", deploy.path("depends_on").path("flottila").path("condition").asText());
        assertEquals("no", deploy.path("restart").asText("no"), "a one-shot is not restarted");
        assertTrue(environment(deploy).getOrDefault("FLOTTILA_ADMIN_TOKEN", "").startsWith("${FLOTTILA_ADMIN_TOKEN:?"),
                "the CLI reads the same required token");
        assertEquals("service:flottila", service("echo").path("network_mode").asText());
    }

    @Test
    void valkeyPersistenceIsOff() throws IOException {
        JsonNode node = service("valkey").path("command");
        List<String> args = new ArrayList<>();
        if (node.isArray()) node.forEach(n -> args.add(n.asText()));
        else for (String a : node.asText().split("\\s+")) args.add(a.replace("\"", "").replace("'", ""));
        args.removeIf(String::isEmpty);
        // --save "" in a string command becomes an empty token that was removed: accept the flag followed by --appendonly
        int save = args.indexOf("--save");
        assertTrue(save >= 0, "snapshots are switched off with --save \"\": " + args);
        assertTrue(save + 1 == args.size() || args.get(save + 1).startsWith("--") || args.get(save + 1).isEmpty(), "--save takes the empty value: " + args);
        int append = args.indexOf("--appendonly");
        assertTrue(append >= 0 && append + 1 < args.size() && "no".equals(args.get(append + 1)), "append-only file is off: " + args);
    }

    @Test
    void noPrivateKeyInTheRepo() throws IOException {
        assertTrue(Files.isDirectory(DIR), DIR + " must exist");
        try (Stream<Path> files = Files.walk(DIR)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(text.contains("PRIVATE KEY"), DIR.relativize(file) + " holds a private key");
            }
        }
        assertTrue(Files.isRegularFile(FILE), FILE + " must exist (the scan is vacuous without the stack)");
    }

    @Test
    void theS3SecretComesFromTheEnvAndIsRequired() throws IOException {
        for (String name : List.of("flottila", "s3")) {
            String secret = environment(service(name)).getOrDefault("FLOTTILA_S3_SECRET_KEY", "");
            assertTrue(secret.startsWith("${FLOTTILA_S3_SECRET_KEY:?"), name + ": the S3 secret is required from the environment, was '" + secret + "'");
        }
        java.util.regex.Pattern value = java.util.regex.Pattern.compile("\\\\?\"secretKey\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]*)");
        try (Stream<Path> files = Files.walk(DIR)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                var m = value.matcher(Files.readString(file, StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    String v = m.group(1);
                    assertTrue(v.startsWith("$") || v.equals("%s"), DIR.relativize(file) + " holds a literal S3 secret key value '" + v + "'");
                }
            }
        }
    }
}
