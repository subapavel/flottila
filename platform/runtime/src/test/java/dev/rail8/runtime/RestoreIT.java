package dev.rail8.runtime;

import dev.rail8.components.http.HttpComponent;
import dev.rail8.runtime.deploy.GitRepos;
import dev.rail8.runtime.node.Node;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.node.TestJournalKey;
import dev.rail8.steps.validate.XsdValidate;
import dev.rail8.steps.xslt.SaxonXml;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static dev.rail8.runtime.NodeIT.DEMO;
import static dev.rail8.runtime.NodeIT.demoFiles;
import static dev.rail8.runtime.NodeIT.get;
import static dev.rail8.runtime.NodeIT.http;
import static dev.rail8.runtime.NodeIT.replaced;
import static dev.rail8.runtime.NodeIT.send;
import static dev.rail8.runtime.NodeIT.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node restores at start what the pool recorded — the {@code deployments} rows in its Postgres, each naming a repository and a
 * commit of the test JVM's package repositories — with no deploy and whatever its data directory holds, here nothing, so each
 * commit is fetched. A restarted node with an empty data directory runs the demo again from its row and its commit and resumes
 * the at-least-once work node 1 left queued; the item node 1 left leased runs once its lease ran out — the lease is set by SQL
 * on the case database, a fake deadline, so no case waits on a clock. A commit gone from git, or a package that needs a secret
 * the node lacks, makes the package FAILED on restore, the {@code deployments} check DEGRADED with /health 200, and the node
 * starts all the same; a deploy recovers it.
 *
 * <p>Every case runs on a database of its own and repositories of its own names; its nodes are closed after it.
 */
class RestoreIT {

    static final SharedPostgres pg = SharedPostgres.database();
    static final Pattern NUMBER = Pattern.compile("number=\"(\\d+)\"");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path tmp;
    String database;
    String url;
    /** The case's prefix of repository names: {@code demo} is committed to {@code <prefix>-demo}. */
    String prefix;
    /** Every node a case started and has not closed: closed after the case. */
    final List<Node> running = new ArrayList<>();
    final List<HttpServer> partners = new ArrayList<>();

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
    void createTheCaseDatabase() throws SQLException {
        int n = cases.incrementAndGet();
        database = "restore_" + n;
        prefix = "restore-it-" + n;
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        // getJdbcUrl() is jdbc:postgresql://host:port/<db> followed by its query string, if it has one
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String suffix = "/" + pg.getDatabaseName();
        assertTrue(path.endsWith(suffix), "the container URL ends with its database: " + base);
        url = path.substring(0, path.length() - suffix.length()) + "/" + database + (query < 0 ? "" : base.substring(query));
    }

    @AfterEach
    void closeTheNodes() throws Exception {
        try {
            for (Node n : List.copyOf(running).reversed()) close(n);
        } finally {
            for (HttpServer p : partners) p.close().await();
        }
    }

    // --- fixture

    /** A ci node {@code nodeId} on the case database, data under {@code dataDir}, deploying from the test JVM's package repositories. */
    Map<String, String> env(String nodeId, Path dataDir) {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "ci");   // ci is no deployed environment: a file:// git base and plain-http partners are allowed
        GitRepos.with(env);
        env.put("RAIL8_NODE_ID", nodeId);
        env.put("RAIL8_ADMIN_PORT", "0");
        env.put("RAIL8_PEER_PORT", "0");   // any free port: test JVMs run side by side
        env.put("RAIL8_HTTP_PORT", "0");   // the node's HTTP port on any free port too
        env.put("RAIL8_DATA_DIR", dataDir.toString());
        env.put("RAIL8_PG_HOST", pg.getHost());
        env.put("RAIL8_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("RAIL8_PG_DATABASE", database);
        env.put("RAIL8_PG_USER", pg.getUsername());
        env.put("RAIL8_PG_PASSWORD", pg.getPassword());
        env.put("RAIL8_PG_POOL_SIZE", "4");
        TestJournalKey.with(env);   // a node needs a journal master key (CORE-4c1, D-106): a required configuration
        return env;
    }

