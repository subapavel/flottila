package io.flottila.runtime;

import io.flottila.components.http.HttpComponent;
import io.flottila.core.flow.FlowWorker;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.TestJournalKey;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-172 P2, P3 (CLAUDE.md: every limit finite; D-082 backpressure): a node's two Postgres pools wait at most
 * {@code FLOTTILA_PG_POOL_WAIT} for a pooled connection and queue at most {@code FLOTTILA_PG_POOL_QUEUE} callers; a caller
 * refused — the wait elapsed or the queue full — is retryable backpressure: the intake answers 503 with nothing saved, an admin
 * read 503, and a refused claim leaves the worker running to claim once the pool frees.
 *
 * <p>Holding a pool (NodePoolRolesIT): the work pool's connections are held by parks waiting on a {@code dlq} locked in access
 * exclusive mode by the test's {@code test-locker} session (invoice-async, a body that is no XML: 202, parked at once). The
 * intake pool cannot be held by saves — at most max(1, intake − 1) groups are in flight (D-130), the rest of the pool is the
 * journal's — so its one connection ({@code FLOTTILA_PG_POOL_SIZE} 2: 1 + 1) is held by the journal: a body above the memory
 * tier is a spill whose persist inserts its {@code journal_segments} row on the intake pool, on the save's thread before its
 * group, and a {@code journal_segments} locked in access exclusive mode keeps that insert waiting on a Lock, its connection held.
 * Every hold is observed in {@code pg_stat_activity} (by {@code application_name}) before the probe and again after its answer.
 */
