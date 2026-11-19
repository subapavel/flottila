package dev.rail8.runtime;

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

    private static final Path DIR = Path.of("..", "..", "deploy", "compose").toAbsolutePath().normalize();
    private static final Path FILE = DIR.resolve("docker-compose.yml");
    private static final Set<String> GATE_SERVICES =
            Set.of("postgres", "valkey", "s3", "kafka", "sftp", "sftp-keys", "rail8", "echo", "deploy", "clickhouse");

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
                // a service built from the repo's Dockerfile (rail8) or sharing another's network may have no registry image
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
            assertFalse(e.getValue().path("depends_on").toString().contains("clickhouse"), e.getKey() + " must not wait for clickhouse");
        }
    }

    // ---- CORE-9 (O-1): Kafka is the optional event-broker add-on, off by default ------------------------------------------------

    /** The profiles of a service as written ({@code profiles: [...]}); empty when it has none. */
    private static List<String> profiles(JsonNode service) {
        List<String> out = new ArrayList<>();
        service.path("profiles").forEach(p -> out.add(p.asText()));
        return out;
    }

    /** The services a service waits for, whether its depends_on is written as a list or as a map. */
    private static List<String> dependencies(JsonNode service) {
        List<String> out = new ArrayList<>();
        JsonNode dependsOn = service.path("depends_on");
        if (dependsOn.isObject()) dependsOn.fieldNames().forEachRemaining(out::add);
        else dependsOn.forEach(d -> out.add(d.asText()));
        return out;
    }

    @Test
    void kafkaOnlyUnderTheKafkaProfile() throws IOException {
        JsonNode kafka = service("kafka");
        assertTrue(kafka.path("profiles").isArray(), "kafka's profiles is a list, was " + kafka.path("profiles"));
        assertEquals(List.of("kafka"), profiles(kafka), "kafka is under profiles: [kafka] and no other");
    }

    @Test
    void theDefaultUpStartsNeitherKafkaNorClickhouse() throws IOException {
        Set<String> unprofiled = new java.util.TreeSet<>();
        services().fields().forEachRemaining(e -> {
            if (!e.getValue().has("profiles")) unprofiled.add(e.getKey());
        });
        assertEquals(new java.util.TreeSet<>(Set.of("postgres", "valkey", "s3", "sftp-keys", "sftp", "netns", "rail8", "echo", "deploy")),
                unprofiled, "a plain `docker compose up` starts exactly these: everything but kafka and clickhouse");
    }

    @Test
    void noServiceWaitsForKafka() throws IOException {
        List<String> named = new ArrayList<>();
        for (var it = services().fields(); it.hasNext(); ) {
            var e = it.next();
            List<String> deps = dependencies(e.getValue());
            named.addAll(deps);
            assertFalse(deps.stream().anyMatch(d -> d.toLowerCase(java.util.Locale.ROOT).contains("kafka")),
                    e.getKey() + " must not wait for kafka: depends_on " + deps);
        }
        // witness (not vacuous): the depends_on maps are read — the node waits for postgres and sftp-keys, the echo for netns
        assertTrue(named.contains("postgres") && named.contains("netns") && named.contains("sftp-keys"), "depends_on read: " + named);
    }

    @Test
    void noServiceIsConfiguredForKafka() throws IOException {
        int keys = 0;
        for (var it = services().fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equals("kafka")) continue; // the broker's own settings are not a wiring to it
            Map<String, String> env = environment(e.getValue());
            keys += env.size();
            for (String key : env.keySet()) {
                assertFalse(key.toUpperCase(java.util.Locale.ROOT).contains("KAFKA"), e.getKey() + " sets " + key + ": nothing is wired to Kafka (O-1)");
            }
        }
        // witness (not vacuous): the environments are read — the node alone sets RAIL8_ENV, RAIL8_PG_HOST and more
        assertTrue(environment(service("rail8")).containsKey("RAIL8_ENV") && keys >= 10, "environment keys read: " + keys);
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
        assertTrue(joined(service("rail8").path("healthcheck").path("test")).contains("/health"), "the node is checked on GET /health");
    }

    @Test
    void theNodeRunsDevWithS3ValkeyAndATokenFromTheEnv() throws IOException {
        JsonNode node = service("rail8");
        Map<String, String> env = environment(node);
        assertEquals("dev", env.get("RAIL8_ENV"));
        assertEquals("postgres", env.get("RAIL8_PG_HOST"));
        assertEquals("s3", env.get("RAIL8_ARCHIVE_STORE"));
        assertEquals("true", env.get("RAIL8_S3_PATH_STYLE"));
        assertTrue(env.getOrDefault("RAIL8_S3_ENDPOINT", "").startsWith("http://s3:"), "the S3 endpoint is the s3 service: " + env.get("RAIL8_S3_ENDPOINT"));
        assertEquals("redis://valkey:6379", env.get("RAIL8_VALKEY_URI"));
        assertEquals("0.0.0.0", env.get("RAIL8_ADMIN_HOST"));
        String token = env.getOrDefault("RAIL8_ADMIN_TOKEN", "");
        assertTrue(token.startsWith("${RAIL8_ADMIN_TOKEN:?") && token.endsWith("}"),
                "the token is required from the environment (${RAIL8_ADMIN_TOKEN:?...}), was '" + token + "'");
        JsonNode dependsOn = node.path("depends_on");
        for (String dependency : List.of("postgres", "valkey", "s3")) {
            assertEquals("service_healthy", dependsOn.path(dependency).path("condition").asText(), "the node waits for " + dependency + " healthy");
        }
    }

    @Test
    void theDeployServiceRunsRail8DeployAfterTheNodeIsHealthy() throws IOException {
        JsonNode deploy = service("deploy");
        String command = joined(deploy.path("command")) + " " + joined(deploy.path("entrypoint"));
        assertTrue(command.contains("deploy") && command.contains("/packages/demo"), "deploy runs 'rail8 deploy /packages/demo': " + command);
        assertTrue(command.contains("http://rail8:8081"), "deploy targets the node's admin port: " + command);
        assertEquals("service_healthy", deploy.path("depends_on").path("rail8").path("condition").asText());
        assertEquals("no", deploy.path("restart").asText("no"), "a one-shot is not restarted");
        assertTrue(environment(deploy).getOrDefault("RAIL8_ADMIN_TOKEN", "").startsWith("${RAIL8_ADMIN_TOKEN:?"),
                "the CLI reads the same required token");
        assertEquals("service:netns", service("echo").path("network_mode").asText(), "FIX-105: the echo joins the holder's namespace, not the node's");
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
        for (String name : List.of("rail8", "s3")) {
            String secret = environment(service(name)).getOrDefault("RAIL8_S3_SECRET_KEY", "");
            assertTrue(secret.startsWith("${RAIL8_S3_SECRET_KEY:?"), name + ": the S3 secret is required from the environment, was '" + secret + "'");
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
    void theNodePublishesItsHttpPortAndItsAdminApiOnLoopbackOnly() throws IOException {
        // FIX-105: the ports are published by the namespace holder `netns`, no longer by the node (the old pin on rail8 contradicted it)
        List<String> ports = ports(service("netns"));
        assertTrue(ports.contains("127.0.0.1:8080:8080"), "the node's HTTP port, every http door's, is published on loopback: " + ports);
        assertTrue(ports.contains("127.0.0.1:8081:8081"), "the admin API keeps a port of its own: " + ports);
        assertTrue(ports.stream().allMatch(p -> p.startsWith("127.0.0.1:")), "every published port is on loopback only: " + ports);
        assertEquals(2, ports.size(), "nothing else is published: " + ports);
        for (Path file : NODE_PORTS.keySet()) {
            assertEquals("0.0.0.0", environment(serviceIn(file, "rail8")).get("RAIL8_HTTP_HOST"),
                    file + ": the node's HTTP port listens on every address of its namespace, so the published port reaches it");
        }
    }

    @Test
    void bothStacksAdvertiseThePublishedHttpPortAsTheOriginSendersCall() throws IOException {
        for (Path file : NODE_PORTS.keySet()) {
            assertEquals("http://localhost:8080", environment(serviceIn(file, "rail8")).get("RAIL8_HTTP_ORIGIN"),
                    file + ": senders call the node's HTTP port at the port published on loopback, not at the container's own");
        }
    }

    // ---- FIX-105: a network-namespace holder outlives the node and the echo, in both compose files -----------------------------

    private static final Path ROOT_FILE = Path.of("..", "..", "docker-compose.yml").toAbsolutePath().normalize();

    /** Both stacks with the node's two published ports: its HTTP port 8080, shared by every http door, and its admin API 8081. */
    private static final Map<Path, List<String>> NODE_PORTS = Map.of(
            FILE, List.of("127.0.0.1:8080:8080", "127.0.0.1:8081:8081"),
            ROOT_FILE, List.of("127.0.0.1:8080:8080", "127.0.0.1:8081:8081"));

    private static JsonNode serviceIn(Path file, String name) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        JsonNode service = new ObjectMapper(new YAMLFactory()).readTree(file.toFile()).path("services").get(name);
        assertTrue(service != null, file.getFileName() + " (" + file.getParent().getFileName() + "): service " + name + " is present");
        return service;
    }

    @Test
    void aNamespaceHolderPublishesTheNodesPorts() throws IOException {
        for (var stack : NODE_PORTS.entrySet()) {
            JsonNode netns = serviceIn(stack.getKey(), "netns");
            assertEquals(new java.util.TreeSet<>(stack.getValue()), new java.util.TreeSet<>(ports(netns)),
                    stack.getKey() + ": netns publishes exactly the node's ports");
            assertEquals(stack.getValue().size(), ports(netns).size(), stack.getKey() + ": no binding twice");
            assertEquals("sleep infinity", joined(netns.path("command")).replace("\"", ""), stack.getKey() + ": the holder only sleeps");
            assertFalse(joined(netns.path("healthcheck").path("test")).isBlank(), stack.getKey() + ": the holder has a healthcheck");
            assertFalse(netns.has("network_mode"), stack.getKey() + ": the holder owns the namespace, it joins none");
        }
    }

    @Test
    void theNodeAndTheEchoJoinTheHoldersNamespace() throws IOException {
        for (Path file : NODE_PORTS.keySet()) {
            for (String name : List.of("rail8", "echo")) {
                assertEquals("service:netns", serviceIn(file, name).path("network_mode").asText(), file + ": " + name + " joins the holder");
            }
            assertEquals("service_healthy", serviceIn(file, "rail8").path("depends_on").path("netns").path("condition").asText(),
                    file + ": the node waits for the holder healthy");
            assertEquals("service_healthy", serviceIn(file, "echo").path("depends_on").path("netns").path("condition").asText(),
                    file + ": the echo waits for the holder healthy");
            JsonNode echoDeps = serviceIn(file, "echo").path("depends_on");
            assertTrue(echoDeps.has("rail8") || echoDeps.toString().contains("\"rail8\""), file + ": the echo still waits for the node: " + echoDeps);
            if (file.equals(FILE)) {
                assertEquals("service_healthy", echoDeps.path("rail8").path("condition").asText(), file + ": healthy, as today");
            }
        }
    }

    @Test
    void neitherTheNodeNorTheEchoPublishesAPort() throws IOException {
        for (Path file : NODE_PORTS.keySet()) {
            for (String name : List.of("rail8", "echo")) {
                assertFalse(serviceIn(file, name).has("ports"), file + ": " + name + " shares the holder's namespace and publishes nothing itself");
            }
        }
    }

    @Test
    void theHolderAnswersAsRail8OnTheNetwork() throws IOException {
        for (Path file : NODE_PORTS.keySet()) {
            JsonNode aliases = serviceIn(file, "netns").path("networks").path("default").path("aliases");
            List<String> names = new ArrayList<>();
            aliases.forEach(a -> names.add(a.asText()));
            assertTrue(names.contains("rail8"), file + ": netns carries the alias rail8 on the default network, was " + names);
            assertFalse(serviceIn(file, "rail8").has("networks"), file + ": the node joins the holder's namespace, it has no networks of its own");
        }
    }

    @Test
    void theHolderUsesAnImageTheStackAlreadyPins() throws IOException {
        String dockerfile = Files.readString(Path.of("..", "..", "deploy", "quickstart", "Dockerfile"));
        java.util.regex.Matcher from = java.util.regex.Pattern.compile("(?m)^FROM\\s+(\\S+)").matcher(dockerfile);
        assertTrue(from.find(), "the node Dockerfile has a FROM");
        String nodeImage = from.group(1);
        assertEquals("eclipse-temurin:25.0.4.1_1-jre-noble", nodeImage, "the node's base image is the pinned JRE the packet names");
        for (Path file : NODE_PORTS.keySet()) {
            assertEquals(nodeImage, serviceIn(file, "netns").path("image").asText(), file + ": the holder runs the node's base image, no new image");
        }
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
        for (String waiter : List.of("sftp", "rail8")) {
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
        JsonNode node = service("rail8");
        assertFalse(environment(node).containsKey("RAIL8_SECRET_SFTP_HOST_KEY"),
                "the fingerprint is unknown until the key exists: it is not a fixed value of the compose file");
        List<Mount> mounted = mounts(node);
        // CORE-4c1 r1 (architect): the node's data directory is a named volume too, so a recreated container keeps its journal
        assertEquals(List.of("sftp-fingerprint", "rail8-data"), mounted.stream().map(Mount::source).toList(),
                "the node mounts the fingerprint volume and its data volume, no other (in particular none that holds a host key): " + mounted);
        assertTrue(mounted.get(0).readOnly(), "and only reads the fingerprint: " + mounted);
        assertEquals(new Mount("rail8-data", "/var/lib/rail8", false), mounted.get(1), "the data volume is the node's data directory, written");
        assertTrue(new ObjectMapper(new YAMLFactory()).readTree(FILE.toFile()).path("volumes").has("rail8-data"), "rail8-data is a declared volume");
        String wrapper = script(node) + " " + environment(node);
        assertFalse(wrapper.contains("ssh_host"), "nothing the node runs names a host key file, public or private, of any type: " + wrapper);
        String assigned = script(node);
        assertTrue(assigned.matches("(?s).*RAIL8_SECRET_SFTP_HOST_KEY=.*"), "the entrypoint wrapper sets the variable: " + assigned);
        String nodeMount = mounted.get(0).target();
        java.util.regex.Matcher read = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(nodeMount) + "/([^\\s\"';&|)]+)").matcher(assigned);
        assertTrue(read.find(), "the wrapper reads a file below the fingerprint mount " + nodeMount + ": " + assigned);
        String keysMount = mountOf(service("sftp-keys"), "sftp-fingerprint").orElseThrow().target();
        assertTrue(script(service("sftp-keys")).contains(keysMount + "/" + read.group(1)),
                "the file the node reads (" + read.group(1) + ") is the file the one-shot writes: " + script(service("sftp-keys")));
        assertTrue(new ObjectMapper(new YAMLFactory()).readTree(FILE.toFile()).path("volumes").has("sftp-fingerprint"), "sftp-fingerprint is a declared volume");
        assertEquals("${RAIL8_SFTP_PASSWORD:-demo}", environment(node).get("RAIL8_SECRET_SFTP_PASSWORD"),
                "the password is the one the sftp server is created with");
        assertTrue(script(service("sftp")).contains("${RAIL8_SFTP_PASSWORD:-demo}"), "the same expression: " + script(service("sftp")));
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

    /** The quickstart stack runs every demo flow: the node waits for the host key one-shot, reads its fingerprint and has no placeholder secret. */
    @Test
    void theQuickstartStackHasTheSftpServerTheDemoNeeds() throws IOException {
        JsonNode node = serviceIn(ROOT_FILE, "rail8");
        JsonNode sftp = serviceIn(ROOT_FILE, "sftp");
        serviceIn(ROOT_FILE, "sftp-keys");
        assertEquals("service_completed_successfully", node.path("depends_on").path("sftp-keys").path("condition").asText(), "the node waits for the host key");
        assertEquals("service_completed_successfully", sftp.path("depends_on").path("sftp-keys").path("condition").asText(), "the server waits for the host key");
        assertEquals("${RAIL8_SFTP_PASSWORD:-demo}", environment(node).get("RAIL8_SECRET_SFTP_PASSWORD"), "the demo's password");
        assertFalse(environment(node).containsKey("RAIL8_SECRET_SFTP_HOST_KEY"), "the fingerprint is read from the one-shot's file, not fixed");
        assertTrue(script(node).contains("RAIL8_SECRET_SFTP_HOST_KEY=$$(cat /sftp-fingerprint/host-key.fingerprint)"), script(node));
        assertTrue(script(node).contains("exec java -jar /opt/rail8/rail8-node.jar"), "the wrapper starts the jar the image builds: " + script(node));
        assertTrue(script(sftp).contains("demo:${RAIL8_SFTP_PASSWORD:-demo}:1001:100:inbox"), script(sftp));
        assertFalse(Files.readString(ROOT_FILE).contains("placeholder"), "no placeholder secret is left in the quickstart stack");
    }

    @Test
    void theReadmeNamesTheAsyncDoorAndHowToDropAFile() throws IOException {
        String readme = Files.readString(DIR.resolve("README.md"));
        assertTrue(readme.contains("8080") && readme.contains("/http/invoice/async"), "the async door: the node's HTTP port and its path there");
        assertTrue(readme.contains("inbox") && (readme.contains("docker compose cp") || readme.contains("sftp ")), "how to drop a file into the sftp inbox");
        assertTrue(readme.toLowerCase(java.util.Locale.ROOT).contains("fingerprint") && readme.contains("ssh-keygen"), "the fingerprint command");
    }

    // ---- FIX-146 T4: a stack that reaches Postgres by a non-loopback name says it runs it in plaintext, never implicitly ------------

    @Test
    void everyServiceReachingPostgresByANameSetsItsSslModeExplicitly() throws IOException {
        int reaching = 0;
        for (Path file : NODE_PORTS.keySet()) {
            JsonNode services = new ObjectMapper(new YAMLFactory()).readTree(file.toFile()).path("services");
            for (var service : (Iterable<Map.Entry<String, JsonNode>>) services::fields) {
                Map<String, String> env = environment(service.getValue());
                String host = env.get("RAIL8_PG_HOST");
                if (host == null || List.of("localhost", "127.0.0.1", "::1").contains(host)) continue;
                reaching++;
                assertEquals("disable", env.get("RAIL8_PG_SSL_MODE"), file + ": " + service.getKey() + " reaches Postgres as '" + host
                        + "', whose default is verify-full: the stack's plaintext link must be written down (FIX-146 T4)");
            }
        }
        assertEquals(2, reaching, "witness: the node of each stack reaches Postgres by its service name (not vacuous)");
    }
}