    /** A demo node (the http component, the XSLT and XSD steps), kept for closing. */
    Node start(Map<String, String> env) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env);
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids(), ctx.timer(), ctx.http())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        running.add(node);
        return node;
    }

    void close(Node node) throws Exception {
        running.remove(node);
        OnVirtualThread.call(() -> { node.close(); return null; });
    }

    /** The case's repository for the package {@code name}. */
    String repository(String name) {
        return prefix + "-" + name;
    }

    /** Commits {@code files} to the case's repository for {@code name}; the commit. */
    String commit(String name, Map<String, byte[]> files) {
        return GitRepos.shared().commit(repository(name), files);
    }

    /** Deploys {@code commit} of the case's repository for {@code name} on the admin port {@code admin}; the answer. */
    HttpResponse<String> deploy(HttpClient client, int admin, String name, String commit) throws Exception {
        return send(client, "POST", admin, "/admin/deploy", NodeIT.deployBody(repository(name), commit), "application/json");
    }

    /** The sample invoice with its {@code <Id>} set to {@code id}: the partner tells the messages apart by it. */
    static byte[] invoice(int id) throws Exception {
        byte[] sample = Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml"));
        return replaced(sample, Map.of("<Id>4711</Id>", "<Id>" + id + "</Id>"));
    }

    /** The invoice number of a mapped invoice the partner received, as the demo's stylesheet writes it. */
    static String number(String body) {
        Matcher m = NUMBER.matcher(body);
        return m.find() ? m.group(1) : "no number in " + body;
    }

    /** Each row of the case database's {@code work} table, in seq order, as {@code "<message id> <locked_by|->"}. */
    List<String> work() throws SQLException {
        return rows("select id, coalesce(locked_by, '-') from work order by seq", 2);
    }

    /** The case database's {@code deployments} rows, as one line each. */
    List<String> deploymentRows() throws SQLException {
        return rows("select package, version, repository, commit, content, deployed_at from deployments order by package", 6);
    }

    long dlqRows() throws SQLException {
        return Long.parseLong(rows("select count(*) from dlq", 1).getFirst());
    }

    List<String> rows(String sql, int columns) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
             Statement q = c.createStatement();
             ResultSet r = q.executeQuery(sql)) {
            List<String> lines = new ArrayList<>();
            while (r.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= columns; i++) line.append(i == 1 ? "" : " ").append(r.getString(i));
                lines.add(line.toString());
            }
            return lines;
        }
    }

    void sql(String statement) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    static Map<String, JsonObject> listed(int admin, String what, String key) throws Exception {
        HttpResponse<String> r = get(admin, "/admin/" + what);
        assertEquals(200, r.statusCode(), r.body());
        Map<String, JsonObject> byKey = new java.util.TreeMap<>();
        new JsonObject(r.body()).getJsonArray("items").forEach(i -> byKey.put(((JsonObject) i).getString(key), (JsonObject) i));
        return byKey;
    }

    static JsonObject health(int admin, int status) throws Exception {
        HttpResponse<String> h = get(admin, "/health");
        assertEquals(status, h.statusCode(), h.body());
        return new JsonObject(h.body());
    }

    static List<String> children(Path dir) throws Exception {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // --- cases

    /**
     * Node 1 deploys the demo from git as an at-least-once flow with {@code maxInFlight: 1} (one item runs at a time)
     * and accepts invoices 4711, 4712 and 4713 (202 each). The partner holds node 1's request for 4711 and never answers it (its
     * producer's timeout is ten minutes): node 1 leaves 4711 leased and 4712, 4713 queued, and closes. Node 2, on the same database
     * and repositories, with an empty data directory and no deploy, restores the demo from its row and its commit and runs the invoices
     * it accepts itself — but not node 1's: their bodies are in node 1's journal, the one copy (CORE-REWORK §3 "Node down", D-105
     * item 9), so they wait for node 1 and stay in work, 4711 even once its lease ran out (taking them over is P2-15/CORE-5; giving
     * them up is ACCEPT_LOSS, CORE-4c3). The witness that node 2 claims: 4714 and 4715, accepted by node 2 after node 1's items,
     * run on node 2 although the claim takes the oldest claimable item first. Nothing is parked, node 1 never ran more than 4711,
     * and the row is as the deploy wrote it: the restore wrote none.
     *
     * <p>CORE-4c2 changed the expectation (was: node 2 runs 4712, 4713 and, after the lease, 4711 — every item completes): a node
     * claims only items whose body it holds (CORE-3 item 6, WorkClaimIT).
     */
    @Test
    void aRestartedNodeWithAnEmptyDataDirRestoresWithoutADeployAndLeavesTheOtherJournalsItemsQueued() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        AtomicBoolean answering = new AtomicBoolean(false);
        List<String> held = new CopyOnWriteArrayList<>();
        List<String> answered = new CopyOnWriteArrayList<>();
        HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            String number = number(b.toString());
            if (answering.get()) {
                answered.add(number);
                req.response().putHeader("content-type", "application/xml").end(ack);
            } else {
                held.add(number);   // node 1's request: never answered, it stays in flight
            }
        })).listen(0, "127.0.0.1").await();
        partners.add(partner);
        Map<String, byte[]> files = demoFiles("http://127.0.0.1:" + partner.actualPort());
        files.put("flows/invoice-sync.yaml", replaced(files.get("flows/invoice-sync.yaml"), Map.of("mode: sync", "mode: at-least-once", "maxInFlight: 16", "maxInFlight: 1")));
        files.put("endpoints/partner-domestic.yaml", replaced(files.get("endpoints/partner-domestic.yaml"), Map.of("maxBodyBytes: 1048576", "maxBodyBytes: 1048576\n  timeoutMs: 600000")));
        String commit = commit("demo", files);

        Node one = start(env("node-1", tmp.resolve("node-1")));
        List<String> ids = new ArrayList<>();
        try (HttpClient client = http()) {
            HttpResponse<String> deployed = deploy(client, one.adminPort(), "demo", commit);
            assertEquals(200, deployed.statusCode(), "fixture: node 1 deploys the demo: " + deployed.body());
            for (int id : List.of(4711, 4712, 4713)) {
                HttpResponse<String> accepted = send(client, "POST", one.httpPort(), "/http/in", invoice(id), "application/xml");
                assertEquals(202, accepted.statusCode(), "fixture: invoice " + id + " is accepted: " + accepted.body());
                ids.add(accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("the 202 names its message id: " + accepted.headers())));
                if (id == 4711) until(() -> held.equals(List.of("4711")), "node 1's worker runs 4711 and the partner holds it");
            }
        }
        List<String> rowsAfterDeploy = deploymentRows();
        assertEquals(1, rowsAfterDeploy.size(), "fixture: the deploy wrote the demo's row: " + rowsAfterDeploy);
        close(one);
        assertEquals(List.of(ids.get(0) + " node-1", ids.get(1) + " -", ids.get(2) + " -"), work(),
                "fixture: node 1 left 4711 leased and 4712, 4713 queued");
        sql("update work set locked_until = now() + interval '1 hour' where id = '" + ids.get(0) + "'");   // a deadline the case controls
        answering.set(true);

        Path empty = Files.createDirectories(tmp.resolve("node-2"));
        assertEquals(List.of(), children(empty), "fixture: node 2's data directory is empty");
        Node two = start(env("node-2", empty));

        try (HttpClient client = http()) {
            HttpResponse<String> own = send(client, "POST", two.httpPort(), "/http/in", invoice(4714), "application/xml");
            assertEquals(202, own.statusCode(), "node 2, restored without a deploy, accepts: " + own.body());
            until(() -> answered.contains("4714"), "node 2 runs what it accepted (partner saw " + answered + ")");
            assertEquals(List.of("4714"), answered, "and nothing of node 1's, though 4712 and 4713 are older and unleased: " + answered);
            sql("update work set locked_until = now() - interval '1 second' where id = '" + ids.get(0) + "'");   // node 1's lease ran out
            HttpResponse<String> after = send(client, "POST", two.httpPort(), "/http/in", invoice(4715), "application/xml");
            assertEquals(202, after.statusCode(), after.body());
            until(() -> answered.contains("4715"), "node 2 runs what it accepts after the lease ran out (partner saw " + answered + ")");
        }
        List<String> nodeOnes = List.of(ids.get(0) + " node-1", ids.get(1) + " -", ids.get(2) + " -");
        until(() -> {
            try {
                return work().size() == nodeOnes.size();
            } catch (SQLException e) {
                throw new AssertionError("the work table could not be read", e);
            }
        }, "node 2 completed what it accepted: its work rows went");
        assertEquals(List.of("4714", "4715"), answered, "4711 is not taken over by node 2 either: its body is in node 1's journal");
        assertEquals(nodeOnes, work(), "node 1's items stay in work for node 1 (ACCEPT_LOSS is CORE-4c3)");
        assertEquals(List.of(ids.get(0) + " 1", ids.get(1) + " 0", ids.get(2) + " 0"), rows("select id, attempts from work order by seq", 2),
                "node 2 never claimed one of them, not even to release it again: only node 1's claim of 4711 counted");
        assertEquals(0, dlqRows(), "nothing was parked");
        assertEquals(List.of("4711"), held, "node 1 ran 4711 only");

        assertEquals(rowsAfterDeploy, deploymentRows(), "the restore wrote no row: the deploy's is unchanged");
        JsonObject demo = listed(two.adminPort(), "packages", "name").get("demo");
        assertTrue(demo != null && "RUNNING".equals(demo.getString("state")), "node 2 lists the demo RUNNING: " + demo);
        assertEquals(new JsonArray(), demo.getJsonArray("problems"), demo.encode());
        assertEquals(repository("demo"), demo.getString("repository"), "the row's repository: " + demo.encode());
        assertEquals(commit, demo.getString("commit"), "the row's commit: " + demo.encode());
        String hex = demo.getString("content").substring("sha256:".length());
        assertTrue(Files.isDirectory(empty.resolve("packages").resolve("demo").resolve(hex)), "node 2 wrote the commit it fetched, by its content: " + children(empty));
        assertEquals("RUNNING", listed(two.adminPort(), "flows", "id").get("demo/invoice-sync").getString("state"));
        assertEquals("UP", health(two.adminPort(), 200).getJsonObject("checks").getString("deployments"), "nothing FAILED");
    }

    /**
     * Node 1 deploys the demo from git and serves an invoice; then the demo's repository is deleted. Node 2, with an empty data
     * directory, starts all the same (a commit that cannot be fetched is no reason not to start), lists the demo FAILED — version,
     * repository and commit from its row, problems naming the commit, no flows, no files — lists no flow, answers 404 at the demo's
     * in path, and its /health is 200 with the {@code deployments} check DEGRADED (the wire names no package). A deploy of the demo
     * committed again runs it, and the check is UP.
     */
    @Test
    void aDeletedCommitRestoresAFailedPackageAndHealthIsDegraded() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b ->
                req.response().putHeader("content-type", "application/xml").end(ack))).listen(0, "127.0.0.1").await();
        partners.add(partner);
        Map<String, byte[]> files = demoFiles("http://127.0.0.1:" + partner.actualPort());
        String commit = commit("demo", files);

        Node one = start(env("node-1", tmp.resolve("node-1")));
        try (HttpClient client = http()) {
            assertEquals(200, deploy(client, one.adminPort(), "demo", commit).statusCode(), "fixture: node 1 deploys");
            assertEquals(200, send(client, "POST", one.httpPort(), "/http/in", invoice(4711), "application/xml").statusCode(), "fixture: node 1 serves the in path");
        }
        assertEquals("UP", health(one.adminPort(), 200).getJsonObject("checks").getString("deployments"), "the deployments check, UP on node 1");
        List<String> row = deploymentRows();
        close(one);
        GitRepos.shared().delete(repository("demo"));
        assertFalse(Files.exists(GitRepos.shared().dir(repository("demo"))), "fixture: the repository is gone");

        Node two = start(env("node-2", tmp.resolve("node-2")));

        JsonObject demo = listed(two.adminPort(), "packages", "name").get("demo");
        assertTrue(demo != null && "FAILED".equals(demo.getString("state")), "node 2 lists the demo FAILED: " + demo);
        JsonArray problems = demo.getJsonArray("problems");
        assertTrue(problems.stream().map(String::valueOf).anyMatch(l -> l.contains(commit)), "a problem names the missing commit: " + demo.encode());
        assertEquals(new JsonArray(), demo.getJsonArray("flows"), demo.encode());
        assertEquals(new JsonArray(), demo.getJsonArray("files"), demo.encode());
        assertEquals("0.1.0", demo.getString("version"), demo.encode());
        assertEquals(repository("demo"), demo.getString("repository"), demo.encode());
        assertEquals(commit, demo.getString("commit"), demo.encode());
        assertEquals(Map.of(), listed(two.adminPort(), "flows", "id"), "no flow runs");
        try (HttpClient client = http()) {
            assertEquals(404, send(client, "POST", two.httpPort(), "/http/in", invoice(4711), "application/xml").statusCode(),
                    "no flow takes requests at the demo's in path on node 2");
        }
        JsonObject down = health(two.adminPort(), 200);
        assertEquals("UP", down.getString("status"), down.encode());
        assertEquals("DEGRADED", down.getJsonObject("checks").getString("deployments"), down.encode());
        assertEquals("UP", down.getJsonObject("checks").getString("postgres"), "the other checks are UP: " + down.encode());
        assertFalse(down.encode().contains("demo"), "the wire names no package: " + down.encode());
        assertEquals(row, deploymentRows(), "the row stays as it was");

        try (HttpClient client = http()) {
            HttpResponse<String> deployed = deploy(client, two.adminPort(), "demo", commit("demo", files));
            assertEquals(200, deployed.statusCode(), "a deploy recovers the package: " + deployed.body());
            assertEquals(200, send(client, "POST", two.httpPort(), "/http/in", invoice(4712), "application/xml").statusCode(), "the demo serves on node 2");
        }
        assertEquals("UP", health(two.adminPort(), 200).getJsonObject("checks").getString("deployments"), "UP once the demo runs");
        assertEquals("RUNNING", listed(two.adminPort(), "packages", "name").get("demo").getString("state"));
    }

    /**
     * A package that cannot be restored does not make the node unhealthy. Node 1 runs two copies of the demo, {@code demo} and
     * {@code other}, at two in paths. The demo's domestic partner is the secret {@code secret://demo-partner}, which node 1 sets and
     * node 2 does not, so node 2 cannot restore the demo. Node 2 starts all the same: the demo is listed FAILED with its reason,
     * naming the secret, and {@code other} runs and serves. {@code /health} answers 200 with the status UP and the
     * {@code deployments} check DEGRADED, and {@code /ready} answers 200. A deploy of the demo naming its partner's URL replaces it,
     * and then the check is UP.
     */
    @Test
    void aPackageThatCannotBeRestoredLeavesTheNodeHealthyAndADeployReplacesIt() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b ->
                req.response().putHeader("content-type", "application/xml").end(ack))).listen(0, "127.0.0.1").await();
        partners.add(partner);
        String partnerBase = "http://127.0.0.1:" + partner.actualPort();
        Map<String, byte[]> demoPkg = demoFiles(partnerBase);
        demoPkg.put("endpoints/partner-domestic.yaml", replaced(demoPkg.get("endpoints/partner-domestic.yaml"), Map.of("url: " + partnerBase + "/echo", "url: secret://demo-partner")));
        Map<String, byte[]> otherFiles = demoFiles(partnerBase);
        otherFiles.put("package.yaml", replaced(otherFiles.get("package.yaml"), Map.of("name: demo", "name: other")));
        otherFiles.put("endpoints/http-in.yaml", replaced(otherFiles.get("endpoints/http-in.yaml"), Map.of("address: /in", "address: /other")));

        Map<String, String> holding = env("node-1", tmp.resolve("node-1"));
        holding.put("RAIL8_SECRET_DEMO_PARTNER", partnerBase + "/echo");
        Node one = start(holding);
        try (HttpClient client = http()) {
            assertEquals(200, deploy(client, one.adminPort(), "demo", commit("demo", demoPkg)).statusCode(), "fixture: node 1 deploys the demo");
            assertEquals(200, deploy(client, one.adminPort(), "other", commit("other", otherFiles)).statusCode(), "fixture: node 1 deploys other");
        }
        close(one);

        Node two = start(env("node-2", tmp.resolve("node-2")));   // no secret demo-partner

        Map<String, JsonObject> packages = listed(two.adminPort(), "packages", "name");
        Map<String, JsonObject> flows = listed(two.adminPort(), "flows", "id");
        JsonObject demo = packages.get("demo");
        assertEquals("FAILED", demo.getString("state"), "the demo could not be restored: " + demo.encode());
        assertTrue(demo.getJsonArray("problems").stream().map(String::valueOf).anyMatch(l -> l.contains("secret://demo-partner")),
                "its reason names the secret: " + demo.encode());
        assertFalse(flows.containsKey("demo/invoice-sync"), "nothing of the demo is listed as a flow: " + flows);
        assertEquals("RUNNING", packages.get("other").getString("state"), "the other package runs: " + packages);
        assertEquals("RUNNING", flows.get("other/invoice-sync").getString("state"), "its flow runs: " + flows);
        try (HttpClient client = http()) {
            assertEquals(200, send(client, "POST", two.httpPort(), "/http/other", invoice(4711), "application/xml").statusCode(), "the other package serves");
        }
        JsonObject degraded = health(two.adminPort(), 200);
        assertEquals("UP", degraded.getString("status"), "a FAILED package does not make the node unhealthy: " + degraded.encode());
        assertEquals("DEGRADED", degraded.getJsonObject("checks").getString("deployments"), degraded.encode());
        assertFalse(degraded.encode().contains("demo"), "the wire names no package: " + degraded.encode());
        HttpResponse<String> ready = get(two.adminPort(), "/ready");
        assertEquals(200, ready.statusCode(), "the node is ready for the packages that run: " + ready.body());

        try (HttpClient client = http()) {
            HttpResponse<String> deployed = deploy(client, two.adminPort(), "demo", commit("demo", demoFiles(partnerBase)));
            assertEquals(200, deployed.statusCode(), "a deploy replaces the FAILED package: " + deployed.body());
            assertEquals(200, send(client, "POST", two.httpPort(), "/http/in", invoice(4712), "application/xml").statusCode(), "the demo serves at its in path");
        }
        assertEquals("UP", health(two.adminPort(), 200).getJsonObject("checks").getString("deployments"), "UP once nothing is FAILED");
    }
}
