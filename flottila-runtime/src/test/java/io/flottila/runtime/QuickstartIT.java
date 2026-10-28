package io.flottila.runtime;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-05a: the local quickstart as its user runs it, against a node in this JVM on a Postgres of its own (as {@link NodeIT}).
 * {@code deploy/quickstart/deploy.sh <package-dir> [name]} is run as a process — from the repository root, the way
 * docs/QUICKSTART.md runs it — with {@code FLOTTILA_ADMIN} pointing at the node's admin port; it zips the package as the admin
 * API expects ({@code package.yaml} at the archive root) and PUTs it to {@code /admin/packages/<name>}. The partner the demo
 * sends to is the quickstart's JDK echo, {@code java deploy/quickstart/PartnerEcho.java <port>}, a process of its own that
 * answers every path ({@code /echo} and {@code /echo-foreign}).
 *
 * <p>No fixed port is needed: the demo is deployed from a copy whose {@code http-in} listens on a free loopback port and whose
 * partners point at the echo's free port (every rewrite asserted, as {@link NodeIT#demoFiles}); the admin API listens on port 0.
 * The child processes get a cleared environment ({@code PATH}, {@code HOME} and what the case sets): an inherited proxy
 * variable would send the script's curl to the proxy instead of the loopback node.
 */
class QuickstartIT {

