package io.flottila.runtime;

import io.flottila.api.BlobKey;
import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.journal.Journal;
import io.flottila.runtime.journal.JournalIndexEntry;
import io.flottila.runtime.journal.JournalKeys;
import io.flottila.runtime.journal.JournalRecord;
import io.flottila.runtime.journal.PgWire;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.TestJournalKey;
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
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        TestJournalKey.with(env);   // a node needs a journal master key (CORE-4c1, D-106): a required configuration
        return env;
    }

    Node startNode(String nodeId) throws Exception {
        return startNode(env(nodeId));
    }

    Node startNode(Map<String, String> env) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env);
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
            assertEquals(0, count("select count(*) from body_refs"), "no body ref: it rolled back with the item (CORE-4c2: a ref, no bodies row)");
            assertEquals(0, count("select count(*) from work"), "no item");
            assertEquals(0L, NodeIT.health(node.adminPort(), 200).getLong("snapshotWriteFailures"), "a failed intake is no snapshot write failure");

            db.query("drop trigger refuse_work on work").execute().await();
            HttpResponse<String> accepted = NodeIT.send(client, "POST", inPort, "/in", InvoiceAsyncIT.invoice("INV-1", "S1"), "application/xml");
            assertEquals(202, accepted.statusCode(), "the retry is accepted once the enqueue succeeds: " + accepted.body());
            String id = accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow();
            assertEquals(1, count("select count(*) from messages where id = $1 and step = 0", id), "its ingress row is committed at the 202");
            assertEquals(1, count("select count(*) from body_refs where message_id = $1 and step = 0 and node = 'node-a'", id), "so is its body's ref, on this node");
            assertEquals(0, count("select count(*) from bodies"), "and no bodies row: the body is a journal record (CORE-4c2)");
        }
    }

    // ------------------------------------------------------------------------------------------------- the node's sweeper (CORE-4d r1 (3))

    /** Bounded wait for {@code condition} (20 s): the node runs on the system clock and a real timer, which no case can drive. */
    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    static TreeSet<Long> segmentFiles(Path segments) throws IOException {
        TreeSet<Long> numbers = new TreeSet<>();
        try (Stream<Path> files = Files.list(segments)) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".seg")).forEach(n -> numbers.add(Long.parseLong(n.substring(0, n.length() - 4))));
        }
        return numbers;
    }

    /**
     * CORE-4d r1 (3), CORE-3 item 10: the node starts its segment sweeper, wired to its intake's open saves. With
     * FLOTTILA_JOURNAL_SWEEP_INTERVAL and FLOTTILA_JOURNAL_SWEEP_GRACE at PT1S, a sealed segment nothing pins goes — its row
     * and its file — while the sealed segment of an at-least-once save whose COMMIT was cut (its transaction in progress, its
     * outcome unknown to the node: an open save) stays: no committed row names it and its segment row is not locked, so only the
     * open-save set keeps it. The pass that removed the unpinned segment had the in-doubt one past its grace (sealed before it).
     * The node takes no injected timer: the interval and the grace run on its real timer and clock, waited by a bounded spin.
     */
    @Test
    void theNodesSweeperRemovesAnUnpinnedSegmentAndKeepsTheSegmentOfAnInDoubtSave() throws Exception {
        int partnerPort = partner();
        try (PgWire wire = new PgWire(pg.getHost(), pg.getMappedPort(5432)); HttpClient client = NodeIT.http()) {
            Map<String, String> env = env("node-a");
            env.put("FLOTTILA_PG_HOST", "127.0.0.1");
            env.put("FLOTTILA_PG_PORT", String.valueOf(wire.port()));
            env.put("FLOTTILA_JOURNAL_SEGMENT_RECORDS", "1");   // a record each: the next append seals the segment before it
            env.put("FLOTTILA_JOURNAL_SEGMENT_AGE", "PT1H");
            env.put("FLOTTILA_JOURNAL_SWEEP_INTERVAL", "PT1S");
            env.put("FLOTTILA_JOURNAL_SWEEP_GRACE", "PT1S");
            Node node = startNode(env);
            int inPort = NodeIT.freePort();
            InvoiceAsyncIT.deploy(client, node, NodeIT.zip(InvoiceAsyncIT.asyncFiles(inPort, "http://127.0.0.1:" + partnerPort)));
            Path segments = data.resolve("node-a").resolve("journal").resolve("segments");

            PgWire.Held commit = wire.holdNextAfter(m -> m.sql() != null && m.sql().contains("insert into work("), PgWire.query("COMMIT"));
            CompletableFuture<HttpResponse<String>> post = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    post.complete(NodeIT.send(client, "POST", inPort, "/in", InvoiceAsyncIT.invoice("INV-DOUBT", "S1"), "application/xml"));
                } catch (Throwable t) {
                    post.completeExceptionally(t);
                }
            });
            try {
                commit.awaitReached();
                commit.cut();   // the COMMIT never reaches the server, the node's connection is gone: in progress, outcome unknown
                assertNotEquals(202, post.get(30, TimeUnit.SECONDS).statusCode(), "fixture: a save whose COMMIT was cut is not accepted");

                int keyId = OnVirtualThread.call(() -> JournalKeys.load(db, new SecretKeySpec(Base64.getDecoder().decode(env.get("FLOTTILA_JOURNAL_KEY")), "AES"))
                        .keyOf("default"));
                Journal.Appended unpinned = node.journal().append(new JournalRecord("m-unpinned", 0, BlobKey.BODY, keyId, new byte[200]), Promise.<Void>promise().future());
                OnVirtualThread.call(() -> unpinned.forced().await());
                Journal.Appended roll = node.journal().append(new JournalRecord("m-roll", 0, BlobKey.BODY, keyId, new byte[200]), Promise.<Void>promise().future());
                OnVirtualThread.call(() -> roll.forced().await());
                long k = unpinned.ref().segment();

                long doubt = -1;
                for (long n : segmentFiles(segments)) {
                    Optional<List<JournalIndexEntry>> index = node.journal().index(n);
                    if (index.isPresent() && index.get().stream().anyMatch(e -> e.step() == 0 && !e.messageId().startsWith("m-"))) doubt = n;
                }
                assertTrue(doubt >= 0 && doubt < k, "fixture: the in-doubt save's ingress record is in a sealed segment before " + k + ": " + segmentFiles(segments));
                final long n = doubt;
                assertEquals(0, count("select count(*) from work where body_node = 'node-a' and body_segment = $1", n), "fixture: no committed work row names " + n);
                assertEquals(0, count("select count(*) from body_refs where node = 'node-a' and segment = $1", n), "fixture: no committed ref names " + n);
                assertEquals(1, count("select count(*) from (select 1 from journal_segments where node = 'node-a' and segment = $1 for update skip locked) x", n),
                        "fixture: segment " + n + "'s row is there and not locked: no lock keeps it from a pass");

                until(() -> count("select count(*) from journal_segments where node = 'node-a' and segment = $1", k) == 0 && !Files.exists(segments.resolve(k + ".seg")),
                        () -> "the node's sweeper removes the unpinned sealed segment " + k + " (its row and its file)");
                assertTrue(Files.exists(segments.resolve(n + ".seg")), "the in-doubt save's segment " + n + " keeps its file");
                assertEquals(1, count("select count(*) from journal_segments where node = 'node-a' and segment = $1", n), "and its row");
            } finally {
                commit.releaseQuietly();   // the server's session closes without the COMMIT
                nodes.remove(node);
                OnVirtualThread.call(() -> { node.close(); return null; });
            }
        }
    }
}
