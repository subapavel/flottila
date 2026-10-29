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
            Set.of("postgres", "valkey", "s3", "kafka", "sftp", "sftp-keys", "flottila", "echo", "deploy", "clickhouse");

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
        for (String name : List.of("postgres", "valkey", "s3", "kafka", "sftp", "sftp-keys", "echo", "clickhouse")) {
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
            if (e.getKey().equals("deploy") || e.getKey().equals("sftp-keys")) continue; // the one-shots exit instead
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

    // ---- P2-06: the async door, the SFTP host key and the demo's secrets -----------------------------------------------------

    private static List<String> ports(JsonNode service) {
        List<String> ports = new ArrayList<>();
        service.path("ports").forEach(p -> ports.add(p.asText()));
        return ports;
    }

    /** One volume mount of a service, written in the short ({@code src:target[:ro]}) or the long (map) form. */
    private record Mount(String source, String target, boolean readOnly) {}

    private static List<Mount> mounts(JsonNode service) {
        List<Mount> out = new ArrayList<>();
        for (JsonNode v : service.path("volumes")) {
            if (v.isObject()) {
                out.add(new Mount(v.path("source").asText(), v.path("target").asText(), v.path("read_only").asBoolean(false)));
            } else {
                String[] parts = v.asText().split(":");
                out.add(new Mount(parts[0], parts.length > 1 ? parts[1] : "", parts.length > 2 && parts[2].contains("ro")));
            }
        }
        return out;
    }

    private static java.util.Optional<Mount> mountOf(JsonNode service, String volume) {
        return mounts(service).stream().filter(m -> m.source().equals(volume)).findFirst();
    }

    private static String script(JsonNode service) {
        return joined(service.path("entrypoint")) + " " + joined(service.path("command"));
    }

    @Test
    void theNodePublishesTheAsyncDoorOnLoopbackBesideTheOthers() throws IOException {
        List<String> ports = ports(service("flottila"));
        assertTrue(ports.contains("127.0.0.1:8082:8082"), "the async http door 8082 is published on loopback: " + ports);
        assertTrue(ports.contains("127.0.0.1:8080:8080") && ports.contains("127.0.0.1:8081:8081"), "the sync door and the admin API stay: " + ports);
        assertTrue(ports.stream().allMatch(p -> p.startsWith("127.0.0.1:")), "every published port is on loopback only: " + ports);
        assertEquals(3, ports.size(), "nothing else is published: " + ports);
    }

    /** The path after {@code ssh-keygen ... -t rsa ... -f}, the key the one-shot generates. */
    private static String generatedKey(String script) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("ssh-keygen\\s[^\\n;]*-t\\s+rsa[^\\n;]*?-f\\s+([^\\s;]+)").matcher(script);
        assertTrue(m.find(), "it generates an rsa host key with 'ssh-keygen -t rsa ... -f <path>': " + script);
        return m.group(1);
    }

    @Test
    void theHostKeyOneShotRunsBeforeTheSftpServerAndTheNode() throws IOException {
        JsonNode keys = service("sftp-keys");
        assertEquals("no", keys.path("restart").asText("no"), "a one-shot is not restarted");
        assertFalse(keys.has("ports"), "the one-shot publishes nothing");
        assertFalse(keys.has("profiles"), "and starts without a profile");
        for (String waiter : List.of("sftp", "flottila")) {
            assertEquals("service_completed_successfully", service(waiter).path("depends_on").path("sftp-keys").path("condition").asText(),
                    waiter + " waits for the host key one-shot to exit 0");
        }
        String script = script(keys);
        // MINA SSHD 2.20.0 (the node's client) offers no ssh-ed25519: the server's only host key is RSA
        assertFalse(script.contains("ed25519"), "no ed25519 key: the node's client cannot use it, and a fingerprint of it would never match: " + script);
        assertTrue(java.util.regex.Pattern.compile("-t\\s+rsa\\s+(-\\S+\\s+)*-b\\s+3072\\b|-b\\s+3072\\s+(-\\S+\\s+)*-t\\s+rsa").matcher(script).find(),
                "an RSA key of 3072 bits: " + script);
        String key = generatedKey(script);
        java.util.regex.Matcher fingerprintOf = java.util.regex.Pattern.compile("ssh-keygen\\s+-l(\\s+-f)?\\s+([^\\s;|]+)|ssh-keygen\\s+-lf\\s+([^\\s;|]+)").matcher(script);
        assertTrue(fingerprintOf.find(), "it prints the fingerprint with 'ssh-keygen -l -f <file>': " + script);
        String printed = fingerprintOf.group(2) != null ? fingerprintOf.group(2) : fingerprintOf.group(3);
        assertEquals(key + ".pub", printed, "the fingerprint is the generated RSA key's public half");
        // once: a second `up` must keep the key sftp already presented, and so the fingerprint the node read
        String quoted = java.util.regex.Pattern.quote(key);
        assertTrue(java.util.regex.Pattern.compile("if\\s+\\[\\s+!\\s+-[sfe]\\s+" + quoted + "\\s+\\][\\s;]*then\\s+ssh-keygen[^;\\n]*-t\\s+rsa"
                        + "|\\[\\s+-[sfe]\\s+" + quoted + "\\s+\\]\\s*\\|\\|\\s*ssh-keygen[^;\\n]*-t\\s+rsa").matcher(script).find(),
                "guarded: the key is generated only when " + key + " is absent: " + script);
        // the fingerprint is written to a file of its own volume, and the one-shot fails when that file is empty
        java.util.regex.Matcher written = java.util.regex.Pattern.compile(">\\s*(/[^\\s;&|]+)").matcher(script);
        assertTrue(written.find(), "the fingerprint is redirected into a file: " + script);
        String file = written.group(1);
        int writeAt = written.start();
        Mount fingerprintMount = mountOf(keys, "sftp-fingerprint").orElseThrow(() -> new AssertionError("sftp-keys mounts the volume sftp-fingerprint"));
        assertTrue(file.startsWith(fingerprintMount.target() + "/") && !fingerprintMount.readOnly(), "the file is written below the writable mount " + fingerprintMount + ": " + file);
        assertTrue(mounts(keys).stream().anyMatch(m -> key.startsWith(m.target() + "/") && !m.source().equals("sftp-fingerprint") && !m.readOnly()),
                "the key is generated into a volume of its own, not the fingerprint's: " + mounts(keys));
        java.util.regex.Matcher check = java.util.regex.Pattern.compile("\\[\\s+-s\\s+" + java.util.regex.Pattern.quote(file) + "\\s+\\]").matcher(script);
        assertTrue(check.find() && check.start() > writeAt,
                "after writing it checks [ -s " + file + " ] (non-empty): " + script);
        String rest = script.substring(check.end()).strip();
        assertTrue(rest.isEmpty() || rest.startsWith("||"), "and the check decides the exit status (last command, or '|| exit 1' after it), not an echo: '" + rest + "'");
        assertTrue(mountOf(service("sftp"), mounts(keys).stream().filter(m -> key.startsWith(m.target() + "/")).findFirst().orElseThrow().source()).isPresent(),
                "the sftp server mounts the volume holding the generated key: " + mounts(service("sftp")));
    }

    @Test
    void theNodeMountsOnlyTheFingerprintAndReadsItIntoItsSecretVariable() throws IOException {
        JsonNode node = service("flottila");
        assertFalse(environment(node).containsKey("FLOTTILA_SECRET_SFTP_HOST_KEY"),
                "the fingerprint is unknown until the key exists: it is not a fixed value of the compose file");
        List<Mount> mounted = mounts(node);
        assertEquals(List.of("sftp-fingerprint"), mounted.stream().map(Mount::source).toList(),
                "the node mounts the fingerprint volume and no other (in particular none that holds a host key): " + mounted);
        assertTrue(mounted.get(0).readOnly(), "and only reads it: " + mounted);
        String wrapper = script(node) + " " + environment(node);
        assertFalse(wrapper.contains("ssh_host"), "nothing the node runs names a host key file, public or private, of any type: " + wrapper);
        String assigned = script(node);
        assertTrue(assigned.matches("(?s).*FLOTTILA_SECRET_SFTP_HOST_KEY=.*"), "the entrypoint wrapper sets the variable: " + assigned);
        String nodeMount = mounted.get(0).target();
        java.util.regex.Matcher read = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(nodeMount) + "/([^\\s\"';&|)]+)").matcher(assigned);
        assertTrue(read.find(), "the wrapper reads a file below the fingerprint mount " + nodeMount + ": " + assigned);
        String keysMount = mountOf(service("sftp-keys"), "sftp-fingerprint").orElseThrow().target();
        assertTrue(script(service("sftp-keys")).contains(keysMount + "/" + read.group(1)),
                "the file the node reads (" + read.group(1) + ") is the file the one-shot writes: " + script(service("sftp-keys")));
        assertTrue(new ObjectMapper(new YAMLFactory()).readTree(FILE.toFile()).path("volumes").has("sftp-fingerprint"), "sftp-fingerprint is a declared volume");
        assertEquals("${FLOTTILA_SFTP_PASSWORD:-demo}", environment(node).get("FLOTTILA_SECRET_SFTP_PASSWORD"),
                "the password is the one the sftp server is created with");
        assertTrue(script(service("sftp")).contains("${FLOTTILA_SFTP_PASSWORD:-demo}"), "the same expression: " + script(service("sftp")));
    }

    @Test
    void noHostKeyFingerprintIsWrittenIntoTheComposeDirectory() throws IOException {
        java.util.regex.Pattern fingerprint = java.util.regex.Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");
        try (Stream<Path> files = Files.walk(DIR)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(fingerprint.matcher(Files.readString(file, StandardCharsets.ISO_8859_1)).find(), DIR.relativize(file) + " holds a host key fingerprint");
            }
        }
        assertTrue(Files.isRegularFile(FILE), FILE + " must exist (the scan is vacuous without the stack)");
    }

    @Test
    void theReadmeNamesTheAsyncDoorAndHowToDropAFile() throws IOException {
        String readme = Files.readString(DIR.resolve("README.md"));
        assertTrue(readme.contains("8082") && readme.contains("/invoice/async"), "the async door: port and path");
        assertTrue(readme.contains("inbox") && (readme.contains("docker compose cp") || readme.contains("sftp ")), "how to drop a file into the sftp inbox");
        assertTrue(readme.toLowerCase(java.util.Locale.ROOT).contains("fingerprint") && readme.contains("ssh-keygen"), "the fingerprint command");
    }
}
