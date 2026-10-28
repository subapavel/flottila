package io.flottila.runtime;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-01 (D-075): ordered delivery of {@code invoice-async} ({@code partitionBy: invoiceId}) by two nodes over one Postgres work
 * queue, end to end through HTTP. Per invoice id the partner receives the invoices one at a time, in the order the nodes accepted
 * them, whichever node accepted or runs them; a key whose lease ran out on one node (that node still busy with it) moves to the
 * other node's worker, which reruns the item and then goes on with the key's later invoices in order.
 *
 * <p>The partner ({@link HoldingPartner}) holds every request until the case releases it and notes, on arrival, every request of
 * an invoice id while another of that id is held. Each invoice carries one line whose SKU names it, so the mapped request says
 * which invoice it is. Every case runs on a database of its own; the nodes share it and one Vert.x.
 */
class OrderedDeliveryIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path data;
    String database;
    Pool db;
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    final List<HoldingPartner> partners = new CopyOnWriteArrayList<>();

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
        database = "ordered_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        db = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword())).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterEach
    void closeEverything() throws Exception {
        partners.forEach(HoldingPartner::releaseAll);   // no held request outlives its case
        for (Node n : nodes) OnVirtualThread.call(() -> { n.close(); return null; });
        for (HttpServer s : servers) s.close().await();
        db.close().await();
    }

    // --- fixture

    /** A partner that holds every request until released; requests are named {@code <invoice id>/<sku>}. */
    static final class HoldingPartner {
        static final Pattern NUMBER = Pattern.compile("number=\"([^\"]+)\"");
        static final Pattern SKU = Pattern.compile("sku=\"([^\"]+)\"");

        record Held(String name, String key, Promise<Void> answer) {}

        final List<String> arrivals = new CopyOnWriteArrayList<>();
        final List<String> overlaps = new CopyOnWriteArrayList<>();
        private final List<Held> held = new ArrayList<>();   // guarded by this, in arrival order

        void arrived(String body, Promise<Void> answer) {
            String key = group(NUMBER, body);
            String name = key + "/" + group(SKU, body);
            synchronized (this) {
                for (Held h : held) if (h.key().equals(key)) overlaps.add(name + " arrived while " + h.name() + " was held");
                arrivals.add(name);
                held.add(new Held(name, key, answer));
            }
        }

        static String group(Pattern p, String body) {
            Matcher m = p.matcher(body);
            if (!m.find()) throw new AssertionError("fixture: the partner request carries " + p + ": " + body);
            return m.group(1);
        }

        synchronized List<String> held() {
            return held.stream().map(Held::name).toList();
        }

        synchronized List<String> heldKeys() {
            return held.stream().map(Held::key).toList();
        }

        /** Answers the most recent held request named {@code name}. */
        void releaseLatest(String name) {
            Held found = null;
            synchronized (this) {
                for (Held h : held) if (h.name().equals(name)) found = h;
                if (found == null) throw new AssertionError("no held request " + name + "; held " + held());
                held.remove(found);
            }
            found.answer().complete();
        }

        void releaseAll() {
            List<Held> all;
            synchronized (this) {
                all = new ArrayList<>(held);
                held.clear();
            }
            all.forEach(h -> h.answer().tryComplete());
        }
    }

    HoldingPartner partner(int[] port) throws IOException {
        Buffer ack = Buffer.buffer(Files.readAllBytes(InvoiceAsyncIT.DEMO.resolve("fixtures/partner-ack.xml")));
        HoldingPartner partner = new HoldingPartner();
        HttpServer server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            Promise<Void> answer = Promise.promise();
            answer.future().onComplete(v -> req.response().putHeader("content-type", "application/xml").end(ack));
            partner.arrived(b.toString(), answer);
        })).listen(0, "127.0.0.1").await();
        servers.add(server);
        partners.add(partner);
        port[0] = server.actualPort();
        return partner;
    }

    Map<String, String> env(String nodeId) throws IOException {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
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

    /**
     * Node {@code nodeId} with the demo's components and steps, invoice-async deployed on it, its in endpoint on a port of its own;
     * {@code maxInFlight} rewrites the flow's limit. Returns the node's in port.
     */
    int nodeWithTheFlow(HttpClient client, String nodeId, int partnerPort, int maxInFlight) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env(nodeId));
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        nodes.add(node);
        int inPort = NodeIT.freePort();
        Map<String, byte[]> files = InvoiceAsyncIT.asyncFiles(inPort, "http://127.0.0.1:" + partnerPort);
        files.put("flows/invoice-async.yaml", NodeIT.replaced(files.get("flows/invoice-async.yaml"), Map.of("maxInFlight: 16", "maxInFlight: " + maxInFlight)));
        InvoiceAsyncIT.deploy(client, node, NodeIT.zip(files));
        return inPort;
    }

    /** Posts invoice {@code key} with the line {@code sku} to the in port; returns the message id of the 202. */
    static String accept(HttpClient client, int inPort, String key, String sku) throws Exception {
        HttpResponse<String> r = NodeIT.send(client, "POST", inPort, "/in", InvoiceAsyncIT.invoice(key, sku), "application/xml");
        assertEquals(202, r.statusCode(), key + "/" + sku + " is accepted: " + r.body());
        return r.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow();
    }

    /** The work row of message {@code id} as {@code locked_by@attempts}, or {@code gone}. */
    String row(String id) {
        List<String> found = new ArrayList<>();
        for (Row r : db.preparedQuery("select locked_by, attempts from work where id = $1").execute(Tuple.of(id)).await()) {
            found.add(r.getString("locked_by") + "@" + r.getInteger("attempts"));
        }
        return found.isEmpty() ? "gone" : String.join(",", found);
    }

    List<String> rows() {
        List<String> all = new ArrayList<>();
        for (Row r : db.query("select id, partition_key, locked_by, attempts from work order by seq").execute().await()) all.add(r.toJson().encode());
        return all;
    }

    // --- cases

    /**
     * Twelve invoices, four each of K1, K2 and K3, accepted in turn by node X and node Y; both workers run. Each round waits until
     * every key not yet through has a request at the partner, then answers them all. Per key: one request at a time, in accept order,
     * each invoice once.
     */
    @Test
    void twoWorkersDeliverEachKeyInAcceptOrderThroughHttp() throws Exception {
        int[] partnerPort = new int[1];
        HoldingPartner partner = partner(partnerPort);
        try (HttpClient client = NodeIT.http()) {
            int inX = nodeWithTheFlow(client, "node-x", partnerPort[0], 16);
            int inY = nodeWithTheFlow(client, "node-y", partnerPort[0], 16);
            Map<String, List<String>> accepted = new LinkedHashMap<>();
            for (int i = 0; i < 12; i++) {
                String key = "K" + (i % 3 + 1);
                String sku = "S" + i;
                accept(client, i % 2 == 0 ? inX : inY, key, sku);
                accepted.computeIfAbsent(key, k -> new ArrayList<>()).add(key + "/" + sku);
            }

            while (partner.arrivals.size() < 12 || !partner.held().isEmpty()) {
                NodeIT.until(() -> accepted.keySet().stream().allMatch(k -> partner.heldKeys().contains(k)
                                || partner.arrivals.stream().filter(a -> a.startsWith(k + "/")).count() >= accepted.get(k).size()),
                        "every key not yet through has a request at the partner; arrivals " + partner.arrivals + ", held " + partner.held() + ", rows " + rows());
                partner.releaseAll();
            }

            assertEquals(List.of(), partner.overlaps, "never two requests of one invoice id at the partner at once; arrivals " + partner.arrivals);
            for (Map.Entry<String, List<String>> k : accepted.entrySet()) {
                assertEquals(k.getValue(), partner.arrivals.stream().filter(a -> a.startsWith(k.getKey() + "/")).toList(),
                        "key " + k.getKey() + " reached the partner in accept order, each invoice once; arrivals " + partner.arrivals);
            }
            NodeIT.until(() -> rows().isEmpty(), "every row completed; rows " + rows());
        }
    }

    /**
     * maxInFlight 1. Node X runs K/S1 and the partner holds it: X has no free slot. K/S2, K/S3 and J/S4 wait. Node Y starts: it
     * passes the key K (leased by X) and runs J/S4. X's lease on K/S1 runs out (set in the database: the fake clock does not reach
     * a node); Y claims K/S1 as its second attempt, reruns it, and once the partner answered goes on with K/S2 and K/S3, in order.
     */
    @Test
    void leaseThatRanOutMovesKeyToOtherWorkerInOrder() throws Exception {
        int[] partnerPort = new int[1];
        HoldingPartner partner = partner(partnerPort);
        try (HttpClient client = NodeIT.http()) {
            int inX = nodeWithTheFlow(client, "node-x", partnerPort[0], 1);
            String m1 = accept(client, inX, "K", "S1");
            String m2 = accept(client, inX, "K", "S2");
            String m3 = accept(client, inX, "K", "S3");
            String m4 = accept(client, inX, "J", "S4");
            NodeIT.until(() -> partner.arrivals.size() == 1, "X runs the oldest item; rows " + rows());
            assertEquals(List.of("K/S1"), partner.arrivals);
            assertEquals("node-x@1", row(m1), "K/S1 leased to X, first attempt; rows " + rows());

            nodeWithTheFlow(client, "node-y", partnerPort[0], 1);
            NodeIT.until(() -> partner.arrivals.size() >= 2, "Y runs an item; rows " + rows());
            assertEquals(List.of("K/S1", "J/S4"), partner.arrivals, "Y passes the key X holds and runs J; rows " + rows());
            assertEquals(List.of(), partner.overlaps);
            assertEquals("null@0", row(m2), "K/S2 waits for its key; rows " + rows());
            partner.releaseLatest("J/S4");
            NodeIT.until(() -> row(m4).equals("gone"), "J/S4 completed; rows " + rows());

            NodeIT.until(() -> {
                db.preparedQuery("update work set locked_until = now() - interval '1 second' where id = $1 and attempts = 1").execute(Tuple.of(m1)).await();
                return row(m1).equals("node-y@2");
            }, "X's lease ran out and Y claimed K/S1 as its second attempt; rows " + rows());
            NodeIT.until(() -> partner.arrivals.size() == 3, "Y reruns K/S1; rows " + rows());
            assertEquals(List.of("K/S1", "J/S4", "K/S1"), partner.arrivals);
            assertEquals("null@0", row(m2), "K/S2 still waits while the rerun runs; rows " + rows());

            partner.releaseLatest("K/S1");   // Y's rerun; X's first run stays held
            NodeIT.until(() -> partner.arrivals.size() == 4, "the key goes on after the rerun; rows " + rows());
            assertEquals("K/S2", partner.arrivals.get(3), "K/S2 next; arrivals " + partner.arrivals);
            assertEquals("node-y@1", row(m2), "on Y, the key's new holder; rows " + rows());
            assertEquals("gone", row(m1), "the rerun completed K/S1");
            partner.releaseLatest("K/S2");
            NodeIT.until(() -> partner.arrivals.size() == 5, "then K/S3; rows " + rows());
            assertEquals(List.of("K/S1", "J/S4", "K/S1", "K/S2", "K/S3"), partner.arrivals);
            assertEquals("node-y@1", row(m3), "K/S3 on Y as well; rows " + rows());
            partner.releaseLatest("K/S3");
            NodeIT.until(() -> rows().isEmpty(), "every row completed; rows " + rows());

            assertEquals(List.of("K/S1"), partner.held(), "X's first run of K/S1 is still at the partner");
        }
    }
}
