package io.flottila.runtime;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
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
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

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

import static io.flottila.runtime.NodeIT.DEMO;
import static io.flottila.runtime.NodeIT.demoFiles;
import static io.flottila.runtime.NodeIT.freePort;
import static io.flottila.runtime.NodeIT.get;
import static io.flottila.runtime.NodeIT.http;
import static io.flottila.runtime.NodeIT.refuses;
import static io.flottila.runtime.NodeIT.replaced;
import static io.flottila.runtime.NodeIT.send;
import static io.flottila.runtime.NodeIT.sha256;
import static io.flottila.runtime.NodeIT.until;
import static io.flottila.runtime.NodeIT.zip;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c3b (D-070), the row's done-when: a node restores at start what the pool recorded — the {@code deployments} rows in its
 * Postgres, the archives in S3 (LocalStack 4, as {@code NodeIT}'s S3 case) — with no PUT and whatever its data directory holds,
 * here nothing. A restarted node with an empty data directory runs the demo again from its row and its object and resumes the
 * at-least-once work node 1 left queued; the item node 1 left leased runs once its lease (D-055) ran out — the lease is set
 * by SQL on the case database, a fake deadline, so no case waits on a clock. An object deleted from S3 makes the package FAILED
 * on restore, /health DOWN, and the node starts all the same; a PUT recovers it.
 *
 * <p>Every case runs on a database of its own (FIX-5) and an S3 prefix of its own in one bucket; its nodes are closed before
 * LocalStack stops.
 */
class RestoreIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final LocalStackContainer localstack = new LocalStackContainer("localstack/localstack:4").withServices("s3");
    static final String BUCKET = "flottila-restore-it";
    static final String REVISION = "9df3f78";
    static final Pattern NUMBER = Pattern.compile("number=\"(\\d+)\"");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;
    static S3Client s3;

    @TempDir Path tmp;
    String database;
    String url;
    String prefix;
    /** Every node a case started and has not closed: closed after the case, before LocalStack stops. */
    final List<Node> running = new ArrayList<>();
    final List<HttpServer> partners = new ArrayList<>();

    @BeforeAll
    static void start() {
        pg.start();
        localstack.start();
        vertx = Vertx.vertx();
        s3 = S3Client.builder().endpointOverride(localstack.getEndpoint()).region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stop() {
        s3.close();
        vertx.close().await();
        localstack.stop();
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
        // getJdbcUrl() is jdbc:postgresql://host:port/<db> followed by the container's query string (?loggerLevel=OFF)
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

    /** A ci node {@code nodeId} on the case database, data under {@code dataDir}, its archives in LocalStack under the case's prefix. */
    Map<String, String> env(String nodeId, Path dataDir) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");   // ci is no deployed environment: LocalStack's plain-http endpoint is allowed
        env.put("FLOTTILA_NODE_ID", nodeId);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", dataDir.toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        env.put("FLOTTILA_ARCHIVE_STORE", "s3");
        env.put("FLOTTILA_S3_BUCKET", BUCKET);
        env.put("FLOTTILA_S3_REGION", localstack.getRegion());
        env.put("FLOTTILA_S3_ENDPOINT", localstack.getEndpoint().toString());
        env.put("FLOTTILA_S3_PATH_STYLE", "true");
        env.put("FLOTTILA_S3_PREFIX", prefix);
        env.put("FLOTTILA_S3_ACCESS_KEY", localstack.getAccessKey());
        env.put("FLOTTILA_S3_SECRET_KEY", localstack.getSecretKey());
        return env;
    }

    /** A demo node (the http component, the XSLT and XSD steps), kept for closing. */
    Node start(Map<String, String> env) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env);
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        running.add(node);
        return node;
    }

    void close(Node node) throws Exception {
        running.remove(node);
        OnVirtualThread.call(() -> { node.close(); return null; });
    }

    String key(String sha256) {
        return prefix + "/archives/" + sha256 + ".zip";
    }

    /** The object under {@code key}, or null when there is none. */
    static byte[] object(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build()).asByteArray();
        } catch (NoSuchKeyException none) {
            return null;
        }
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
        return rows("select package, version, coalesce(revision, '-'), archive_sha256, deployed_at from deployments order by package", 5);
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
     * Node 1 deploys the demo as an at-least-once flow with {@code maxInFlight: 1} (one item runs at a time) over S3 and Postgres
     * and accepts invoices 4711, 4712 and 4713 (202 each). The partner holds node 1's request for 4711 and never answers it (its
     * producer's timeout is ten minutes): node 1 leaves 4711 leased and 4712, 4713 queued, and closes. Node 2, on the same database
     * and S3 prefix, with an empty data directory and no PUT, restores the demo from its row and its object and runs 4712 and 4713
     * while 4711 is still leased to node 1 (its lease extended to an hour by SQL); once that lease has run out (set into the past by
     * SQL) node 2 runs 4711 too. Every item completes, nothing is parked, node 1 never ran more than 4711, and the row is as the
     * PUT wrote it: the restore wrote none.
     */
    @Test
    void aRestartedNodeWithAnEmptyDataDirResumesQueuedAtLeastOnceWorkWithoutAPut() throws Exception {
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
        int inPort = freePort();
        Map<String, byte[]> files = demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort());
        files.put("flows/invoice-sync.yaml", replaced(files.get("flows/invoice-sync.yaml"), Map.of("mode: sync", "mode: at-least-once", "maxInFlight: 16", "maxInFlight: 1")));
        files.put("endpoints/partner-domestic.yaml", replaced(files.get("endpoints/partner-domestic.yaml"), Map.of("maxBodyBytes: 1048576", "maxBodyBytes: 1048576\n  timeoutMs: 600000")));
        byte[] zip = zip(files);
        String sha = sha256(zip);

        Node one = start(env("node-1", tmp.resolve("node-1")));
        List<String> ids = new ArrayList<>();
        try (HttpClient client = http()) {
            HttpResponse<String> put = send(client, "PUT", one.adminPort(), "/admin/packages/demo?revision=" + REVISION, zip, "application/zip");
            assertEquals(200, put.statusCode(), "fixture: node 1 deploys the demo: " + put.body());
            for (int id : List.of(4711, 4712, 4713)) {
                HttpResponse<String> accepted = send(client, "POST", inPort, "/in", invoice(id), "application/xml");
                assertEquals(202, accepted.statusCode(), "fixture: invoice " + id + " is accepted: " + accepted.body());
                ids.add(accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("the 202 names its message id: " + accepted.headers())));
                if (id == 4711) until(() -> held.equals(List.of("4711")), "node 1's worker runs 4711 and the partner holds it");
            }
        }
        List<String> rowsAfterPut = deploymentRows();
        assertEquals(1, rowsAfterPut.size(), "fixture: the PUT wrote the demo's row: " + rowsAfterPut);
        close(one);
        assertEquals(List.of(ids.get(0) + " node-1", ids.get(1) + " -", ids.get(2) + " -"), work(),
                "fixture: node 1 left 4711 leased and 4712, 4713 queued");
        sql("update work set locked_until = now() + interval '1 hour' where id = '" + ids.get(0) + "'");   // a deadline the case controls
        answering.set(true);

        Path empty = Files.createDirectories(tmp.resolve("node-2"));
        assertEquals(List.of(), children(empty), "fixture: node 2's data directory is empty");
        Node two = start(env("node-2", empty));

        until(() -> answered.containsAll(List.of("4712", "4713")), "node 2, restored without a PUT, runs the items node 1 left queued (partner saw " + answered + ")");
        assertFalse(answered.contains("4711"), "4711 waits for the lease node 1 holds: " + answered);
        assertTrue(work().contains(ids.get(0) + " node-1"), "4711 is still leased to node 1: " + work());
        sql("update work set locked_until = now() - interval '1 second' where id = '" + ids.get(0) + "'");   // the lease ran out
        until(() -> answered.contains("4711"), "node 2 runs 4711 once node 1's lease ran out (D-055)");
        until(() -> {
            try {
                return work().isEmpty();
            } catch (SQLException e) {
                throw new AssertionError("the work table could not be read", e);
            }
        }, "every item completed on node 2");
        assertEquals(0, dlqRows(), "nothing was parked");
        assertEquals(List.of("4711"), held, "node 1 ran 4711 only");

        assertEquals(rowsAfterPut, deploymentRows(), "the restore wrote no row: the PUT's is unchanged");
        assertTrue(Files.isDirectory(empty.resolve("packages").resolve("demo").resolve(sha)), "node 2 unpacked the archive it fetched: " + children(empty));
        assertFalse(Files.exists(empty.resolve("archives")), "node 2 keeps no archive store on disk: it fetched from S3");
        JsonObject demo = listed(two.adminPort(), "packages", "name").get("demo");
        assertTrue(demo != null && "RUNNING".equals(demo.getString("state")), "node 2 lists the demo RUNNING: " + demo);
        assertEquals(new JsonArray(), demo.getJsonArray("problems"), demo.encode());
        assertEquals(REVISION, demo.getString("revision"), "the row's revision: " + demo.encode());
        assertEquals("sha256:" + sha, demo.getString("archive"), demo.encode());
        assertEquals("RUNNING", listed(two.adminPort(), "flows", "id").get("demo/invoice-sync").getString("state"));
        assertEquals("UP", health(two.adminPort(), 200).getJsonObject("checks").getString("deployments"), "nothing FAILED");
    }

    /**
     * Node 1 deploys the demo over S3 and serves an invoice; then the demo's object is deleted from S3. Node 2 starts all the same
     * (a missing object is no reason not to start), lists the demo FAILED — version, revision and archive from its row, problems
     * naming the archive, no flows, no files — lists no flow, serves nothing on the demo's in port, and its /health is 503 with
     * the {@code deployments} check DOWN (the wire names no package). A PUT of the demo stores the object again, runs it, and
     * /health is 200.
     */
    @Test
    void aDeletedObjectRestoresAFailedPackageAndHealthIsDown() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b ->
                req.response().putHeader("content-type", "application/xml").end(ack))).listen(0, "127.0.0.1").await();
        partners.add(partner);
        int inPort = freePort();
        byte[] zip = zip(demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort()));
        String sha = sha256(zip);

        Node one = start(env("node-1", tmp.resolve("node-1")));
        try (HttpClient client = http()) {
            assertEquals(200, send(client, "PUT", one.adminPort(), "/admin/packages/demo?revision=" + REVISION, zip, "application/zip").statusCode(), "fixture: node 1 deploys");
            assertEquals(200, send(client, "POST", inPort, "/in", invoice(4711), "application/xml").statusCode(), "fixture: node 1 serves the in port");
        }
        assertEquals("UP", health(one.adminPort(), 200).getJsonObject("checks").getString("deployments"), "the deployments check, UP on node 1");
        List<String> row = deploymentRows();
        close(one);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key(sha)).build());
        assertTrue(object(key(sha)) == null, "fixture: the object is gone");

        Node two = start(env("node-2", tmp.resolve("node-2")));

        JsonObject demo = listed(two.adminPort(), "packages", "name").get("demo");
        assertTrue(demo != null && "FAILED".equals(demo.getString("state")), "node 2 lists the demo FAILED: " + demo);
        JsonArray problems = demo.getJsonArray("problems");
        assertTrue(problems.stream().map(String::valueOf).anyMatch(l -> l.contains(sha)), "a problem names the missing archive: " + demo.encode());
        assertEquals(new JsonArray(), demo.getJsonArray("flows"), demo.encode());
        assertEquals(new JsonArray(), demo.getJsonArray("files"), demo.encode());
        assertEquals("0.1.0", demo.getString("version"), demo.encode());
        assertEquals(REVISION, demo.getString("revision"), demo.encode());
        assertEquals("sha256:" + sha, demo.getString("archive"), demo.encode());
        assertEquals(Map.of(), listed(two.adminPort(), "flows", "id"), "no flow runs");
        assertTrue(refuses(inPort), "nothing listens on the demo's in port (node 1 served it)");
        JsonObject down = health(two.adminPort(), 503);
        assertEquals("DOWN", down.getString("status"), down.encode());
        assertEquals("DOWN", down.getJsonObject("checks").getString("deployments"), down.encode());
        assertEquals("UP", down.getJsonObject("checks").getString("postgres"), "the other checks are UP: " + down.encode());
        assertFalse(down.encode().contains("demo"), "the wire names no package: " + down.encode());
        assertEquals(row, deploymentRows(), "the row stays as it was");

        try (HttpClient client = http()) {
            HttpResponse<String> put = send(client, "PUT", two.adminPort(), "/admin/packages/demo?revision=" + REVISION, zip, "application/zip");
            assertEquals(200, put.statusCode(), "a PUT recovers the package: " + put.body());
            assertEquals(200, send(client, "POST", inPort, "/in", invoice(4712), "application/xml").statusCode(), "the demo serves on node 2");
        }
        assertArrayEquals(zip, object(key(sha)), "the PUT stored the object again");
        assertEquals("UP", health(two.adminPort(), 200).getJsonObject("checks").getString("deployments"), "UP once the demo runs");
        assertEquals("RUNNING", listed(two.adminPort(), "packages", "name").get("demo").getString("state"));
    }
}
