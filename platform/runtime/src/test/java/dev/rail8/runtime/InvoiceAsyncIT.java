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
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-01 (D-075): {@code invoice-async} — the demo's invoice flow, at-least-once, {@code partitionBy: invoiceId} — on a node
 * against a real Postgres. A POST is answered 202 with the message id once the invoice is saved and queued; its work row
 * carries the invoice id as its partition key; the node's worker takes it from the queue and sends the mapped invoice to the
 * partner; once the partner answered the row is gone and the message's steps are recorded under its key. An invoice id over
 * 256 characters is a 400 and nothing is queued; a body that is no XML is accepted (202) without a key and parked by the pipeline.
 *
 * <p>The package: {@code examples/demo} with {@code flows/invoice-sync.yaml} (and its test file) replaced by the fixture
 * {@code src/test/resources/invoice-async/flows/invoice-async.yaml}; its in endpoint on a free loopback port, its partners a
 * local HTTP server that holds its answer until the case lets it go. Every case runs on a database of its own (FIX-5).
 */
class InvoiceAsyncIT {

    static final Path DEMO = NodeIT.DEMO;
    static final Path FIXTURE = Path.of("src/test/resources/invoice-async");
    static final SharedPostgres pg = SharedPostgres.database();
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path data;
    String database;
    Pool db;                 // the case database, as another session sees it
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    final List<Promise<Void>> holds = new CopyOnWriteArrayList<>();

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
        database = "async_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        db = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword())).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterEach
    void closeEverything() throws Exception {
        holds.forEach(Promise::tryComplete);   // no partner answer stays held past its case
        for (Node n : nodes) OnVirtualThread.call(() -> { n.close(); return null; });
        for (HttpServer s : servers) s.close().await();
        db.close().await();
    }

    // --- fixture

    /** The environment of node {@code nodeId} on the case database, admin on any free port, data in a directory of its own. */
    Map<String, String> env(String nodeId) throws IOException {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "ci");   // ci is no deployed environment: a plain-http endpoint is allowed
        GitRepos.with(env);   // the test JVM's package repositories, which its deploys commit to
        env.put("RAIL8_NODE_ID", nodeId);
        env.put("RAIL8_ADMIN_PORT", "0");
        env.put("RAIL8_PEER_PORT", "0");   // any free port: test JVMs run side by side
        env.put("RAIL8_HTTP_PORT", "0");   // the node's HTTP port on any free port too
        env.put("RAIL8_DATA_DIR", Files.createDirectories(data.resolve(nodeId)).toString());
        env.put("RAIL8_PG_HOST", pg.getHost());
        env.put("RAIL8_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("RAIL8_PG_DATABASE", database);
        env.put("RAIL8_PG_USER", pg.getUsername());
        env.put("RAIL8_PG_PASSWORD", pg.getPassword());
        env.put("RAIL8_PG_POOL_SIZE", "4");
        TestJournalKey.with(env);   // a node needs a journal master key (CORE-4c1, D-106): a required configuration
        return env;
    }