@Timeout(150)
class NodePoolWaitIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final AtomicInteger cases = new AtomicInteger();
    static final AtomicBoolean warmed = new AtomicBoolean();
    static final String NODE = "node-wait";
    static final String INTAKE = "flottila-intake@" + NODE;
    static final String WORK = "flottila-work@" + NODE;
    static final byte[] NO_XML = "this is no invoice".getBytes(StandardCharsets.UTF_8);
    static Vertx vertx;

    @TempDir Path data;
    String database;
    Pool observer;
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    final List<PgConnection> lockers = new CopyOnWriteArrayList<>();
    final Logger workerLog = Logger.getLogger(FlowWorker.class.getName());   // a strong reference: JUL holds loggers weakly
    final List<Handler> handlers = new CopyOnWriteArrayList<>();

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
    void createTheCaseDatabase() throws Exception {
        database = "wait_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        observer = PgBuilder.pool().connectingTo(options("test-observer")).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
        if (!warmed.getAndSet(true)) warmUp();
    }

    /**
     * A node started and closed once per JVM, under the default wait, in a database and a data directory of its own: the first
     * node a JVM starts took 140 to 380 ms for its first pooled statement (the wait covers the connect), at or above the PT0.2S the
     * cases start their nodes under; after this no case's start depends on running after another case (FIX-172 fixture).
     */
    void warmUp() throws Exception {
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE wait_warm");
        }
        NodeConfig config = NodeConfig.from(env("FLOTTILA_PG_DATABASE", "wait_warm", "FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve("warm")).toString()));
        Node warm = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(), List.of(), List.of()));
        OnVirtualThread.call(() -> { warm.close(); return null; });
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (Handler h : handlers) workerLog.removeHandler(h);
        for (PgConnection locker : lockers) locker.close().await();   // a held lock ends with its session
        for (Node n : nodes) OnVirtualThread.call(() -> { n.close(); return null; });
        for (HttpServer s : servers) s.close().await();
        observer.close().await();
    }

    PgConnectOptions options(String applicationName) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword()).addProperty("application_name", applicationName);
    }

    Map<String, String> env(String... pairs) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", NODE);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve(NODE)).toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");   // 2 intake + 2 work
        for (int i = 0; i < pairs.length; i += 2) env.put(pairs[i], pairs[i + 1]);
        TestJournalKey.with(env);
        return env;
    }

    /** A node over {@code env} with invoice-async deployed on {@code inPort}, its partners a server no unparsable body reaches. */
    Node startDeployed(HttpClient client, int inPort, Map<String, String> env) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env);
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())),
                Stream.concat(xml.steps().stream(), new XsdValidate().steps().stream()).toList(), xml.languages()));
        nodes.add(node);
        HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.response().setStatusCode(500).end("not expected")).listen(0, "127.0.0.1").await();
        servers.add(partner);
        InvoiceAsyncIT.deploy(client, node, NodeIT.zip(InvoiceAsyncIT.asyncFiles(inPort, "http://127.0.0.1:" + partner.actualPort())));
        return node;
    }

    long count(String sql, Object... params) {
        return observer.preparedQuery(sql).execute(Tuple.from(params)).await().iterator().next().getLong(0);
    }

    /** Sessions named {@code applicationName} waiting on a lock. */
    long waitingOnALock(String applicationName) {
        return count("select count(*) from pg_stat_activity where datname = $1 and application_name = $2 and wait_event_type = 'Lock'", database, applicationName);
    }

    /** A session that holds {@code table} in access exclusive mode until it rolls back or closes. */
    PgConnection lock(String table) {
        PgConnection locker = PgConnection.connect(vertx, options("test-locker")).await();
        lockers.add(locker);
        locker.query("begin").execute().await();
        locker.query("lock table " + table + " in access exclusive mode").execute().await();
        return locker;
    }

    static HttpRequest post(int port, byte[] body, String requestId) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/in")).timeout(Duration.ofSeconds(60)).header("content-type", "application/xml")
                .header("x-request-id", requestId).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    static HttpRequest get(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(90)).GET().build();
    }

    static String id(HttpResponse<String> accepted) {
        return accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("a 202 names its message: " + accepted.headers()));
    }

    /** Two no-XML messages accepted whose parks hold the work pool's two connections, waiting on the locked {@code dlq}. */
    void holdTheWorkPool(HttpClient client, int inPort) throws Exception {
        for (int i = 1; i <= 2; i++) {
            HttpResponse<String> accepted = client.send(post(inPort, NO_XML, "holder-" + i), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode(), "fixture: holder " + i + " saved and queued: " + accepted.body());
        }
        NodeIT.until(() -> waitingOnALock(WORK) == 2, "fixture: two parks hold the work pool's two connections, waiting on the locked dlq");
    }

    /** A message accepted and parked while the pools are free: a timeline the admin read finds (200). */
    String parkedWhileFree(HttpClient client, int inPort) throws Exception {
        HttpResponse<String> accepted = client.send(post(inPort, NO_XML, "free"), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode(), "fixture: saved while the pools are free: " + accepted.body());
        String id = id(accepted);
        NodeIT.until(() -> count("select count(*) from dlq where id = $1", id) == 1, "fixture: parked while the work pool is free");
        return id;
    }

    /**
     * A2 (P3 intake): the intake pool's one connection held by a spill's segment row waiting on the locked
     * {@code journal_segments}; three POSTs sent together under {@code FLOTTILA_PG_POOL_WAIT} PT0.2S each answer 503 well
     * before the flow's 5 s save timeout (a refused wait, not the timeout's Nack), while the connection is still held; none of
     * them is saved, then or after the release; after the release a POST is 202 again with its row committed. The group window
     * at its PT0.05S bound lets the three share a group (no member of a refused group is answered otherwise).
     */
    @Test
    void aRefusedWaitForTheIntakePoolAnswers503WellBeforeTheSaveTimeoutAndSavesNothing() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            startDeployed(client, inPort, env("FLOTTILA_PG_POOL_SIZE", "2", "FLOTTILA_PG_POOL_WAIT", "PT0.2S", "FLOTTILA_BLOB_MEMORY_TIER", "4096",
                    "FLOTTILA_INTAKE_GROUP_WINDOW", "PT0.05S"));
            HttpResponse<String> warm = client.send(post(inPort, NO_XML, "warm"), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, warm.statusCode(), "fixture: a save while the intake pool is free: " + warm.body());

            PgConnection locker = lock("journal_segments");
            byte[] spill = new byte[8192];
            Arrays.fill(spill, (byte) 'x');
            CompletableFuture<HttpResponse<String>> holder = client.sendAsync(post(inPort, spill, "holder"), HttpResponse.BodyHandlers.ofString());
            NodeIT.until(() -> waitingOnALock(INTAKE) == 1, "fixture: the spill's segment row holds the intake pool's one connection, waiting on the lock");

            List<CompletableFuture<long[]>> refused = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                long sent = System.nanoTime();
                refused.add(client.sendAsync(post(inPort, NO_XML, "refused-" + i), HttpResponse.BodyHandlers.ofString())
                        .thenApply(r -> new long[] {r.statusCode(), (System.nanoTime() - sent) / 1_000_000}));
            }
            for (int i = 0; i < refused.size(); i++) {
                long[] answer = refused.get(i).get(30, TimeUnit.SECONDS);
                assertEquals(503, answer[0], "refused-" + (i + 1) + ": a refused wait for the intake pool is retryable backpressure, 503");
                assertTrue(answer[1] < 2_500, "refused-" + (i + 1) + " answered after " + answer[1]
                        + " ms: the wait for a pooled connection (PT0.2S) refuses it well before the 5 s save timeout");
            }
            assertEquals(1, waitingOnALock(INTAKE), "the refusals came while the intake connection was still held");
            assertEquals(0, count("select count(*) from messages where headers->>'x-request-id' like 'refused-%'"), "no refused POST is saved");

            locker.query("rollback").execute().await();
            holder.get(30, TimeUnit.SECONDS);   // its answer, whichever: it waited on the lock, not on the pool
            HttpResponse<String> probe = client.send(post(inPort, NO_XML, "probe"), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, probe.statusCode(), "after the release a save is accepted again: " + probe.body());
            assertEquals(1, count("select count(*) from messages where id = $1 and step = 0", id(probe)), "its ingress row is committed at the 202");
            assertEquals(1, count("select count(*) from messages where step = 0 and headers->>'x-request-id' = 'probe'"),
                    "witness: a saved POST's x-request-id is found by the query that finds no refused one");
            assertEquals(0, count("select count(*) from messages where headers->>'x-request-id' like 'refused-%'"), "and no refused POST was saved since");
        }
    }

    /**
     * A3 (P3 admin): the work pool held by two parks, {@code FLOTTILA_PG_POOL_WAIT} PT0.2S: an admin read (a message's timeline,
     * one statement on the work pool) answers 503 with the usual error body, never 500, while the parks still hold the pool;
     * the same read answers 200 before the hold and after the release.
     */
    @Test
    void anAdminReadRefusedByTheWorkPoolAnswers503() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            Node node = startDeployed(client, inPort, env("FLOTTILA_PG_POOL_WAIT", "PT0.2S"));
            String id = parkedWhileFree(client, inPort);
            String path = "/admin/messages/" + id;
            assertEquals(200, client.send(get(node.adminPort(), path), HttpResponse.BodyHandlers.ofString()).statusCode(), "fixture: the read while the pool is free");

            PgConnection locker = lock("dlq");
            holdTheWorkPool(client, inPort);
            HttpResponse<String> read = client.send(get(node.adminPort(), path), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, read.statusCode(), "a read refused a pooled connection is retryable, 503, never 500: " + read.body());
            String error = new JsonObject(read.body()).getString("error");
            assertTrue(error != null && !error.isBlank(), "the usual error body {error}: " + read.body());
            assertEquals(2, waitingOnALock(WORK), "the refusal came while the parks still held the work pool");

            locker.query("rollback").execute().await();
            NodeIT.until(() -> count("select count(*) from dlq") == 3, "the parks go through once the lock is gone");
            assertEquals(200, client.send(get(node.adminPort(), path), HttpResponse.BodyHandlers.ofString()).statusCode(), "the read once the pool is free again");
        }
    }

    /**
     * A4 (P3 worker claim): the work pool held by two parks, {@code FLOTTILA_PG_POOL_WAIT} PT0.2S. A work event from another
     * node ({@code w1 test-sender * *}) wakes the worker: its claim is refused within the wait — the WARNING "the poll on a wake
     * of every flow failed" comes well inside 10 s of the NOTIFY — and the wake leaves the backlog known, so a message accepted
     * now gets no hand-off: it waits in the queue for a claim. Once the lock goes the worker, still running, claims it (a parked
     * item's end claims while a backlog is known) and parks it: the queued item is done with, nobody restarted anything.
     */
    @Test
    void aClaimRefusedByTheWorkPoolLeavesTheWorkerRunningAndItClaimsTheQueuedItemAfterTheRelease() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            startDeployed(client, inPort, env("FLOTTILA_PG_POOL_WAIT", "PT0.2S"));
            AtomicBoolean woken = new AtomicBoolean();
            CountDownLatch refusedAfterTheWake = new CountDownLatch(1);
            List<String> refusals = new CopyOnWriteArrayList<>();
            Handler capture = new Handler() {
                @Override public void publish(LogRecord r) {
                    if (woken.get() && r.getLevel() == Level.WARNING && r.getMessage() != null && r.getMessage().contains("the poll on a wake of every flow failed")) {
                        refusals.add(r.getMessage());
                        refusedAfterTheWake.countDown();
                    }
                }
                @Override public void flush() { }
                @Override public void close() { }
            };
            capture.setLevel(Level.ALL);
            workerLog.addHandler(capture);
            handlers.add(capture);

            PgConnection locker = lock("dlq");
            holdTheWorkPool(client, inPort);
            woken.set(true);
            observer.query("notify flottila_work, 'w1 test-sender * *'").execute().await();
            assertTrue(refusedAfterTheWake.await(10, TimeUnit.SECONDS),
                    "the claim the wake issued is refused within FLOTTILA_PG_POOL_WAIT PT0.2S, logged well inside 10 s; none was");

            HttpResponse<String> queued = client.send(post(inPort, NO_XML, "queued"), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, queued.statusCode(), "the intake is not the work pool: saved and queued while the claims are refused: " + queued.body());
            String id = id(queued);
            assertEquals(2, waitingOnALock(WORK), "still held by the same two parks: " + refusals);
            // the observer reads work, never the locked dlq (its read would wait behind the locker too)
            assertEquals(1, count("select count(*) from work where id = $1", id), "its item waits in the queue while the work pool is held");

            locker.query("rollback").execute().await();
            NodeIT.until(() -> count("select count(*) from dlq where id = $1", id) == 1,
                    "the worker survived its refused claims and ran the queued item once the pool freed: " + refusals);
            NodeIT.until(() -> count("select count(*) from work where id = $1", id) == 0, "and its work item is done with");
        }
    }

    /**
     * A5 (P2 queue): {@code FLOTTILA_PG_POOL_QUEUE} 1 and a wait of PT1M — a refusal here can only be the queue's. The work pool
     * held by two parks; two admin reads sent together: one waits (the queue's one place), the other is refused at once, 503,
     * while the first still waits; after the release the waiting one answers 200.
     */
    @Test
    void aFullWaitQueueRefusesTheNextWaiterAtOnce() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            Node node = startDeployed(client, inPort, env("FLOTTILA_PG_POOL_WAIT", "PT1M", "FLOTTILA_PG_POOL_QUEUE", "1"));
            String path = "/admin/messages/" + parkedWhileFree(client, inPort);

            PgConnection locker = lock("dlq");
            holdTheWorkPool(client, inPort);
            CompletableFuture<HttpResponse<String>> a = client.sendAsync(get(node.adminPort(), path), HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> b = client.sendAsync(get(node.adminPort(), path), HttpResponse.BodyHandlers.ofString());
            try {
                CompletableFuture.anyOf(a, b).get(10, TimeUnit.SECONDS);
            } catch (TimeoutException none) {
                fail("with FLOTTILA_PG_POOL_QUEUE 1 the second of two reads waiting on a held pool is refused at once; neither answered within 10 s");
            }
            CompletableFuture<HttpResponse<String>> first = a.isDone() ? a : b;
            CompletableFuture<HttpResponse<String>> other = first == a ? b : a;
            HttpResponse<String> refusedRead = first.get();
            assertEquals(503, refusedRead.statusCode(), "the read past the queue's one place is refused, retryable: " + refusedRead.body());
            assertFalse(other.isDone(), "the other read still waits in the queue's one place (PT1M): " + (other.isDone() ? other.get().statusCode() : ""));
            assertEquals(2, waitingOnALock(WORK), "the refusal came while the parks still held the work pool");

            locker.query("rollback").execute().await();
            HttpResponse<String> waited = other.get(30, TimeUnit.SECONDS);
            assertEquals(200, waited.statusCode(), "the waiting read gets the connection the parks give back: " + waited.body());
        }
    }

    // ---- FIX-172b: the transaction path's refusal (PgTx, PoolRefusedException) and the routes past a query -------------------

    static final String BUSY = "the database pool is busy; retry";   // AdminServer's fixed refusal text (FIX-172)

    /** WARNING records of {@link AdminServer}'s logger from now on: what a route that answered 500 failed with. */
    List<LogRecord> adminWarnings() {
        Logger log = Logger.getLogger(AdminServer.class.getName());
        adminLog.add(log);   // a strong reference: JUL holds loggers weakly
        List<LogRecord> seen = new CopyOnWriteArrayList<>();
        Handler h = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel().intValue() >= Level.WARNING.intValue()) seen.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        h.setLevel(Level.ALL);
        log.addHandler(h);
        adminHandlers.add(h);
        return seen;
    }

    final List<Logger> adminLog = new CopyOnWriteArrayList<>();
    final List<Handler> adminHandlers = new CopyOnWriteArrayList<>();

    @AfterEach
    void removeTheAdminHandlers() {
        for (Logger l : adminLog) for (Handler h : adminHandlers) l.removeHandler(h);
    }

    static String thrown(List<LogRecord> warnings) {
        return warnings.stream().map(r -> r.getMessage() + " <- " + (r.getThrown() == null ? "no throwable"
                : r.getThrown().getClass().getName() + ": " + r.getThrown().getMessage())).toList().toString();
    }

    /** A work row of an undeployed flow, keyless, marked lost (what an accepted loss writes, D-126): one a discard deletes. */
    long lostRow(String id) {
        return observer.preparedQuery("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length, lost_at)"
                + " values ($1, 'demo/gone', 1, null, $2, 1, 0, 10, now()) returning seq").execute(Tuple.of(id, NODE)).await().iterator().next().getLong(0);
    }

    HttpResponse<String> post(HttpClient client, int port, String path) throws Exception {
        return NodeIT.send(client, "POST", port, path, new byte[0], "application/json");
    }

    static void assertBusy(HttpResponse<String> answer, String what) {
        assertEquals(503, answer.statusCode(), what + ": a request the pool refused is retryable, 503: " + answer.body());
        assertEquals(new JsonObject().put("error", BUSY), new JsonObject(answer.body()), what + ": the fixed refusal body");
    }

    /**
     * FIX-172b A1 (P1, P2): {@code POST /admin/work/{seq}/discard} takes its connection for a transaction (PgWorkQueue's discard):
     * under the work pool held by two parks and {@code FLOTTILA_PG_POOL_WAIT} PT0.2S its acquire is refused — 503 with the fixed
     * text, never 500 — while the parks still hold the pool, and the lost row is still there, unaudited. After the release the same
     * discard answers 200 {seq, auditId} and the row is gone. A 500 today names in its message what the route's log saw.
     */
    @Test
    void aDiscardRefusedByTheWorkPoolAtItsTransactionAnswers503AndKeepsTheRow() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            Node node = startDeployed(client, inPort, env("FLOTTILA_PG_POOL_WAIT", "PT0.2S"));
            long seq = lostRow("lost-1");
            List<LogRecord> warnings = adminWarnings();

            PgConnection locker = lock("dlq");
            holdTheWorkPool(client, inPort);
            HttpResponse<String> refused = post(client, node.adminPort(), "/admin/work/" + seq + "/discard");
            assertEquals(503, refused.statusCode(), "a discard whose transaction the pool refused is retryable, 503, never 500: " + refused.body()
                    + "; the admin log saw " + thrown(warnings));
            assertBusy(refused, "the refused discard");
            assertEquals(2, waitingOnALock(WORK), "the refusal came while the parks still held the work pool");
            assertEquals(1, count("select count(*) from work where seq = $1 and lost_at is not null", seq), "the refused discard deleted nothing");
            assertEquals(0, count("select count(*) from audit where action = 'work.discard'"), "and audited nothing");

            locker.query("rollback").execute().await();
            NodeIT.until(() -> count("select count(*) from dlq") == 2, "the parks go through once the lock is gone");
            HttpResponse<String> discarded = post(client, node.adminPort(), "/admin/work/" + seq + "/discard");
            assertEquals(200, discarded.statusCode(), "the same discard once the pool is free: " + discarded.body());
            JsonObject body = new JsonObject(discarded.body());
            assertEquals(seq, body.getLong("seq"), "it names the row: " + body);
            assertEquals(0, count("select count(*) from work where seq = $1", seq), "the row is gone");
            assertEquals(1, count("select count(*) from audit where action = 'work.discard' and subject = $1", "work/" + seq), "and audited once");
        }
    }

    /**
     * FIX-172b A2 (end to end): a replay under the work pool held by two parks, PT0.2S, answers 503 with the fixed text (the
     * refusal of its message's reads), never a 502 FAILED; once the pool is free the same replay runs (an {@code outcome}).
     * The refusal inside the run (P2 {@code ran()}) is pinned by {@code AdminServerReplayTest} (no staging of a refusal between
     * the route's read and the run's own reads exists end to end).
     */
    @Test
    void aReplayRefusedByTheWorkPoolAnswers503NotA502() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            Node node = startDeployed(client, inPort, env("FLOTTILA_PG_POOL_WAIT", "PT0.2S"));
            String id = parkedWhileFree(client, inPort);
            String path = "/admin/messages/" + id + "/replay?from=1";

            PgConnection locker = lock("dlq");
            holdTheWorkPool(client, inPort);
            assertBusy(post(client, node.adminPort(), path), "a replay the pool refused");
            assertEquals(2, waitingOnALock(WORK), "the refusal came while the parks still held the work pool");

            locker.query("rollback").execute().await();
            NodeIT.until(() -> count("select count(*) from dlq") == 3, "the parks go through once the lock is gone");
            HttpResponse<String> ran = post(client, node.adminPort(), path);
            assertTrue(ran.statusCode() != 503 && new JsonObject(ran.body()).containsKey("outcome"), "witness: the replay runs once the pool is free: "
                    + ran.statusCode() + " " + ran.body());
        }
    }

    /**
     * FIX-172b A3 (pins FIX-172 for it; FIX-138a moved it to the new-copy route): {@code POST /admin/dlq/{id}/replay} under the work pool held by two parks, PT0.2S, answers
     * 503 with the fixed text while the parks hold the pool, its row kept; after the release the retry runs (an {@code outcome}).
     */
    @Test
    void aDlqReplayRefusedByTheWorkPoolAnswers503() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            Node node = startDeployed(client, inPort, env("FLOTTILA_PG_POOL_WAIT", "PT0.2S"));
            String id = parkedWhileFree(client, inPort);

            PgConnection locker = lock("dlq");
            holdTheWorkPool(client, inPort);
            assertBusy(post(client, node.adminPort(), "/admin/dlq/" + id + "/replay"), "a DLQ replay the pool refused");
            assertEquals(2, waitingOnALock(WORK), "the refusal came while the parks still held the work pool");

            locker.query("rollback").execute().await();
            NodeIT.until(() -> count("select count(*) from dlq") == 3, "the parks go through once the lock is gone");
            assertEquals(1, count("select count(*) from dlq where id = $1", id), "the refused retry kept its row");
            HttpResponse<String> retried = post(client, node.adminPort(), "/admin/dlq/" + id + "/replay");
            assertTrue(retried.statusCode() != 503 && new JsonObject(retried.body()).containsKey("outcome"), "witness: the retry runs once the pool is free: "
                    + retried.statusCode() + " " + retried.body());
        }
    }

    /**
     * FIX-172b A3: {@code PUT /admin/packages/demo} (the deployed package again) under the work pool held by two parks, PT0.2S,
     * answers 503 with an {@code error} while the parks hold the pool — retryable, never 500; after the release the same deploy
     * answers 200.
     */
    @Test
    void aDeployRefusedByTheWorkPoolAnswers503() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            Node node = startDeployed(client, inPort, env("FLOTTILA_PG_POOL_WAIT", "PT0.2S"));
            byte[] zip = NodeIT.zip(InvoiceAsyncIT.asyncFiles(inPort, "http://127.0.0.1:" + servers.getFirst().actualPort()));

            PgConnection locker = lock("dlq");
            holdTheWorkPool(client, inPort);
            HttpResponse<String> refused = NodeIT.send(client, "PUT", node.adminPort(), "/admin/packages/demo", zip, "application/zip");
            assertEquals(503, refused.statusCode(), "a deploy the pool refused is retryable, 503, never 500: " + refused.body());
            String error = new JsonObject(refused.body()).getString("error");
            assertTrue(error != null && !error.isBlank(), "an error body {error, ..}: " + refused.body());
            assertEquals(2, waitingOnALock(WORK), "the refusal came while the parks still held the work pool");

            locker.query("rollback").execute().await();
            NodeIT.until(() -> count("select count(*) from dlq") == 2, "the parks go through once the lock is gone");
            HttpResponse<String> deployed = NodeIT.send(client, "PUT", node.adminPort(), "/admin/packages/demo", zip, "application/zip");
            assertEquals(200, deployed.statusCode(), "witness: the same deploy once the pool is free: " + deployed.body());
        }
    }

    /**
     * FIX-172b F1 (D-133): {@code DELETE /admin/packages/demo} under the work pool held by two parks, PT0.2S, answers 503 with an
     * {@code error} while the parks hold the pool — retryable, never 500 — and the package is still deployed: its record row stays
     * and the listing names it. After the release the same undeploy answers 200 naming the package, and the row is gone.
     */
    @Test
    void anUndeployRefusedByTheWorkPoolAnswers503AndKeepsThePackage() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            Node node = startDeployed(client, inPort, env("FLOTTILA_PG_POOL_WAIT", "PT0.2S"));
            assertEquals(1, count("select count(*) from deployments where package = 'demo'"), "fixture: demo is recorded");
            List<LogRecord> warnings = adminWarnings();

            PgConnection locker = lock("dlq");
            holdTheWorkPool(client, inPort);
            HttpResponse<String> refused = NodeIT.send(client, "DELETE", node.adminPort(), "/admin/packages/demo", new byte[0], "application/json");
            assertEquals(503, refused.statusCode(), "an undeploy the pool refused is retryable, 503, never 500: " + refused.body()
                    + "; the admin log saw " + thrown(warnings));
            String error = new JsonObject(refused.body()).getString("error");
            assertTrue(error != null && !error.isBlank(), "an error body {error, ..}: " + refused.body());
            assertEquals(2, waitingOnALock(WORK), "the refusal came while the parks still held the work pool");
            assertEquals(1, count("select count(*) from deployments where package = 'demo'"), "the refused undeploy deleted no record");

            locker.query("rollback").execute().await();
            NodeIT.until(() -> count("select count(*) from dlq") == 2 && count("select count(*) from work") == 0,
                    "the parks go through once the lock is gone, and the queue holds no item of the flow");
            HttpResponse<String> listed = NodeIT.send(client, "GET", node.adminPort(), "/admin/packages", new byte[0], "application/json");
            assertTrue(listed.statusCode() == 200 && new JsonObject(listed.body()).getJsonArray("items").stream()
                    .anyMatch(p -> "demo".equals(((JsonObject) p).getString("name"))), "demo is still deployed after the refusal: " + listed.body());
            HttpResponse<String> undeployed = NodeIT.send(client, "DELETE", node.adminPort(), "/admin/packages/demo", new byte[0], "application/json");
            assertEquals(200, undeployed.statusCode(), "witness: the same undeploy once the pool is free: " + undeployed.body());
            assertEquals("demo", new JsonObject(undeployed.body()).getString("name"), "it names the package: " + undeployed.body());
            assertEquals(0, count("select count(*) from deployments where package = 'demo'"), "the record is deleted");
        }
    }

    /**
     * FIX-172b A5 (the intake pool's queue bound): {@code FLOTTILA_PG_POOL_QUEUE} 1, a wait of PT1M — a refusal can only be the
     * queue's — and the intake pool's one connection held by a spill's segment row waiting on the locked
     * {@code journal_segments}. Two more spills sent together each need a segment row of their own on the intake pool: one waits
     * in the queue's one place, the other is refused at once, 503, well before the 5 s save timeout, while the first still waits.
     * After the release the waiting one is saved (202) and nothing of the refused one is.
     */
    @Test
    void aFullIntakeWaitQueueRefusesTheNextWaitingSaveAtOnce() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            startDeployed(client, inPort, env("FLOTTILA_PG_POOL_SIZE", "2", "FLOTTILA_PG_POOL_WAIT", "PT1M", "FLOTTILA_PG_POOL_QUEUE", "1",
                    "FLOTTILA_BLOB_MEMORY_TIER", "4096"));
            HttpResponse<String> warm = client.send(post(inPort, NO_XML, "warm"), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, warm.statusCode(), "fixture: a save while the intake pool is free: " + warm.body());

            PgConnection locker = lock("journal_segments");
            byte[] spill = new byte[8192];
            Arrays.fill(spill, (byte) 'x');
            CompletableFuture<HttpResponse<String>> holder = client.sendAsync(post(inPort, spill, "holder"), HttpResponse.BodyHandlers.ofString());
            NodeIT.until(() -> waitingOnALock(INTAKE) == 1, "fixture: the spill's segment row holds the intake pool's one connection, waiting on the lock");

            long sent = System.nanoTime();
            CompletableFuture<HttpResponse<String>> a = client.sendAsync(post(inPort, spill, "queued-a"), HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> b = client.sendAsync(post(inPort, spill, "queued-b"), HttpResponse.BodyHandlers.ofString());
            try {
                CompletableFuture.anyOf(a, b).get(10, TimeUnit.SECONDS);
            } catch (TimeoutException none) {
                fail("with FLOTTILA_PG_POOL_QUEUE 1 the second of two saves waiting on the held intake pool is refused at once; neither answered within 10 s");
            }
            long millis = (System.nanoTime() - sent) / 1_000_000;
            CompletableFuture<HttpResponse<String>> first = a.isDone() ? a : b;
            CompletableFuture<HttpResponse<String>> other = first == a ? b : a;
            HttpResponse<String> refused = first.get();
            assertEquals(503, refused.statusCode(), "the save past the intake queue's one place is refused, retryable: " + refused.body());
            assertTrue(millis < 2_500, "answered after " + millis + " ms: refused at once by the full queue, well before the 5 s save timeout");
            assertFalse(other.isDone(), "the other save still waits in the queue's one place: " + (other.isDone() ? other.get().statusCode() + " " + other.get().body() : ""));
            assertEquals(1, waitingOnALock(INTAKE), "the refusal came while the intake connection was still held");
            String refusedId = refused.request().headers().firstValue("x-request-id").orElseThrow();

            locker.query("rollback").execute().await();
            holder.get(30, TimeUnit.SECONDS);
            HttpResponse<String> waited = other.get(30, TimeUnit.SECONDS);
            assertEquals(202, waited.statusCode(), "the waiting save gets the connection the holder gives back: " + waited.body());
            assertEquals(1, count("select count(*) from messages where id = $1 and step = 0", id(waited)), "its ingress row is committed at the 202");
            assertEquals(0, count("select count(*) from messages where headers->>'x-request-id' = $1", refusedId), "nothing of the refused save");
        }
    }
}
