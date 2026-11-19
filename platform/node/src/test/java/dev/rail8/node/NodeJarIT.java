package dev.rail8.node;

import dev.rail8.testkit.Ports;
import dev.rail8.testkit.PostgresImage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The jar {@code package} builds is the node's distribution. Its manifest names {@code dev.rail8.runtime.Main} and a class
 * path of {@code lib/} entries, each a file in {@code target/lib}; {@code java -jar} on it, with no {@code -cp} and the
 * configuration in the environment alone, starts a node that serves {@code /health} 200.
 */
class NodeJarIT {

    /** How long the node may take to serve, or to stop once asked. */
    static final Duration BOUND = Duration.ofSeconds(60);

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name()).withCommand("postgres", "-c", "fsync=off");

    @TempDir Path data;
    @TempDir Path logs;
    Process child;

    @BeforeAll
    static void start() {
        pg.start();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    /** The node never outlives the case: SIGTERM, then SIGKILL once the bound has passed. */
    @AfterEach
    void destroyTheChild() throws InterruptedException {
        if (child == null) return;
        child.destroy();
        if (!child.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS)) {
            child.destroyForcibly();
            assertTrue(child.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS), "the child " + child.pid() + " survived SIGKILL");
        }
    }

    @Test
    void javaDashJarOnThePackagedJarStartsANodeThatServesHealth() throws Exception {
        Path jar = packagedJar();
        Manifest manifest;
        try (JarFile file = new JarFile(jar.toFile())) {
            manifest = file.getManifest();
        }
        assertNotNull(manifest, "the packaged jar has a manifest: " + jar);
        Attributes main = manifest.getMainAttributes();
        assertEquals("dev.rail8.runtime.Main", main.getValue(Attributes.Name.MAIN_CLASS), "the manifest names Main: " + main.entrySet());
        String classPath = main.getValue(Attributes.Name.CLASS_PATH);
        assertNotNull(classPath, "the manifest carries the runtime class path: " + main.entrySet());
        for (String entry : classPath.trim().split("\\s+")) {
            assertTrue(entry.startsWith("lib/"), "every class path entry is under lib/: " + entry);
            assertTrue(Files.isRegularFile(jar.resolveSibling(entry)), "every class path entry is a file beside the jar: " + jar.resolveSibling(entry));
        }

        int port = Ports.free();
        Path stderr = logs.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toAbsolutePath().toString())
                .redirectError(stderr.toFile()).redirectOutput(logs.resolve("stdout.txt").toFile());
        builder.environment().clear();   // nothing inherited: an inherited JAVA_TOOL_OPTIONS, say, would change the child
        builder.environment().putAll(env(port));
        child = builder.start();

        long deadline = System.nanoTime() + BOUND.toNanos();
        while (health(port) != 200) {
            if (!child.isAlive()) fail("the node exited with " + child.exitValue() + " before it served: " + lines(stderr));
            if (System.nanoTime() > deadline) fail("the node did not serve /health 200 within " + BOUND + ": " + lines(stderr));
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /** A node's environment on the container's database, its data in the case's temporary directory. */
    Map<String, String> env(int adminPort) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Map.ofEntries(
                Map.entry("RAIL8_ENV", "ci"),
                Map.entry("RAIL8_NODE_ID", "node-jar-it"),
                Map.entry("RAIL8_ADMIN_PORT", String.valueOf(adminPort)),
                Map.entry("RAIL8_PEER_PORT", "0"),   // any free port: test JVMs run side by side
                Map.entry("RAIL8_DATA_DIR", data.toString()),
                Map.entry("RAIL8_PG_HOST", pg.getHost()),
                Map.entry("RAIL8_PG_PORT", String.valueOf(pg.getMappedPort(5432))),
                Map.entry("RAIL8_PG_DATABASE", pg.getDatabaseName()),
                Map.entry("RAIL8_PG_USER", pg.getUsername()),
                Map.entry("RAIL8_PG_PASSWORD", pg.getPassword()),
                Map.entry("RAIL8_PG_POOL_SIZE", "4"),
                Map.entry("RAIL8_JOURNAL_KEY", Base64.getEncoder().encodeToString(key)));   // a node refuses to start without one
    }

    /** The module's packaged jar, {@code target/rail8-node-<version>.jar} (not a sources, tests or javadoc jar): exactly one. */
    static Path packagedJar() throws IOException {
        try (Stream<Path> listed = Files.list(Path.of("target"))) {
            List<Path> jars = listed.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("rail8-node-") && name.endsWith(".jar")
                        && !name.endsWith("-sources.jar") && !name.endsWith("-tests.jar") && !name.endsWith("-javadoc.jar");
            }).toList();
            assertEquals(1, jars.size(), "one packaged jar in target/ (failsafe runs after package): " + jars);
            return jars.getFirst();
        }
    }

    /** The status of {@code GET /health} on the loopback {@code port}, or -1 when nothing answered. */
    static int health(int port) throws InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException refusedOrCut) {
            return -1;
        }
    }

    static List<String> lines(Path file) throws IOException {
        return Files.notExists(file) ? List.of() : Files.readAllLines(file, StandardCharsets.UTF_8);
    }
}