    /** Node {@code nodeId} with the http component and the Saxon and XSD steps, as the demo needs them. */
    Node startNode(String nodeId) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env(nodeId));
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids(), ctx.timer(), ctx.http())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        nodes.add(node);
        return node;
    }

    /**
     * The demo package with invoice-async in place of invoice-sync, its in endpoint served at {@code /http/in} on the node's HTTP
     * port, its partners at {@code partnerBase}.
     */
    static Map<String, byte[]> asyncFiles(String partnerBase) throws IOException {
        Map<String, byte[]> files = NodeIT.demoFiles(partnerBase);
        assertNotNull(files.remove("flows/invoice-sync.yaml"), "fixture: the demo's sync flow is replaced");
        assertNotNull(files.remove("flows/invoice-sync.test.yaml"), "fixture: and its test file with it");
        files.put("flows/invoice-async.yaml", Files.readAllBytes(FIXTURE.resolve("flows/invoice-async.yaml")));
        return files;
    }

    /** An invoice {@code id} in CZK (the domestic partner) with one line of SKU {@code sku}. */
    static byte[] invoice(String id, String sku) {
        return ("<Invoice><Id>" + id + "</Id><Currency>CZK</Currency><Lines><Line><Sku>" + sku + "</Sku><Qty>1</Qty><Price>1</Price></Line></Lines></Invoice>")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Commits {@code files} to the repository {@code demo} and deploys that commit on the node's admin API; the deploy names the one flow. */
    static void deploy(HttpClient client, Node node, Map<String, byte[]> files) throws Exception {
        HttpResponse<String> deployed = NodeIT.deploy(client, node.adminPort(), "demo", files);
        assertEquals(200, deployed.statusCode(), "fixture: the package deploys: " + deployed.body());
        assertEquals(new JsonArray().add("demo/invoice-async"), new JsonObject(deployed.body()).getJsonArray("flows"), "fixture: " + deployed.body());
    }

    /** A partner that records each request's path and body and answers the demo's ack once {@code release} completes. */
    HttpServer partner(List<String> saw, Future<Void> release) throws IOException {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        HttpServer server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            saw.add(req.path() + " " + b);
            release.onComplete(v -> req.response().putHeader("content-type", "application/xml").end(ack));
        })).listen(0, "127.0.0.1").await();
        servers.add(server);
        return server;
    }

    /** The work rows of the case database: id, flow, partition_key, locked_by, attempts. */
    List<Row> workRows() {
        List<Row> rows = new ArrayList<>();
        db.query("select id, flow, partition_key, locked_by, attempts from work order by seq").execute().await().forEach(rows::add);
        return rows;
    }

    static List<String> describe(List<Row> rows) {
        return rows.stream().map(Row::toJson).map(JsonObject::encode).toList();
    }

    // --- cases

    @Test
    void invoiceAsyncAccepted202AndDeliveredThroughTheQueue() throws Exception {
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        Promise<Void> release = Promise.promise();
        holds.add(release);
        HttpServer partner = partner(partnerSaw, release.future());
        try (HttpClient client = NodeIT.http()) {
            Map<String, byte[]> files = asyncFiles("http://127.0.0.1:" + partner.actualPort());
            Node node = startNode("node-async");
            deploy(client, node, files);

            HttpResponse<String> accepted = NodeIT.send(client, "POST", node.httpPort(), "/http/in", Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")), "application/xml");

            assertEquals(202, accepted.statusCode(), "accepted once saved and queued: " + accepted.body());
            String id = accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("a 202 names its message: " + accepted.headers()));
            NodeIT.until(() -> partnerSaw.size() == 1, "the worker took the invoice from the queue and sent it on (the partner holds its answer)");
            List<Row> rows = workRows();
            assertEquals(1, rows.size(), "one work row while the partner holds its answer: " + describe(rows));
            Row row = rows.get(0);
            assertEquals(id, row.getString("id"), "the row is the accepted message's: " + describe(rows));
            assertEquals("demo/invoice-async", row.getString("flow"), "the flow: " + describe(rows));
            assertEquals("4711", row.getString("partition_key"), "the row is keyed by the invoice id (partitionBy: invoiceId): " + describe(rows));
            assertEquals("node-async", row.getString("locked_by"), "leased to the node's worker: " + describe(rows));

            release.complete();
            NodeIT.until(() -> workRows().isEmpty(), "the row is completed once the partner answered");
            assertEquals(1, partnerSaw.size(), "the partner saw the invoice once: " + partnerSaw);
            assertTrue(partnerSaw.get(0).startsWith("/echo ") && partnerSaw.get(0).contains("<PartnerInvoice number=\"4711\""),
                    "the mapped invoice went to the domestic partner: " + partnerSaw);
            int admin = node.adminPort();
            NodeIT.until(() -> {
                try {
                    JsonArray found = new JsonObject(NodeIT.get(admin, "/admin/messages?key=invoiceId:4711").body()).getJsonArray("items");
                    return found.size() == 1 && id.equals(found.getString(0))
                            && new JsonObject(NodeIT.get(admin, "/admin/messages/" + id).body()).getJsonArray("steps").size() == 6;
                } catch (IOException | InterruptedException e) {
                    throw new AssertionError("the admin API did not answer", e);
                }
            }, "the invoice is found by its key and its six steps (ingress and five) are recorded");
            assertEquals("demo/invoice-async", new JsonObject(NodeIT.get(admin, "/admin/messages/" + id).body()).getString("flow"));
        }
    }

    /** An invoice id of 257 characters is a partition key over the bound: a final refusal (400); nothing is queued or recorded. */
    @Test
    void aKeyOver256CharactersIs400AndNothingQueued() throws Exception {
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(partnerSaw, Future.succeededFuture());
        try (HttpClient client = NodeIT.http()) {
            Node node = startNode("node-async");
            deploy(client, node, asyncFiles("http://127.0.0.1:" + partner.actualPort()));
            String longId = "7".repeat(257);

            HttpResponse<String> refused = NodeIT.send(client, "POST", node.httpPort(), "/http/in", invoice(longId, "S-1"), "application/xml");

            assertEquals(400, refused.statusCode(), "a key over 256 characters is the sender's fault, final: " + refused.body());
            assertTrue(refused.body().contains("256"), "the answer names the bound: " + refused.body());
            assertEquals(List.of(), describe(workRows()), "nothing queued");
            assertEquals(0L, db.query("select count(*) from messages").execute().await().iterator().next().getLong(0), "nothing recorded");
            HttpResponse<String> fine = NodeIT.send(client, "POST", node.httpPort(), "/http/in", invoice("7".repeat(256), "S-2"), "application/xml");
            assertEquals(202, fine.statusCode(), "control: 256 characters is a key: " + fine.body());
            NodeIT.until(() -> partnerSaw.size() == 1, "the control invoice is delivered");
            assertTrue(partnerSaw.get(0).contains("number=\"" + "7".repeat(256) + "\""), partnerSaw.get(0));
        }
    }

    /** A body that is not XML: the key cannot be read (MALFORMED) — accepted (202) without a key, and the pipeline parks it. */
    @Test
    void aBodyThatIsNoXmlIsAccepted202UnkeyedAndParked() throws Exception {
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(partnerSaw, Future.succeededFuture());
        try (HttpClient client = NodeIT.http()) {
            Node node = startNode("node-async");
            deploy(client, node, asyncFiles("http://127.0.0.1:" + partner.actualPort()));

            HttpResponse<String> accepted = NodeIT.send(client, "POST", node.httpPort(), "/http/in", "this is no invoice".getBytes(StandardCharsets.UTF_8), "application/xml");

            assertEquals(202, accepted.statusCode(), "the save does not judge the body: " + accepted.body());
            String id = accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow();
            NodeIT.until(() -> {
                long parked = db.query("select count(*) from dlq").execute().await().iterator().next().getLong(0);
                return parked == 1 && workRows().isEmpty();
            }, "the worker ran it and the xsd step's failure parked it");
            assertEquals(id, db.query("select id from dlq").execute().await().iterator().next().getString(0), "the DLQ row is the message's");
            assertEquals(List.of(), partnerSaw, "nothing reached the partner");
        }
    }
}
