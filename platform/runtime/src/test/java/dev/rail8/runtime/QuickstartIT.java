package dev.rail8.runtime;

import dev.rail8.components.http.HttpComponent;
import dev.rail8.runtime.node.Node;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.node.TestJournalKey;
import dev.rail8.steps.validate.XsdValidate;
import dev.rail8.steps.xslt.SaxonXml;
import dev.rail8.testkit.Ports;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
 * The local quickstart as its user runs it, against a node in this JVM on a Postgres of its own (as {@link NodeIT}). The
 * working folder holds the demo as the git repository {@code demo}, with a {@code .gitattributes} of {@code * -text}, and
 * commits it; the node reads the working folder as its {@code file://} package repositories, as the quickstart's compose file
 * mounts it; a deploy is the request {@code rail8 deploy demo HEAD} sends, {@code POST /admin/deploy}. The partner the demo
 * sends to is the quickstart's JDK echo, {@code java deploy/quickstart/PartnerEcho.java <port>}, a process of its own that
 * answers every path ({@code /echo} and {@code /echo-foreign}).
 *
 * <p>No fixed port is needed: the demo is committed from a copy whose partners point at the echo's free port (every rewrite
 * asserted, as {@link NodeIT#demoFiles}); the node's HTTP and admin ports are port 0. The echo gets a cleared environment
 * ({@code PATH} and {@code HOME}).
 */
class QuickstartIT {

    static final Duration BOUND = Duration.ofSeconds(60);
    static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    static final Path DEMO = REPO.resolve("examples/demo");
    static final Path PARTNER_ECHO = REPO.resolve("deploy/quickstart/PartnerEcho.java");
    /** The node's admin token: every admin request of a case carries it, as the command line sends it from RAIL8_ADMIN_TOKEN. */
    static final String TOKEN = "quickstart-it-token";

    static final SharedPostgres pg = SharedPostgres.database();
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path work;
    Path workspace;
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
        env.put("RAIL8_ENV", "dev");   // the quickstart's environment (docker-compose.yml)
        env.put("RAIL8_NODE_ID", "quickstart-it");
        env.put("RAIL8_ADMIN_PORT", "0");
        env.put("RAIL8_PEER_PORT", "0");   // any free port: test JVMs run side by side
        env.put("RAIL8_HTTP_PORT", "0");   // the node's HTTP port on any free port too
        env.put("RAIL8_DATA_DIR", Files.createDirectories(work.resolve("data")).toString());
        workspace = Files.createDirectories(work.resolve("workspace"));
        env.put("RAIL8_GIT_BASE", workspace.toUri().toString());   // the quickstart's working folder, read as file:///workspace/
        env.put("RAIL8_PG_HOST", pg.getHost());
        env.put("RAIL8_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("RAIL8_PG_DATABASE", database);
        env.put("RAIL8_PG_USER", pg.getUsername());
        env.put("RAIL8_PG_PASSWORD", pg.getPassword());
        env.put("RAIL8_PG_POOL_SIZE", "4");
        env.put("RAIL8_ADMIN_TOKEN", TOKEN);   // /admin/** needs the bearer token (allowed on the loopback bind too)
        TestJournalKey.with(env);   // a node needs a journal master key (CORE-4c1, D-106): a required configuration
        NodeConfig config = NodeConfig.from(env);
        SaxonXml xml = new SaxonXml();
        node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids(), ctx.timer(), ctx.http())),
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
     * The quickstart's path: the demo committed in the working folder deploys at {@code HEAD}, and the node's answer names its
     * flows; the node lists the package under the repository and the commit, holding exactly the commit's files at their
     * package-relative paths ({@code .gitattributes} among them, {@code .git} not); a CZK invoice POSTed to the demo's door comes
     * back with the echo's answer; the admin API finds the message by its key, shows the six steps of invoice-sync, and serves
     * the partner step's recorded body — the answer the caller got.
     */
    @Test
    void theDemoCommittedInTheWorkingFolderDeploysAndACzkInvoiceGetsTheEchosAnswerAndItsTimeline() throws Exception {
        int echoPort = startTheEcho();
        Path demo = demoCopy(echoPort);
        String commit = commit(demo);

        HttpResponse<String> deployed = deploy("demo", "HEAD");

        assertEquals(200, deployed.statusCode(), deployed.body());
        assertTrue(deployed.body().contains("\"package\":\"demo\"") && deployed.body().contains("\"demo/invoice-sync\""),
                "the node answers the flows it deployed: " + deployed.body());
        JsonObject item = onlyPackage();
        assertEquals("demo", item.getString("name"), item.encode());
        assertEquals("demo", item.getString("repository"), item.encode());
        assertEquals(commit, item.getString("commit"), "HEAD is read as the commit it names: " + item.encode());
        assertEquals(filesOf(demo), item.getJsonArray("files").stream().collect(Collectors.toSet()),
                "the commit held every file of the package at its package-relative path, package.yaml at the root, nothing else");

        String invoice = Files.readString(DEMO.resolve("fixtures/sample-invoice.xml"));
        HttpResponse<String> answer = post(node.httpPort(), "/http/in", invoice);

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
        commit(demoCopy(echoPort));
        HttpResponse<String> deployed = deploy("demo", "HEAD");
        assertEquals(200, deployed.statusCode(), "fixture: the demo deploys: " + deployed.body());
        String invoice = new String(replaced(Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")),
                Map.of("<Id>4711</Id>", "<Id>4712</Id>", "<Currency>CZK</Currency>", "<Currency>EUR</Currency>")), StandardCharsets.UTF_8);

        HttpResponse<String> answer = post(node.httpPort(), "/http/in", invoice);

        assertEquals(200, answer.statusCode(), "the foreign partner's path answers: " + answer.body());
        assertPartnersAnswer(answer.body(), "4712", "EUR");
        JsonObject timeline = timelineOf("4712");
        assertEquals("demo/invoice-sync", timeline.getString("flow"), timeline.encode());
    }

    /**
     * The quickstart's steps commit before every deploy: an edit of the working folder that is not committed is not deployed —
     * a deploy of {@code HEAD} deploys the commit before it again — and once committed, a deploy of {@code HEAD} deploys it.
     */
    @Test
    void anEditIsDeployedOnceItIsCommitted() throws Exception {
        Path demo = demoCopy(freePort());
        String first = commit(demo);
        assertEquals(200, deploy("demo", "HEAD").statusCode(), "fixture: the demo deploys");
        rewrite(demo.resolve("package.yaml"), Map.of("version: 0.1.0", "version: 0.2.0"));

        HttpResponse<String> uncommitted = deploy("demo", "HEAD");

        assertEquals(200, uncommitted.statusCode(), uncommitted.body());
        assertEquals(first, onlyPackage().getString("commit"), "the edit is not committed: HEAD is still the first commit");
        assertEquals("0.1.0", onlyPackage().getString("version"));

        String second = commit(demo);
        HttpResponse<String> committed = deploy("demo", "HEAD");

        assertEquals(200, committed.statusCode(), committed.body());
        assertNotEquals(first, second, "fixture: the edit is a commit of its own");
        assertEquals(second, onlyPackage().getString("commit"), "HEAD names the edit's commit");
        assertEquals("0.2.0", onlyPackage().getString("version"));
    }

    // --- the quickstart's pieces

    /**
     * The working folder {@code dir} as the quickstart keeps it: a git repository on {@code main}, with a {@code .gitattributes} of
     * {@code * -text}, every file committed; answers the commit's id.
     */
    static String commit(Path dir) throws IOException {
        Path attributes = dir.resolve(".gitattributes");
        if (Files.notExists(attributes)) Files.writeString(attributes, "* -text\n");
        PersonIdent who = new PersonIdent("Quickstart IT", "quickstart-it@rail8.invalid");
        try (Git git = Files.exists(dir.resolve(".git")) ? Git.open(dir.toFile()) : Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            return git.commit().setMessage("The quickstart's demo\n").setAuthor(who).setCommitter(who).setSign(false).call().name();
        } catch (GitAPIException e) {
            throw new IOException("fixture: " + dir + " not committed", e);
        }
    }

    /** The deploy {@code rail8 deploy <repository> <commit>} sends: {@code POST /admin/deploy} with the node's token. */
    HttpResponse<String> deploy(String repository, String commit) throws IOException, InterruptedException {
        byte[] body = new JsonObject().put("repository", repository).put("commit", commit).encode().getBytes(StandardCharsets.UTF_8);
        try (HttpClient client = http()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.adminPort() + "/admin/deploy")).timeout(Duration.ofSeconds(60))
                    .header("authorization", "Bearer " + TOKEN).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** The one package the admin API lists. */
    JsonObject onlyPackage() throws IOException, InterruptedException {
        HttpResponse<String> packages = get(node.adminPort(), "/admin/packages");
        assertEquals(200, packages.statusCode(), packages.body());
        JsonArray items = new JsonObject(packages.body()).getJsonArray("items");
        assertEquals(1, items.size(), "one package deployed: " + packages.body());
        return items.getJsonObject(0);
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
     * The demo copied to the working folder's {@code demo}: http-in served at {@code /http/in} on the node's HTTP port as the demo
     * has it, both partners at the echo on 127.0.0.1:{@code echoPort} (their paths kept), every rewrite asserted.
     */
    Path demoCopy(int echoPort) throws IOException {
        Path copy = workspace.resolve("demo");
        try (Stream<Path> walk = Files.walk(DEMO)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (NodeIT.addedByP206(DEMO.relativize(p).toString().replace('\\', '/'))) continue;   // the quickstart is the sync demo: no stack secrets here
                Path target = copy.resolve(DEMO.relativize(p).toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target);
            }
        }
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

    /** Every regular file under {@code dir} but its {@code .git} as the admin API lists it: package-relative path and SHA-256. */
    static Set<Object> filesOf(Path dir) throws Exception {
        Set<Object> files = new HashSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).filter(p -> !p.startsWith(dir.resolve(".git"))).toList()) {
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
        return Ports.free();
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
