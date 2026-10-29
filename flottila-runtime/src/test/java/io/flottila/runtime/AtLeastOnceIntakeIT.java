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
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-08a (D-082): the node wires the Postgres intake — an at-least-once ingress whose enqueue fails leaves no ingress row and no
 * body row behind (body, record and item commit together or not at all), and the sender gets a retryable 503; once the
 * enqueue succeeds again the retry is accepted with its ingress row. A failed intake is not counted in the health's
 * {@code snapshotWriteFailures}. Over HTTP, on {@code invoice-async} laid over the demo package (as {@link InvoiceAsyncIT});
 * every case runs on a database of its own (FIX-5).
 */
@Timeout(120)
class AtLeastOnceIntakeIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path data;
    String database;
    Pool db;   // the case database, as another session sees it
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();

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
        database = "intake_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        db = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword())).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (Node n : nodes) OnVirtualThread.call(() -> { n.close(); return null; });
        for (HttpServer s : servers) s.close().await();
        db.close().await();
    }

    Map<String, String> env(String nodeId) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");   // ci is no deployed environment: a plain-http endpoint is allowed
        env.put("FLOTTILA_NODE_ID", nodeId);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve(nodeId)).toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        return env;
    }

    Node startNode(String nodeId) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env(nodeId));
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        nodes.add(node);
        return node;
    }

    /** A partner that answers the demo's ack at once; returns its port. */
    int partner() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(InvoiceAsyncIT.DEMO.resolve("fixtures/partner-ack.xml")));
        HttpServer server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b ->
                req.response().putHeader("content-type", "application/xml").end(ack))).listen(0, "127.0.0.1").await();
        servers.add(server);
        return server.actualPort();
    }

    long count(String sql, Object... params) {
        return db.preparedQuery(sql).execute(Tuple.from(params)).await().iterator().next().getLong(0);
    }

    @Test
    void aNodeIngressWhoseEnqueueFailsLeavesNoIngressRow() throws Exception {
        int partnerPort = partner();
        Node node = startNode("node-a");
        int inPort = NodeIT.freePort();
        try (HttpClient client = NodeIT.http()) {
            InvoiceAsyncIT.deploy(client, node, NodeIT.zip(InvoiceAsyncIT.asyncFiles(inPort, "http://127.0.0.1:" + partnerPort)));
            db.query("create function refuse_work() returns trigger language plpgsql as $$ begin raise exception 'work refused by the test'; end $$")
                    .execute().await();
            db.query("create trigger refuse_work before insert on work for each row execute function refuse_work()").execute().await();

            HttpResponse<String> refused = NodeIT.send(client, "POST", inPort, "/in", InvoiceAsyncIT.invoice("INV-1", "S1"), "application/xml");
            assertEquals(503, refused.statusCode(), "an enqueue that fails is a retryable Nack: " + refused.body());
            assertEquals(0, count("select count(*) from messages"), "no ingress row: the record rolled back with the item");
            assertEquals(0, count("select count(*) from bodies"), "no body row: the body rolled back with the item");
            assertEquals(0, count("select count(*) from work"), "no item");
            assertEquals(0L, NodeIT.health(node.adminPort(), 200).getLong("snapshotWriteFailures"), "a failed intake is no snapshot write failure");

            db.query("drop trigger refuse_work on work").execute().await();
            HttpResponse<String> accepted = NodeIT.send(client, "POST", inPort, "/in", InvoiceAsyncIT.invoice("INV-1", "S1"), "application/xml");
            assertEquals(202, accepted.statusCode(), "the retry is accepted once the enqueue succeeds: " + accepted.body());
            String id = accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow();
            assertEquals(1, count("select count(*) from messages where id = $1 and step = 0", id), "its ingress row is committed at the 202");
            assertEquals(1, count("select count(*) from bodies where message_id = $1 and step = 0", id), "so is its body");
        }
    }
}
