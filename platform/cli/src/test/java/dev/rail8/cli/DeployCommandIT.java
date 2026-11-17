package dev.rail8.cli;

import dev.rail8.cli.CliRuns.Ran;
import dev.rail8.runtime.Main;
import dev.rail8.runtime.node.Node;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.testkit.Ports;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.rail8.cli.CliRuns.DEMO;
import static dev.rail8.cli.CliRuns.FIXTURES;
import static dev.rail8.cli.CliRuns.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code rail8 deploy} against a real node — started in this JVM over what {@code Main.shipped(int)} wires, on a
 * Postgres of its own (Testcontainers, a database per case, as runtime's QuickstartIT), its admin API on port 0 behind a bearer
 * token. The demo deploys (200, exit 0) and the node holds exactly its files at their package-relative paths; a package whose
 * stylesheet does not compile is refused (422, exit 1) and nothing is deployed; without the token the node refuses (401, exit 1).
 */
class DeployCommandIT {

    static final Duration BOUND = Duration.ofSeconds(60);
    static final String TOKEN = "deploy-command-it-token";

    static final SharedPostgres pg = SharedPostgres.database();
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path work;
    Node node;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        if (vertx != null) vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void startTheNode() throws Exception {
        String database = "cli_deploy_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", "cli-deploy-it");
        env.put("RAIL8_ADMIN_PORT", "0");
        env.put("RAIL8_PEER_PORT", "0");   // any free port: test JVMs run side by side
        env.put("RAIL8_DATA_DIR", Files.createDirectories(work.resolve("data")).toString());
        env.put("RAIL8_PG_HOST", pg.getHost());
        env.put("RAIL8_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("RAIL8_PG_DATABASE", database);
        env.put("RAIL8_PG_USER", pg.getUsername());
        env.put("RAIL8_PG_PASSWORD", pg.getPassword());
        env.put("RAIL8_PG_POOL_SIZE", "4");
        env.put("RAIL8_ADMIN_TOKEN", TOKEN);
        // a node needs a journal master key (CORE-4c1, D-106): a required configuration, a key made for this run and never stored
        byte[] journalKey = new byte[32];
        new java.security.SecureRandom().nextBytes(journalKey);
        env.put("RAIL8_JOURNAL_KEY", java.util.Base64.getEncoder().encodeToString(journalKey));
        // the demo's sftp-inbox keeps its password and host key as secret:// references (P2-06): the node it is deployed to has both
        env.put("RAIL8_SECRET_SFTP_PASSWORD", "demo");
        env.put("RAIL8_SECRET_SFTP_HOST_KEY", "SHA256:zuCbj2cX0buHOs3UvKwKc/FsR3hTNWRKrSBVsfc5gzU");
        NodeConfig config = NodeConfig.from(env);
        Main.Shipped shipped = Main.shipped(NodeConfig.JQ_MAX_RECURSION_DEPTH_DEFAULT);
        node = onVirtualThread(() -> Node.start(config, vertx, shipped.components(), shipped.steps(), shipped.languages()));
    }

    @AfterEach
    void stopTheNode() throws Exception {
        if (node != null) onVirtualThread(() -> { node.close(); return null; });
    }

    // --- fixture

    /** Runs {@code call} on a virtual thread (Node.start and close block) and waits up to {@link #BOUND}, naming where it sits on a timeout. */
    static <T> T onVirtualThread(Callable<T> call) throws Exception {
        CompletableFuture<T> outcome = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().name("cli-it-call").start(() -> {
            try {
                outcome.complete(call.call());
            } catch (Throwable failed) {
                outcome.completeExceptionally(failed);
            }
        });
        try {
            return outcome.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        } catch (TimeoutException late) {
            return fail("still running after " + BOUND + " at " + Arrays.toString(t.getStackTrace()));
        }
    }

    static int freePort() throws IOException {
        return Ports.free();
    }

    /** A copy of {@code from} whose http-in listens on a free loopback port (the demo's 0.0.0.0:8080 may be taken). */
    Path withFreeInPort(Path from, String as) throws IOException {
        Path copy = CliRuns.copy(from, work.resolve(as));
        CliRuns.rewrite(copy.resolve("endpoints/http-in.yaml"), Map.of("host: 0.0.0.0", "host: 127.0.0.1", "port: 8080", "port: " + freePort()));
        if (Files.exists(copy.resolve("endpoints/http-in-async.yaml"))) {   // the demo's second door (P2-06) has a port of its own
            CliRuns.rewrite(copy.resolve("endpoints/http-in-async.yaml"), Map.of("host: 0.0.0.0", "host: 127.0.0.1", "port: 8082", "port: " + freePort()));
        }
        return copy;
    }

    String adminUrl() {
        return "http://127.0.0.1:" + node.adminPort();
    }

    JsonArray packages() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> answer = client.send(HttpRequest.newBuilder(URI.create(adminUrl() + "/admin/packages")).timeout(Duration.ofSeconds(20))
                    .header("authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, answer.statusCode(), answer.body());
            return new JsonObject(answer.body()).getJsonArray("items");
        }
    }

    /** Every regular file under {@code dir} as the admin API lists it: package-relative path and SHA-256. */
    static Set<Object> filesOf(Path dir) throws Exception {
        Set<Object> files = new HashSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
                files.add(new JsonObject().put("path", dir.relativize(p).toString().replace('\\', '/')).put("sha256", sha));
            }
        }
        return files;
    }

    // --- cases

    @Test
    void theDemoDeploysWith200AndExits0() throws Exception {
        Path demo = withFreeInPort(DEMO, "demo");

        Ran ran = run(Map.of("RAIL8_ADMIN_TOKEN", TOKEN), "deploy", demo.toString(), "--admin", adminUrl());

        assertEquals(0, ran.exit(), ran.toString());
        assertTrue(ran.out().contains("\"package\":\"demo\"") && ran.out().contains("\"demo/invoice-sync\"")
                && ran.out().contains("\"demo/invoice-async\"") && ran.out().contains("\"demo/sftp-invoice\""), "the node's answer names the three flows: " + ran);
        JsonArray items = packages();
        assertEquals(1, items.size(), "one package deployed: " + items.encode());
        JsonObject item = items.getJsonObject(0);
        assertEquals("demo", item.getString("name"), item.encode());
        assertEquals(filesOf(demo), item.getJsonArray("files").stream().collect(Collectors.toSet()),
                "the archive held every file at its package-relative path, package.yaml at the root, nothing else");
    }

    @Test
    void aBrokenPackageIs422AndExits1() throws Exception {
        Path broken = withFreeInPort(FIXTURES.resolve("broken-xslt"), "broken");

        Ran ran = run(Map.of("RAIL8_ADMIN_TOKEN", TOKEN), "deploy", broken.toString(), "--admin", adminUrl());

        assertEquals(1, ran.exit(), ran.toString());
        assertTrue(ran.out().contains("\"problems\"") && ran.out().contains("flows/invoice-to-partner.xsl"),
                "the node's 422 answer, naming the stylesheet, is printed: " + ran);
        assertEquals(0, packages().size(), "nothing deployed");
    }

    @Test
    void withoutTheTokenTheNodeRefusesAndTheDeployExits1() throws Exception {
        Path demo = withFreeInPort(DEMO, "demo");

        Ran ran = run(Map.of(), "deploy", demo.toString(), "--admin", adminUrl());

        assertEquals(1, ran.exit(), ran.toString());
        assertEquals(0, packages().size(), "nothing deployed");
    }
}
