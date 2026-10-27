package io.flottila.runtime;

import io.flottila.api.Component;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.components.http.HttpComponent;
import io.flottila.components.mock.DisguisedMock;
import io.flottila.components.mock.nested.NestedMock;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.node.ComponentContext;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.PlainComponent;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.BindException;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09b: the node against a real Postgres (D-057 b amended). {@code Node.start} migrates the database, opens the pool and
 * the stores, builds the components from its own context and serves {@code /health} on the admin port; {@code close()}
 * stops the admin server, then flushes and closes the store, then closes the pool; {@code Main} refuses a bad
 * configuration with one line and exit code 2. Every case runs on a database of its own (FIX-5); the observer pool sits on
 * the container's own database, so it never counts as a connection of the node's.
 */
class NodeIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;
    static Pool observer;

    @TempDir Path data;
    String database;
    String url;
    Node node;                               // closed after the case, unless the case closes it itself
    OnVirtualThread.Call<Void> closing;      // the case's own close, joined after the case

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        observer = PgBuilder.pool().connectingTo(options(pg.getDatabaseName())).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterAll
    static void stop() {
        observer.close().await();
        vertx.close().await();
        pg.stop();
    }

    static PgConnectOptions options(String database) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    @BeforeEach
    void createTheCaseDatabase() throws SQLException {
        database = "node_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        // getJdbcUrl() is jdbc:postgresql://host:port/<db> followed by the container's query string (?loggerLevel=OFF)
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String suffix = "/" + pg.getDatabaseName();
        assertTrue(path.endsWith(suffix), "the container URL ends with its database: " + base);
        url = path.substring(0, path.length() - suffix.length()) + "/" + database + (query < 0 ? "" : base.substring(query));
    }

    @AfterEach
    void closeTheNode() throws Exception {
        if (closing != null) OnVirtualThread.join(closing);
        else if (node != null) OnVirtualThread.call(() -> { node.close(); return null; });
    }

    /** The environment of a node on the case's database, admin on any free port, data under the case's temporary directory. */
    Map<String, String> env(String flottilaEnv) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", flottilaEnv);
        env.put("FLOTTILA_NODE_ID", "node-it");
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        return env;
    }

    Node start(Map<String, String> env, Function<ComponentContext, List<Component>> components) throws Exception {
        NodeConfig config = NodeConfig.from(env);
        node = OnVirtualThread.call(() -> Node.start(config, vertx, components, List.of(), List.of()));
        return node;
    }

    @Test
    void startMigratesAndServesHealth() throws Exception {
        Map<String, String> env = env("prod");
        env.put("FLOTTILA_BLOB_TREE_LIMIT", "65536");
        NodeConfig config = NodeConfig.from(env);
        assertTrue(config.deployed(), "a deployed node: it accepts the real component and a plain one");
        List<ComponentContext> asked = new CopyOnWriteArrayList<>();
        SaxonXml xml = new SaxonXml();
        node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> {
            asked.add(ctx);
            return List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids()), new PlainComponent("sftp"));
        }, Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));

        assertEquals(1, asked.size(), "the node built its components once, from its own context");
        ComponentContext ctx = asked.get(0);
        assertSame(vertx, ctx.vertx(), "the components run on the caller's Vert.x");
        assertNotNull(ctx.ids(), "ids");
        assertNotNull(ctx.timer(), "timer");
        assertNotNull(ctx.clock(), "clock");
        assertEquals(65536L, ctx.blobs().treeLimit(), "the components' BlobStore is the node's, built from FLOTTILA_BLOB_TREE_LIMIT");

        assertTrue(node.adminPort() > 0, "the port actually bound, not the configured 0: " + node.adminPort());
        HttpResponse<String> health = get(node.adminPort(), "/health");
        assertEquals(200, health.statusCode(), health.body());
        JsonObject body = new JsonObject(health.body());
        assertEquals("UP", body.getString("status"), body.encode());
        assertFalse(body.getJsonObject("checks").isEmpty(), "the node reports its checks, Postgres among them: " + body.encode());

        Migrations.Result again = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals(0, again.migrationsExecuted(), "the node migrated the database to the latest packaged version before it served: " + again);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "ci"})
    void devAcceptsAMock(String flottilaEnv) throws Exception {
        assertFalse(NodeConfig.from(env(flottilaEnv)).deployed(), flottilaEnv + " is not deployed");
        Set<String> before = Set.copyOf(vertx.deploymentIDs());
        // one scheme each (two components for one scheme are refused): mock, as2, http (DisguisedMock), sftp (NestedMock)
        start(env(flottilaEnv), ctx -> List.of(new PlainComponent("mock"), new RecordingComponent("as2"), new DisguisedMock(), new NestedMock()));
        assertEquals(200, get(node.adminPort(), "/health").statusCode(), "the node runs with every kind of mock binding");
        assertNotEquals(before, Set.copyOf(vertx.deploymentIDs()),
                "the running node's admin server is a deployed verticle: what NodeTest's 'nothing left listening' looks at");
    }

    /** A spooled object is encrypted with a key that died with its process: what a previous run left under the spool is garbage. */
    @Test
    void startClearsTheSpoolLeftByAPreviousRun() throws Exception {
        Path spool = data.resolve("spool");
        Path leftover = Files.writeString(Files.createDirectories(spool).resolve("leftover.bin"), "spooled by the previous run");
        Path nested = Files.writeString(Files.createDirectories(spool.resolve("previous-run")).resolve("part-0"), "and in a directory of its own");
        Path besides = Files.writeString(data.resolve("not-the-spool.txt"), "outside the spool");
        start(env("ci"), ctx -> List.of(new PlainComponent("sftp")));
        assertEquals(200, get(node.adminPort(), "/health").statusCode(), "the node runs");
        assertFalse(Files.exists(leftover), "a file the previous run left in the spool is gone after start");
        assertFalse(Files.exists(nested.getParent()), "a directory the previous run left in the spool is gone with its files");
        assertTrue(Files.exists(besides), "only the spool is cleared: the rest of the data directory stays");
    }

    @Test
    void closeStopsTheAdminServerThenFlushesTheStore() throws Exception {
        Node started = start(env("ci"), ctx -> List.of());
        int port = started.adminPort();
        assertEquals(200, get(port, "/health").statusCode(), "serving before close");
        Future<Void> recorded;
        PgConnection holder = PgConnection.connect(vertx, options(database)).await();
        try {
            Transaction held = holder.begin().await();
            // an uncommitted payload row with the snapshot's hash: the snapshot's batch waits on it, inside close's flush
            holder.preparedQuery("insert into payloads(hash, size, algorithm) values ($1, 1, 'sha256-64')").execute(Tuple.of(hashBytes(4711L))).await();
            recorded = started.messages().record(snapshot("m-flushed", 4711L));
            until(() -> lockWaiters() == 1, "the snapshot's batch waits on the held payload row");
            closing = OnVirtualThread.start(started::close);
            until(() -> refuses(port), "the admin port refuses while close still waits for the store");
            assertFalse(closing.isDone(), "close waits for the store's flush: the snapshot is still held");
            assertEquals(1, lockWaiters(), "the snapshot's batch still waits: the admin server stopped before the store was flushed");
            assertFalse(recorded.isComplete(), "the snapshot is not committed yet: " + recorded);
            held.rollback().await();
        } finally {
            holder.close().await();
        }
        OnVirtualThread.join(closing);
        assertTrue(recorded.succeeded(), "close's flush committed the snapshot recorded before it: " + recorded.cause());
        until(() -> connections() == 0, "close closed the node's pool: no connection to the case database is left");
        PgConnection reader = PgConnection.connect(vertx, options(database)).await();
        try {
            long rows = reader.query("select count(*) from messages where id = 'm-flushed'").execute().await().iterator().next().getLong(0);
            assertEquals(1, rows, "the snapshot recorded before close is in Postgres after it");
        } finally {
            reader.close().await();
        }
        Future<Void> late = started.messages().record(snapshot("m-late", 4712L));
        late.otherwiseEmpty().await(20, TimeUnit.SECONDS);
        assertTrue(late.failed(), "a snapshot recorded after close fails: the store and its pool are closed");
        assertTrue(refuses(port), "and the admin port stays closed");
    }

    /**
     * An admin port another socket holds: start fails after it migrated and opened the pool and the stores, and closes them
     * again — no connection to the case database is left, no verticle stays deployed.
     */
    @Test
    void anAdminPortAlreadyInUseFailsStartWithTheBindError() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            Map<String, String> env = env("ci");
            env.put("FLOTTILA_ADMIN_PORT", String.valueOf(taken.getLocalPort()));
            NodeConfig config = NodeConfig.from(env);
            Set<String> before = Set.copyOf(vertx.deploymentIDs());
            Exception failed = null;
            try {   // an assertion error — a start still running after the bound — is not caught: it fails the case as it is
                node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new PlainComponent("sftp")), List.of(), List.of()));
            } catch (Exception expected) {
                failed = expected;
            }
            assertNotNull(failed, "start fails while another socket holds the admin port (it returned a node listening on " + (node == null ? "?" : node.adminPort()) + ")");
            assertTrue(isBindError(failed), "the failure is the admin port's bind error, not something else: " + causes(failed));
            assertEquals(0, Migrations.migrate(url, pg.getUsername(), pg.getPassword()).migrationsExecuted(), "start got as far as the migrations: " + failed);
            until(() -> connections() == 0, "the failed start closed what it opened: no connection to the case database is left");
            assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "no verticle stays deployed");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FLOTTILA_ENV", "FLOTTILA_ADMIN_PORT"})
    void mainRunRefusesABadConfigWithExitTwo(String bad) throws Exception {
        Map<String, String> env = env("ci");
        if (bad.equals("FLOTTILA_ENV")) env.remove("FLOTTILA_ENV");
        else env.put(bad, "not-a-port");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = OnVirtualThread.call(() -> Main.run(env, err));
        List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, code, "a refused configuration exits with 2: " + lines);
        assertEquals(1, lines.size(), "one line on stderr, no stack trace: " + lines);
        assertTrue(lines.get(0).contains(bad), "the line names the key: " + lines.get(0));
        assertEquals(0, connections(), "the refused node never connected to its database");
    }

    // --- helpers

    // --- P1-09d1b: deploy over the admin API, close drains

    static final Path DEMO = Path.of("../packages/demo");

    /** A free loopback port: bound once and released (the demo's consumer binds it right after). */
    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    /** The demo package as a zip, its http-in endpoint on 127.0.0.1:{@code inPort}, its partners at {@code partnerBase}; every rewrite asserted. */
    static Map<String, byte[]> demoFiles(int inPort, String partnerBase) throws IOException {
        Map<String, byte[]> files = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(DEMO)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) files.put(DEMO.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
        files.put("endpoints/http-in.yaml", replaced(files.get("endpoints/http-in.yaml"), Map.of("host: 0.0.0.0", "host: 127.0.0.1", "port: 8080", "port: " + inPort)));
        for (String partner : List.of("endpoints/partner-domestic.yaml", "endpoints/partner-foreign.yaml")) {
            files.put(partner, replaced(files.get(partner), Map.of("http://localhost:8090", partnerBase)));
        }
        return files;
    }

    static byte[] replaced(byte[] file, Map<String, String> rewrites) {
        String text = new String(file, StandardCharsets.UTF_8);
        for (Map.Entry<String, String> r : rewrites.entrySet()) {
            assertTrue(text.contains(r.getKey()), "fixture: the demo file holds '" + r.getKey() + "' to rewrite: " + text);
            text = text.replace(r.getKey(), r.getValue());
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                out.putNextEntry(new java.util.zip.ZipEntry(f.getKey()));
                out.write(f.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static HttpResponse<String> send(HttpClient client, String method, int port, String path, byte[] body, String contentType) throws IOException, InterruptedException {
        return client.send(request(method, port, path, body, contentType), HttpResponse.BodyHandlers.ofString());
    }

    static HttpRequest request(String method, int port, String path, byte[] body, String contentType) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).header("content-type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    /** Every client here speaks HTTP/1.1: the JDK client would upgrade to h2c and share one connection between requests (FIX-29). */
    static HttpClient http() {
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** The partner both demo endpoints send to: records what it saw, answers the demo's ack once {@code release} completes. */
    io.vertx.core.http.HttpServer partner(List<String> saw, Future<Void> release, Buffer ack) {
        return vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            saw.add(req.path() + " " + b);
            release.onComplete(v -> req.response().putHeader("content-type", "application/xml").end(ack));
        })).listen(0, "127.0.0.1").await();
    }

    Node startDemoNode(Map<String, String> env) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env);
        node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        return node;
    }

    @Test
    void deploysTheDemoZipAndAnInvoiceGetsThePartnersReply() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, Future.succeededFuture(), ack);
        try (HttpClient client = http()) {
            int inPort = freePort();
            Map<String, byte[]> files = demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort());
            byte[] zip = zip(files);
            startDemoNode(env("ci"));
            int admin = node.adminPort();

            HttpResponse<String> put = send(client, "PUT", admin, "/admin/packages/demo?revision=9df3f78", zip, "application/zip");

            assertEquals(200, put.statusCode(), put.body());
            assertEquals(new JsonObject().put("package", "demo").put("version", "0.1.0").put("revision", "9df3f78").put("archive", "sha256:" + sha256(zip))
                    .put("flows", new io.vertx.core.json.JsonArray().add("demo/invoice-sync")).put("added", new io.vertx.core.json.JsonArray().add("demo/invoice-sync"))
                    .put("swapped", new io.vertx.core.json.JsonArray()).put("removed", new io.vertx.core.json.JsonArray()), new JsonObject(put.body()), "the deploy's answer");

            HttpResponse<String> invoice = send(client, "POST", inPort, "/in", Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")), "application/xml");
            assertEquals(200, invoice.statusCode(), invoice.body());
            assertEquals(ack.toString(), invoice.body(), "the partner's reply comes back as the HTTP answer");
            assertEquals(1, partnerSaw.size(), "the partner saw the invoice once: " + partnerSaw);
            assertTrue(partnerSaw.get(0).startsWith("/echo ") && partnerSaw.get(0).contains("<PartnerInvoice number=\"4711\""), "the mapped invoice went to the domestic partner: " + partnerSaw);

            HttpResponse<String> flows = get(admin, "/admin/flows");
            assertEquals(200, flows.statusCode(), flows.body());
            JsonObject listed = new JsonObject(flows.body());
            JsonObject flowItem = listed.getJsonArray("items").getJsonObject(0);   // P1-09d2a (D-063): the effective hash and the state
            assertTrue(String.valueOf(flowItem.remove("hash")).matches("sha256:[0-9a-f]{64}"), "the flow's effective hash: " + flows.body());
            assertEquals("RUNNING", flowItem.remove("state"), "the flow's state: " + flows.body());
            assertEquals(new JsonObject().put("items", new io.vertx.core.json.JsonArray().add(new JsonObject().put("id", "demo/invoice-sync").put("package", "demo")
                    .put("packageVersion", "0.1.0").put("revision", "9df3f78").put("flowVersion", "0.1.0").put("delivery", "sync"))), listed);

            HttpResponse<String> packages = get(admin, "/admin/packages");
            assertEquals(200, packages.statusCode(), packages.body());
            JsonObject item = new JsonObject(packages.body()).getJsonArray("items").getJsonObject(0);
            Set<Object> expectedFiles = new java.util.HashSet<>();
            for (Map.Entry<String, byte[]> f : files.entrySet()) expectedFiles.add(new JsonObject().put("path", f.getKey()).put("sha256", sha256(f.getValue())));
            assertEquals(expectedFiles, item.getJsonArray("files").stream().collect(java.util.stream.Collectors.toSet()), "every file of the demo with its hash");

            List<String> found = new CopyOnWriteArrayList<>();
            until(() -> {
                try {
                    HttpResponse<String> search = get(admin, "/admin/messages?key=invoiceId:4711");
                    found.clear();
                    new JsonObject(search.body()).getJsonArray("items").forEach(id -> found.add((String) id));
                    if (found.isEmpty()) return false;
                    JsonObject timeline = new JsonObject(get(admin, "/admin/messages/" + found.get(0)).body());
                    return timeline.getJsonArray("steps").size() == 6;
                } catch (IOException | InterruptedException e) {
                    throw new AssertionError("the admin API did not answer", e);
                }
            }, "the invoice is found by its key and its six steps are recorded");
            JsonObject timeline = new JsonObject(get(admin, "/admin/messages/" + found.get(0)).body());
            assertEquals("demo/invoice-sync", timeline.getString("flow"), "the timeline names the qualified flow");
            for (Object s : timeline.getJsonArray("steps")) {
                assertEquals(new JsonObject().put("flow", "demo/invoice-sync@0.1.0").put("package", "demo@0.1.0").put("revision", "9df3f78"),
                        ((JsonObject) s).getJsonObject("versions"), "every snapshot carries flow, package and revision: " + s);
            }
        } finally {
            partner.close().await();
        }
    }

    // --- P1-09d2b (D-063): redeploy over the admin API

    static final String V1_ROOT = "<PartnerInvoice number=";
    static final String V2_ROOT = "<PartnerInvoiceV2 number=";

    /** The demo's files with its stylesheet's root element renamed: the only file v2 changes. */
    static Map<String, byte[]> withRenamedRoot(Map<String, byte[]> files) {
        Map<String, byte[]> v2 = new java.util.TreeMap<>(files);
        v2.put("flows/invoice-to-partner.xsl", replaced(files.get("flows/invoice-to-partner.xsl"), Map.of("<PartnerInvoice ", "<PartnerInvoiceV2 ", "</PartnerInvoice>", "</PartnerInvoiceV2>")));
        return v2;
    }

    /** The demo flow's listed {@code /admin/flows} item. */
    static JsonObject flowItem(int admin) throws Exception {
        HttpResponse<String> flows = get(admin, "/admin/flows");
        assertEquals(200, flows.statusCode(), flows.body());
        return new JsonObject(flows.body()).getJsonArray("items").getJsonObject(0);
    }

    /**
     * The demo runs; v2 changes only its stylesheet. The PUT answers 200 with the flow swapped, its listed hash moves, and the next
     * invoice — on the same in port, which v2's consumer took over — reaches the partner mapped by v2's stylesheet.
     */
    @Test
    void aChangedXsltRedeploySwapsAndTheNextInvoiceUsesIt() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, Future.succeededFuture(), ack);
        try (HttpClient client = http()) {
            int inPort = freePort();
            Map<String, byte[]> v1 = demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort());
            startDemoNode(env("ci"));
            int admin = node.adminPort();
            byte[] invoice = Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml"));
            assertEquals(200, send(client, "PUT", admin, "/admin/packages/demo?revision=r1", zip(v1), "application/zip").statusCode(), "fixture: v1 deploys");
            assertEquals(200, send(client, "POST", inPort, "/in", invoice, "application/xml").statusCode(), "fixture: v1 serves");
            assertTrue(partnerSaw.get(0).contains(V1_ROOT), "fixture: v1 maps to PartnerInvoice: " + partnerSaw);
            String v1Hash = flowItem(admin).getString("hash");
            byte[] v2Zip = zip(withRenamedRoot(v1));

            HttpResponse<String> put = send(client, "PUT", admin, "/admin/packages/demo?revision=r2", v2Zip, "application/zip");

            assertEquals(200, put.statusCode(), put.body());
            JsonObject body = new JsonObject(put.body());
            assertEquals(new io.vertx.core.json.JsonArray().add("demo/invoice-sync"), body.getJsonArray("swapped"), "the flow reading the stylesheet swaps: " + body);
            assertEquals(new io.vertx.core.json.JsonArray(), body.getJsonArray("added"), body.encode());
            assertEquals(new io.vertx.core.json.JsonArray(), body.getJsonArray("removed"), body.encode());
            assertEquals("sha256:" + sha256(v2Zip), body.getString("archive"));
            JsonObject listed = flowItem(admin);
            assertNotEquals(v1Hash, listed.getString("hash"), "the listed hash moved with the stylesheet: " + listed);
            assertEquals("r2", listed.getString("revision"), "the swapped flow carries v2's revision: " + listed);

            HttpResponse<String> next = send(client, "POST", inPort, "/in", invoice, "application/xml");

            assertEquals(200, next.statusCode(), next.body());
            assertEquals(ack.toString(), next.body(), "the partner's reply comes back");
            assertEquals(2, partnerSaw.size(), "the partner saw the second invoice once: " + partnerSaw);
            assertTrue(partnerSaw.get(1).contains(V2_ROOT) && !partnerSaw.get(1).contains(V1_ROOT),
                    "the next invoice is mapped by v2's stylesheet: " + partnerSaw.get(1));
        } finally {
            partner.close().await();
        }
    }

    /**
     * The demo runs; v2's stylesheet does not compile. The PUT is 422 naming the flow and the file; the flow and package are
     * listed as v1 (same hash, same archive) and the next invoice is still mapped by v1's stylesheet.
     */
    @Test
    void anInvalidRedeployLeavesV1Serving() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, Future.succeededFuture(), ack);
        try (HttpClient client = http()) {
            int inPort = freePort();
            Map<String, byte[]> v1 = demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort());
            byte[] v1Zip = zip(v1);
            startDemoNode(env("ci"));
            int admin = node.adminPort();
            byte[] invoice = Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml"));
            assertEquals(200, send(client, "PUT", admin, "/admin/packages/demo", v1Zip, "application/zip").statusCode(), "fixture: v1 deploys");
            JsonObject v1Flow = flowItem(admin);
            Map<String, byte[]> broken = withRenamedRoot(v1);
            broken.put("flows/invoice-to-partner.xsl", "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"3.0\"><xsl:template match=\"/\">"
                    .getBytes(StandardCharsets.UTF_8));   // not well-formed

            HttpResponse<String> put = send(client, "PUT", admin, "/admin/packages/demo", zip(broken), "application/zip");

            assertEquals(422, put.statusCode(), put.body());
            JsonObject body = new JsonObject(put.body());
            assertEquals(Set.of("error", "problems"), body.fieldNames(), body.encode());
            assertTrue(body.getJsonArray("problems").stream().map(String::valueOf).anyMatch(l -> l.contains("invoice-sync") && l.contains("invoice-to-partner.xsl")),
                    "a problem names the flow and its stylesheet: " + body);
            assertEquals(v1Flow, flowItem(admin), "the flow is listed as v1: same hash, revision and state");
            HttpResponse<String> packages = get(admin, "/admin/packages");
            assertEquals("sha256:" + sha256(v1Zip), new JsonObject(packages.body()).getJsonArray("items").getJsonObject(0).getString("archive"), "the package is v1's");

            HttpResponse<String> next = send(client, "POST", inPort, "/in", invoice, "application/xml");

            assertEquals(200, next.statusCode(), next.body());
            assertEquals(ack.toString(), next.body());
            assertEquals(1, partnerSaw.size(), partnerSaw.toString());
            assertTrue(partnerSaw.get(0).contains(V1_ROOT), "v1 keeps serving with its stylesheet: " + partnerSaw);
        } finally {
            partner.close().await();
        }
    }

    @Test
    void closeDrainsAHeldSyncRequestBeforeTheAdminPortRefuses() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.Promise<Void> release = io.vertx.core.Promise.promise();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, release.future(), ack);
        try (HttpClient client = http()) {
            int inPort = freePort();
            byte[] zip = zip(demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort()));
            Map<String, String> env = env("ci");
            env.put("FLOTTILA_STOP_DEADLINE", "PT2M");
            startDemoNode(env);
            int admin = node.adminPort();
            HttpResponse<String> put = send(client, "PUT", admin, "/admin/packages/demo", zip, "application/zip");
            assertEquals(200, put.statusCode(), put.body());
            java.util.concurrent.CompletableFuture<HttpResponse<String>> held = client.sendAsync(
                    request("POST", inPort, "/in", Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")), "application/xml"), HttpResponse.BodyHandlers.ofString());
            until(() -> partnerSaw.size() == 1, "the invoice is held at the partner");

            closing = OnVirtualThread.start(node::close);

            until(() -> {
                try (HttpClient probe = http()) {   // its own client, so its own connection: never the held request's
                    return send(probe, "POST", inPort, "/in", "<NotAnInvoice/>".getBytes(StandardCharsets.UTF_8), "application/xml").statusCode() == 503;
                } catch (java.net.http.HttpTimeoutException noAnswer) {
                    throw new AssertionError("a probe got no answer within its timeout: a stopping consumer answers 503 or refuses, it never hangs", noAnswer);
                } catch (IOException refusedOrReset) {
                    return true;   // the consumer's socket no longer takes the request: it is stopping as well
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }, "close stops the flow's consumer: a new request is 503 or refused");
            assertFalse(refuses(admin), "the admin port still listens while the held request drains");
            HttpResponse<String> health = get(admin, "/health");
            assertTrue(health.statusCode() == 200 || health.statusCode() == 503, "the admin API still answers: " + health.statusCode());
            assertFalse(closing.isDone(), "close waits for the held request (stop deadline PT2M)");
            assertFalse(held.isDone(), "the held request is not answered before the partner answers");

            release.complete();
            HttpResponse<String> drained = held.get(20, TimeUnit.SECONDS);
            assertEquals(200, drained.statusCode(), drained.body());
            assertEquals(ack.toString(), drained.body(), "the held request drained with the partner's reply");
            until(() -> refuses(admin), "after the drain the admin port is closed");
            OnVirtualThread.join(closing);
            assertEquals(1, partnerSaw.size(), "the probes never reached the partner: " + partnerSaw);
        } finally {
            release.tryComplete();
            partner.close().await();
        }
    }

    // --- P1-09h (D-058, D-061): durable at-least-once bodies; a node commits synchronously or does not start

    /**
     * The demo package with its flow switched to at-least-once (its 1 MiB body limit is FLOTTILA_BLOB_DURABLE_MAX's default):
     * the 202 promises the body, so another session sees its {@code bodies} row the moment the 202 arrives, with the posted
     * bytes; the worker then reads the body back from Postgres (the node keeps no local copy after persist) and sends it on.
     */
    @Test
    void anAcceptedAtLeastOnceMessageHasItsBodiesRow() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, Future.succeededFuture(), ack);
        try (HttpClient client = http()) {
            int inPort = freePort();
            Map<String, byte[]> files = demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort());
            files.put("flows/invoice-sync.yaml", replaced(files.get("flows/invoice-sync.yaml"), Map.of("mode: sync", "mode: at-least-once")));
            startDemoNode(env("ci"));
            HttpResponse<String> put = send(client, "PUT", node.adminPort(), "/admin/packages/demo", zip(files), "application/zip");
            assertEquals(200, put.statusCode(), put.body());

            byte[] invoice = Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml"));
            HttpResponse<String> accepted = send(client, "POST", inPort, "/in", invoice, "application/xml");
            assertEquals(202, accepted.statusCode(), accepted.body());
            String id = accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("the 202 names its message id: " + accepted.headers()));
            assertEquals(Optional.of(Buffer.buffer(invoice)), bodiesRow(id), "no wait: what the 202 promised is in Postgres, the ingress body's bytes under (id, 0, body)");

            until(() -> partnerSaw.size() == 1, "the worker sends the invoice on, its body read back from Postgres");
            assertTrue(partnerSaw.get(0).startsWith("/echo ") && partnerSaw.get(0).contains("<PartnerInvoice number=\"4711\""), "the mapped invoice went to the domestic partner: " + partnerSaw);
        } finally {
            partner.close().await();
        }
    }

    /**
     * D-061: a node refuses to start unless {@code synchronous_commit} is {@code on}, {@code remote_write} or {@code remote_apply}
     * for its sessions — an ACK promises a commit that survives a crash. Set on the case database: {@code off} stops {@code Main}
     * with one {@code flottila:} line and exit 1; {@code local} fails {@code Node.start}, which closes the pool it opened; the
     * same database with {@code remote_write} starts — the refusal is the setting's, nothing else's.
     */
    @Test
    void aNodeRefusesAsynchronousCommit() throws Exception {
        synchronousCommit("off");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = OnVirtualThread.call(() -> Main.run(env("ci"), err));
        List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(1, code, "a node that does not start exits with 1: " + lines);
        assertEquals(1, lines.size(), "one line on stderr, no stack trace: " + lines);
        assertTrue(lines.get(0).startsWith("flottila:"), lines.get(0));
        assertTrue(lines.get(0).contains("synchronous_commit") && lines.get(0).contains("off"), "the line names the setting and its value: " + lines.get(0));
        until(() -> connections() == 0, "the refused node left no connection to its database");
        assertEquals(List.of(), tables(), "the check runs before migrate: the refused node left its database untouched, no flyway_schema_history");

        synchronousCommit("local");
        NodeConfig config = NodeConfig.from(env("ci"));
        Set<String> before = Set.copyOf(vertx.deploymentIDs());
        Exception refused = null;
        try {
            node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new PlainComponent("sftp")), List.of(), List.of()));
        } catch (Exception expected) {
            refused = expected;
        }
        assertNotNull(refused, "local commits only wait for the local flush: refused too (the node started on port " + (node == null ? "?" : node.adminPort()) + ")");
        assertTrue(causes(refused).stream().anyMatch(c -> c.contains("synchronous_commit")), "names the setting: " + causes(refused));
        node = null;
        until(() -> connections() == 0, "the failed start closed the pool it opened: no connection to the case database is left");
        assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "no verticle stays deployed");
        assertEquals(List.of(), tables(), "refused before migrate: still no flyway_schema_history, no table at all");

        synchronousCommit("remote_write");
        start(env("ci"), ctx -> List.of(new PlainComponent("sftp")));
        assertEquals(200, get(node.adminPort(), "/health").statusCode(), "remote_write commits synchronously: the node starts on the same database");
        assertTrue(tables().contains("public.flyway_schema_history"), "witness for the refusals: a started node migrates, so the history table shows: " + tables());
    }

    /** D-061: {@code remote_apply} waits for the standby to apply the commit — accepted, as {@code remote_write} is. */
    @Test
    void aNodeStartsUnderRemoteApply() throws Exception {
        synchronousCommit("remote_apply");
        start(env("ci"), ctx -> List.of(new PlainComponent("sftp")));
        assertEquals(200, get(node.adminPort(), "/health").statusCode(), "remote_apply commits synchronously: the node starts");
        assertTrue(tables().contains("public.flyway_schema_history"), "and migrates its database: " + tables());
    }

    /** {@code synchronous_commit} for every new session of the case database. */
    void synchronousCommit(String value) throws SQLException {
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement alter = admin.createStatement()) {
            alter.execute("ALTER DATABASE \"" + database + "\" SET synchronous_commit = " + value);
        }
    }

    /** The bytes of the message's ingress body row, as a session of its own on the case database sees them, or empty. */
    Optional<Buffer> bodiesRow(String messageId) {
        PgConnection reader = PgConnection.connect(vertx, options(database)).await();
        try {
            for (io.vertx.sqlclient.Row r : reader.preparedQuery("select bytes from bodies where message_id = $1 and step = 0 and part = 'body'")
                    .execute(Tuple.of(messageId)).await()) {
                return Optional.of(r.getBuffer(0));
            }
            return Optional.empty();
        } finally {
            reader.close().await();
        }
    }

    static Buffer hashBytes(long hash) {
        return Buffer.buffer(ByteBuffer.allocate(Long.BYTES).putLong(hash).array());
    }

    static Snapshot snapshot(String id, long hash) {
        return new Snapshot(id, "node-it", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), Instant.parse("2026-10-26T00:00:00Z"), 0, 0,
                Headers.empty(), Props.empty(), Keys.empty(), new Hash("sha256-64", hash), 1, Optional.empty(), Map.of());
    }

    /** Backends of the case database waiting on a lock. */
    long lockWaiters() {
        return observer.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock'")
                .execute(Tuple.of(database)).await().iterator().next().getLong(0);
    }

    /** The tables of the case database outside the system schemas, as {@code schema.table}, read by a session of its own. */
    List<String> tables() {
        PgConnection reader = PgConnection.connect(vertx, options(database)).await();
        try {
            List<String> names = new java.util.ArrayList<>();
            for (io.vertx.sqlclient.Row r : reader.query("select schemaname || '.' || tablename from pg_tables "
                    + "where schemaname not in ('pg_catalog', 'information_schema') order by 1").execute().await()) {
                names.add(r.getString(0));
            }
            return names;
        } finally {
            reader.close().await();
        }
    }

    /** Backends connected to the case database. */
    long connections() {
        return observer.preparedQuery("select count(*) from pg_stat_activity where datname = $1").execute(Tuple.of(database)).await()
                .iterator().next().getLong(0);
    }

    static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Whether a connection to the loopback {@code port} is refused (nothing listens there). */
    static boolean refuses(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port), 5_000);
            return false;
        } catch (ConnectException refused) {
            return true;
        } catch (SocketException reset) {   // a connect the closing listener took and reset (observed): not refused yet, ask again
            return false;
        } catch (IOException other) {
            throw new UncheckedIOException(other);
        }
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after 20 s naming what never happened. */
    /** A {@link BindException}, or a message saying the address is in use, anywhere in the cause chain. */
    static boolean isBindError(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BindException) return true;
            if (t.getMessage() != null && t.getMessage().toLowerCase(java.util.Locale.ROOT).contains("address already in use")) return true;
        }
        return false;
    }

    /** The cause chain as text, for a failure message. */
    static List<String> causes(Throwable failure) {
        List<String> chain = new java.util.ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) chain.add(t.toString());
        return chain;
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