    static final Duration BOUND = Duration.ofSeconds(60);
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final Path DEMO = REPO.resolve("packages/demo");
    static final Path DEPLOY_SH = REPO.resolve("deploy/quickstart/deploy.sh");
    static final Path PARTNER_ECHO = REPO.resolve("deploy/quickstart/PartnerEcho.java");
    /** The node's admin token: deploy.sh sends it from FLOTTILA_ADMIN_TOKEN, the case's own admin requests carry it too. */
    static final String TOKEN = "quickstart-it-token";

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path work;
    String database;
    Node node;
    Process echo;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void startTheNode() throws Exception {
        database = "quickstart_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");   // the quickstart's environment (docker-compose.yml)
        env.put("FLOTTILA_NODE_ID", "quickstart-it");
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", Files.createDirectories(work.resolve("data")).toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        env.put("FLOTTILA_ADMIN_TOKEN", TOKEN);   // /admin/** needs the bearer token (allowed on the loopback bind too)
        NodeConfig config = NodeConfig.from(env);
        SaxonXml xml = new SaxonXml();
        node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
    }

    @AfterEach
    void stopTheNodeAndTheEcho() throws Exception {
        try {
            if (node != null) OnVirtualThread.call(() -> { node.close(); return null; });
        } finally {
            if (echo != null) {
                echo.destroy();
                if (!echo.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS)) {
                    echo.destroyForcibly();
                    assertTrue(echo.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS), "the echo " + echo.pid() + " survived SIGKILL");
                }
            }
        }
    }

    // --- cases

    /**
     * The quickstart's path: deploy.sh deploys the demo (no name given: the directory's), prints the node's answer and exits 0;
     * the node holds exactly the package's files at their package-relative paths (the zip layout); a CZK invoice POSTed to the
     * demo's door comes back with the echo's answer; the admin API finds the message by its key, shows the six steps of
     * invoice-sync, and serves the partner step's recorded body — the answer the caller got.
     */
    @Test
    void deployShDeploysTheDemoAndACzkInvoiceGetsTheEchosAnswerAndItsTimeline() throws Exception {
        int echoPort = startTheEcho();
        int inPort = freePort();
        Path demo = demoCopy(inPort, echoPort);

        Run deployed = deploySh(demo.toString());

        assertEquals(0, deployed.exit(), "a 200 deploy exits 0: " + deployed.output());
        assertTrue(deployed.output().contains("\"package\":\"demo\"") && deployed.output().contains("\"demo/invoice-sync\""),
                "the script prints the node's answer: " + deployed.output());
        HttpResponse<String> packages = get(node.adminPort(), "/admin/packages");
        assertEquals(200, packages.statusCode(), packages.body());
        JsonArray items = new JsonObject(packages.body()).getJsonArray("items");
        assertEquals(1, items.size(), "one package deployed: " + packages.body());
        JsonObject item = items.getJsonObject(0);
        assertEquals("demo", item.getString("name"), "deployed under the directory's name: " + item.encode());
        assertEquals(filesOf(demo), item.getJsonArray("files").stream().collect(Collectors.toSet()),
                "the archive held every file of the package at its package-relative path, package.yaml at the root, nothing else");

        String invoice = Files.readString(DEMO.resolve("fixtures/sample-invoice.xml"));
        HttpResponse<String> answer = post(inPort, "/in", invoice);

        assertEquals(200, answer.statusCode(), "the demo answers the invoice: " + answer.body());
        assertPartnersAnswer(answer.body(), "4711", "CZK");
        JsonObject timeline = timelineOf("4711");
        assertEquals("demo/invoice-sync", timeline.getString("flow"), timeline.encode());
        List<String> names = new ArrayList<>();
        int partnerStep = -1;
        for (Object s : timeline.getJsonArray("steps")) {
            JsonObject step = (JsonObject) s;
            names.add(step.getString("name"));
            if ("partner".equals(step.getString("name"))) partnerStep = step.getInteger("step");
        }
        assertEquals(List.of("ingress", "validate", "extractKey", "route", "toPartner", "partner"), names, "the steps of invoice-sync: " + timeline.encode());
        HttpResponse<String> body = get(node.adminPort(), "/admin/messages/" + timeline.getString("id") + "/steps/" + partnerStep + "/body");
        assertEquals(200, body.statusCode(), body.body());
        assertEquals(answer.body(), body.body(), "the partner step recorded the echo's answer, the one the caller got");
    }

    /** A foreign invoice is routed to partner-foreign ({@code /echo-foreign}): the echo answers that path too. */
    @Test
    void aForeignInvoiceGetsTheEchosAnswerFromEchoForeign() throws Exception {
        int echoPort = startTheEcho();
        int inPort = freePort();
        Path demo = demoCopy(inPort, echoPort);
        Run deployed = deploySh(demo.toString(), "demo");
        assertEquals(0, deployed.exit(), "fixture: the demo deploys: " + deployed.output());
        String invoice = new String(replaced(Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")),
                Map.of("<Id>4711</Id>", "<Id>4712</Id>", "<Currency>CZK</Currency>", "<Currency>EUR</Currency>")), StandardCharsets.UTF_8);

        HttpResponse<String> answer = post(inPort, "/in", invoice);

        assertEquals(200, answer.statusCode(), "the foreign partner's path answers: " + answer.body());
        assertPartnersAnswer(answer.body(), "4712", "EUR");
        JsonObject timeline = timelineOf("4712");
        assertEquals("demo/invoice-sync", timeline.getString("flow"), timeline.encode());
    }

    /**
     * A refused deploy — the name in the URL is not the one in package.yaml: the node's 422 — makes the script exit non-zero and
     * print the node's answer (which names the URL's package: the second argument reached the node); nothing is deployed.
     */
    @Test
    void deployShExitsNonZeroAndPrintsTheAnswerWhenTheNodeRefuses() throws Exception {
        Path demo = demoCopy(freePort(), freePort());

        Run refused = deploySh(demo.toString(), "other");

        assertNotEquals(0, refused.exit(), "a non-2xx answer exits non-zero: " + refused.output());
        assertTrue(refused.output().contains("package.yaml names the package 'demo', the deploy 'other'"),
                "the script prints the node's refusal (the PUT reached the node under the given name): " + refused.output());
        HttpResponse<String> packages = get(node.adminPort(), "/admin/packages");
        assertEquals(new JsonArray(), new JsonObject(packages.body()).getJsonArray("items"), "nothing deployed: " + packages.body());
    }

    /**
     * The node needs a token: deploy.sh run without FLOTTILA_ADMIN_TOKEN, or with a wrong one, gets the node's 401, prints it and
     * exits non-zero; nothing is deployed. (That the right token is sent is pinned by every deploy that exits 0.)
     */
    @Test
    void deployShExitsNonZeroOnTheNodes401WithoutTheTokenOrWithAWrongOne() throws Exception {
        Path demo = demoCopy(freePort(), freePort());

        Run without = deploySh(Map.of(), demo.toString());
        Run wrong = deploySh(Map.of("FLOTTILA_ADMIN_TOKEN", "not-the-token"), demo.toString());

        for (Run refused : List.of(without, wrong)) {
            assertNotEquals(0, refused.exit(), "a 401 exits non-zero: " + refused.output());
            assertTrue(refused.output().contains("this route needs Authorization: Bearer <token>"),
                    "the script prints the node's 401 (the PUT reached the node): " + refused.output());
        }
        HttpResponse<String> packages = get(node.adminPort(), "/admin/packages");
        assertEquals(200, packages.statusCode(), packages.body());
        assertEquals(new JsonArray(), new JsonObject(packages.body()).getJsonArray("items"), "nothing deployed: " + packages.body());
    }

    /**
     * Without zip on the PATH the script archives with the JDK's jar: PATH is a directory of links to sh, the tools deploy.sh
     * calls besides zip, and $JAVA_HOME/bin/jar — no zip. The deploy exits 0 and the node holds exactly the package's files at
     * their package-relative paths, the same layout as the zip path.
     */
    @Test
    void withoutZipDeployShArchivesWithTheJdksJarInTheSameLayout() throws Exception {
        Path bin = Files.createDirectories(work.resolve("bin"));
        for (String tool : List.of("sh", "curl", "mktemp", "rm", "basename", "cat")) Files.createSymbolicLink(bin.resolve(tool), onPath(tool));
        Files.createSymbolicLink(bin.resolve("jar"), Path.of(System.getProperty("java.home"), "bin", "jar"));
        assertTrue(Files.notExists(bin.resolve("zip")), "fixture: no zip on the script's PATH");
        Path demo = demoCopy(freePort(), freePort());

        Run deployed = deploySh(Map.of("PATH", bin.toString(), "FLOTTILA_ADMIN_TOKEN", TOKEN), demo.toString());

        assertEquals(0, deployed.exit(), "the jar-built archive deploys: " + deployed.output());
        HttpResponse<String> packages = get(node.adminPort(), "/admin/packages");
        JsonArray items = new JsonObject(packages.body()).getJsonArray("items");
        assertEquals(1, items.size(), "one package deployed: " + packages.body());
        assertEquals(filesOf(demo), items.getJsonObject(0).getJsonArray("files").stream().collect(Collectors.toSet()),
                "the jar-built archive held every file of the package at its package-relative path, package.yaml at the root, nothing else");
    }

    // --- the quickstart's pieces

    record Run(int exit, String output) {}

    /** {@code tool} as the test JVM's PATH resolves it. */
    static Path onPath(String tool) {
        for (String dir : System.getenv("PATH").split(":")) {
            Path candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) return candidate;
        }
        return fail("fixture: " + tool + " is not on the PATH");
    }

    /** deploy.sh with {@code args} and the node's token. */
    Run deploySh(String... args) throws Exception {
        return deploySh(Map.of("FLOTTILA_ADMIN_TOKEN", TOKEN), args);
    }

    /**
     * deploy.sh with {@code args}, cwd the repository root, {@code FLOTTILA_ADMIN} the node's admin URL, {@code extra} laid over the
     * plain environment (PATH, HOME); stdout and stderr together.
     */
    Run deploySh(Map<String, String> extra, String... args) throws Exception {
        assertTrue(Files.isRegularFile(DEPLOY_SH) && Files.isExecutable(DEPLOY_SH), "deploy/quickstart/deploy.sh exists and is executable: " + DEPLOY_SH);
        List<String> command = new ArrayList<>(List.of(DEPLOY_SH.toString()));
        command.addAll(List.of(args));
        Path output = Files.createTempFile(work, "deploy-sh-", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).directory(REPO.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        plainEnvironment(builder);
        builder.environment().put("FLOTTILA_ADMIN", "http://127.0.0.1:" + node.adminPort());
        builder.environment().putAll(extra);
        Process script = builder.start();
        if (!script.waitFor(BOUND.toSeconds(), TimeUnit.SECONDS)) {
            script.destroyForcibly();
            fail("deploy.sh did not exit within " + BOUND + ": " + Files.readString(output));
        }
        return new Run(script.exitValue(), Files.readString(output));
    }

    /** The quickstart's echo on a free loopback port, once it accepts connections; fails with its output when it exits first. */
    int startTheEcho() throws Exception {
        assertTrue(Files.isRegularFile(PARTNER_ECHO), "the quickstart's partner echo exists: " + PARTNER_ECHO);
        int port = freePort();
        Path output = work.resolve("echo.txt");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), PARTNER_ECHO.toString(), String.valueOf(port))
                .directory(REPO.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        plainEnvironment(builder);
        echo = builder.start();
        until(() -> {
            if (!echo.isAlive()) {
                try {
                    fail("the echo exited with " + echo.exitValue() + " before it listened: " + Files.readString(output));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return !refuses(port);
        }, "the echo listens on " + port);
        return port;
    }

    /** PATH and HOME only: no proxy variable, no JAVA_TOOL_OPTIONS. */
    static void plainEnvironment(ProcessBuilder builder) {
        String path = System.getenv("PATH");
        builder.environment().clear();
        builder.environment().put("PATH", path == null ? "/usr/bin:/bin" : path);
        builder.environment().put("HOME", System.getProperty("user.home"));
    }

    /**
     * The demo copied to {@code <work>/demo}: http-in on 127.0.0.1:{@code inPort}, both partners at the echo on
     * 127.0.0.1:{@code echoPort} (their paths kept), every rewrite asserted.
     */
    Path demoCopy(int inPort, int echoPort) throws IOException {
        Path copy = work.resolve("demo");
        try (Stream<Path> walk = Files.walk(DEMO)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path target = copy.resolve(DEMO.relativize(p).toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target);
            }
        }
        rewrite(copy.resolve("endpoints/http-in.yaml"), Map.of("host: 0.0.0.0", "host: 127.0.0.1", "port: 8080", "port: " + inPort));
        for (String partner : List.of("endpoints/partner-domestic.yaml", "endpoints/partner-foreign.yaml")) {
            rewrite(copy.resolve(partner), Map.of("http://localhost:8090", "http://127.0.0.1:" + echoPort));
        }
        return copy;
    }

    static void rewrite(Path file, Map<String, String> rewrites) throws IOException {
        Files.write(file, replaced(Files.readAllBytes(file), rewrites));
    }

    static byte[] replaced(byte[] file, Map<String, String> rewrites) {
        String text = new String(file, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> r : rewrites.entrySet()) {
            assertTrue(text.contains(r.getKey()), "fixture: the demo file holds '" + r.getKey() + "' to rewrite: " + text);
            text = text.replace(r.getKey(), r.getValue());
        }
        return text.getBytes(StandardCharsets.UTF_8);
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

    /**
     * The partner's answer as the demo's caller gets it: the echo answers either the demo's fixed ack or the body it was sent —
     * the invoice as invoice-to-partner.xsl mapped it. Either differs from the invoice the caller sent.
     */
    static void assertPartnersAnswer(String body, String number, String currency) throws IOException {
        String ack = Files.readString(DEMO.resolve("fixtures/partner-ack.xml"));
        boolean fixedAck = body.equals(ack);
        boolean echoed = body.contains("<PartnerInvoice number=\"" + number + "\" currency=\"" + currency + "\"");
        assertTrue(fixedAck || echoed, "the answer is the echo's: the demo's partner-ack.xml or the mapped invoice " + number + " (" + currency + "): " + body);
    }

    /** The timeline of the one message the admin API finds under {@code invoiceId:<id>}, once all six steps are recorded. */
    JsonObject timelineOf(String invoiceId) {
        AtomicReference<JsonObject> found = new AtomicReference<>();
        until(() -> {
            try {
                JsonArray ids = new JsonObject(get(node.adminPort(), "/admin/messages?key=invoiceId:" + invoiceId).body()).getJsonArray("items");
                if (ids.isEmpty()) return false;
                assertEquals(1, ids.size(), "one message under invoiceId:" + invoiceId + ": " + ids.encode());
                HttpResponse<String> timeline = get(node.adminPort(), "/admin/messages/" + ids.getString(0));
                if (timeline.statusCode() != 200) return false;
                JsonObject t = new JsonObject(timeline.body());
                found.set(t);
                return t.getJsonArray("steps").size() == 6;
            } catch (IOException | InterruptedException e) {
                throw new AssertionError("the admin API did not answer", e);
            }
        }, "the admin API finds invoiceId:" + invoiceId + " and its six steps are recorded; last seen " + found);
        return found.get();
    }

    // --- the outside world

    static HttpClient http() {
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }

    static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        try (HttpClient client = http()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                            .header("authorization", "Bearer " + TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    static HttpResponse<String> post(int port, String path, String xml) throws IOException, InterruptedException {
        try (HttpClient client = http()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/xml").POST(HttpRequest.BodyPublishers.ofString(xml)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    static boolean refuses(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port), 5_000);
            return false;
        } catch (ConnectException refused) {
            return true;
        } catch (SocketException reset) {
            return false;
        } catch (IOException other) {
            throw new UncheckedIOException(other);
        }
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after {@link #BOUND} naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within " + BOUND + ": " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
