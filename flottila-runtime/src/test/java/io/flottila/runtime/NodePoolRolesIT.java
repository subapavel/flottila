package io.flottila.runtime;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.TestJournalKey;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-17 (D-130 draft, F-27 I4): a node's Postgres sessions by role, observed in {@code pg_stat_activity}. A node of
 * {@code FLOTTILA_PG_POOL_SIZE} 4 splits it 2 + 2 (intake, work); every session it opens names its role and the node
 * ({@code flottila-<role>@<nodeId>}: intake, work, membership, listen), and a work pool held by two parks waiting on a lock
 * does not delay an at-least-once save: the ACK path touches the intake pool only.
 *
 * <p>The flow: {@code invoice-async} over the demo package ({@link InvoiceAsyncIT}); a body that is not XML is accepted (202,
 * unkeyed) and its xsd step's failure parks it at once ({@code onFailure: park}, no retry) — a park is one statement on the work
 * pool ({@code PgDlq}) that a session holding {@code dlq} in access exclusive mode keeps waiting on a Lock. Every case runs on a
 * database of its own; the test's own sessions name themselves {@code test-observer} and {@code test-locker}.
 */
@Timeout(120)
class NodePoolRolesIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final AtomicInteger cases = new AtomicInteger();
    static final String NODE = "node-roles";
    static final String INTAKE = "flottila-intake@" + NODE;
    static final String WORK = "flottila-work@" + NODE;
    static final String MEMBERSHIP = "flottila-membership@" + NODE;
    static final String LISTEN = "flottila-listen@" + NODE;
    static final byte[] NO_XML = "this is no invoice".getBytes(StandardCharsets.UTF_8);
    static Vertx vertx;

    @TempDir Path data;
    String database;
    Pool observer;   // the case database as another session sees it
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    final List<PgConnection> lockers = new CopyOnWriteArrayList<>();

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
        database = "roles_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        observer = PgBuilder.pool().connectingTo(options("test-observer")).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (PgConnection locker : lockers) locker.close().await();   // a held lock ends with its session: the parks go on
        for (Node n : nodes) OnVirtualThread.call(() -> { n.close(); return null; });
        for (HttpServer s : servers) s.close().await();
        observer.close().await();
    }

    PgConnectOptions options(String applicationName) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword()).addProperty("application_name", applicationName);
    }

    Map<String, String> env() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");   // ci is no deployed environment: a plain-http endpoint is allowed
        env.put("FLOTTILA_NODE_ID", NODE);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve(NODE)).toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");   // FLOTTILA_PG_POOL_INTAKE unset: 2 intake + 2 work
        TestJournalKey.with(env);
        return env;
    }

    /** A node with the http component and the Saxon and XSD steps, invoice-async deployed on {@code inPort}, its partners a server no unparsable body reaches. */
    Node startDeployed(HttpClient client, int inPort) throws Exception {
        SaxonXml xml = new SaxonXml();
        NodeConfig config = NodeConfig.from(env());
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

    /** The client sessions of the case database other than the test's own, by application_name. */
    Map<String, Long> sessions() {
        Map<String, Long> byName = new TreeMap<>();
        for (Row r : observer.preparedQuery("select application_name, count(*) from pg_stat_activity where datname = $1 and backend_type = 'client backend'"
                + " and application_name not in ('test-observer', 'test-locker') group by 1").execute(Tuple.of(database)).await()) {
            byName.put(r.getString(0), r.getLong(1));
        }
        return byName;
    }

    /** Sessions of the node's work pool waiting on a lock (a park behind the locker's {@code dlq}). */
    long workWaitingOnALock() {
        return count("select count(*) from pg_stat_activity where datname = $1 and application_name = $2 and wait_event_type = 'Lock'", database, WORK);
    }

    HttpResponse<String> postNoXml(HttpClient client, int inPort) throws Exception {
        return NodeIT.send(client, "POST", inPort, "/in", NO_XML, "application/xml");
    }

    /** P3: every session of a 2 + 2 node names its role and the node; each pool within its share, one membership and one LISTEN connection. */
    @Test
    void everySessionNamesItsRole() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            startDeployed(client, inPort);
            HttpResponse<String> accepted = postNoXml(client, inPort);
            assertEquals(202, accepted.statusCode(), "fixture: saved and queued: " + accepted.body());
            NodeIT.until(() -> count("select count(*) from dlq") == 1, "fixture: a worker ran it and parked it (the work pool was used)");

            Map<String, Long> sessions = sessions();
            assertTrue(Set.of(INTAKE, WORK, MEMBERSHIP, LISTEN).containsAll(sessions.keySet()),
                    "every session of the node names one of its four roles at " + NODE + ": " + sessions);
            long intake = sessions.getOrDefault(INTAKE, 0L);
            long work = sessions.getOrDefault(WORK, 0L);
            assertTrue(intake >= 1 && intake <= 2, "the intake pool (2 of 4) holds 1 or 2 sessions, the save used it: " + sessions);
            assertTrue(work >= 1 && work <= 2, "the work pool (the other 2) holds 1 or 2 sessions, the park used it: " + sessions);
            assertEquals(1L, sessions.getOrDefault(MEMBERSHIP, 0L), "the node lease's own connection: " + sessions);
            assertEquals(1L, sessions.getOrDefault(LISTEN, 0L), "the work events' LISTEN connection: " + sessions);
        }
    }

    /**
     * P3 (review r1): the names sit on the right connections — the session named {@code flottila-listen@<node>} is the one that
     * ran {@code LISTEN} (its only statement; {@code Node.start} returns after it was acknowledged), the one named
     * {@code flottila-membership@<node>} ran the lease's statements and never a LISTEN. Swapping the two names fails here.
     */
    @Test
    void theListenSessionIsTheOneThatListens() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            startDeployed(client, NodeIT.freePort());
            Map<String, List<String>> lastQuery = new TreeMap<>();
            for (Row r : observer.preparedQuery("select application_name, query from pg_stat_activity where datname = $1 and backend_type = 'client backend'"
                    + " and application_name in ($2, $3)").execute(Tuple.of(database, LISTEN, MEMBERSHIP)).await()) {
                lastQuery.computeIfAbsent(r.getString(0), k -> new ArrayList<>()).add(r.getString(1));
            }
            assertEquals(Set.of(LISTEN, MEMBERSHIP), lastQuery.keySet(), "one listen and one membership session: " + lastQuery);
            List<String> listen = lastQuery.get(LISTEN);
            List<String> membership = lastQuery.get(MEMBERSHIP);
            assertEquals(1, listen.size(), "one LISTEN connection: " + lastQuery);
            assertEquals(1, membership.size(), "one membership connection: " + lastQuery);
            assertTrue(listen.getFirst().strip().toUpperCase(Locale.ROOT).startsWith("LISTEN "),
                    "the session named " + LISTEN + " ran LISTEN: " + lastQuery);
            assertTrue(!membership.getFirst().isBlank() && !membership.getFirst().strip().toUpperCase(Locale.ROOT).startsWith("LISTEN"),
                    "the session named " + MEMBERSHIP + " ran the lease's statements, no LISTEN: " + lastQuery);
        }
    }

    /**
     * P1: the ACK path touches only the intake pool. Three parks under a locked {@code dlq}: two hold the work pool's two
     * connections waiting on the lock, the third waits for a connection of the pool. A probe POST is still accepted (202) with
     * its ingress row committed, while the same two parks still wait — no connection came free for it. Once the lock goes,
     * the four messages are parked; the closed node leaves no session.
     */
    @Test
    void aSaturatedWorkPoolDoesNotDelayASave() throws Exception {
        try (HttpClient client = NodeIT.http()) {
            int inPort = NodeIT.freePort();
            Node node = startDeployed(client, inPort);
            PgConnection locker = PgConnection.connect(vertx, options("test-locker")).await();
            lockers.add(locker);
            locker.query("begin").execute().await();
            locker.query("lock table dlq in access exclusive mode").execute().await();

            for (int i = 1; i <= 3; i++) {
                HttpResponse<String> accepted = postNoXml(client, inPort);
                assertEquals(202, accepted.statusCode(), "message " + i + " saved and queued, whatever the parks before it hold: " + accepted.body());
            }
            NodeIT.until(() -> workWaitingOnALock() == 2, "two parks hold the work pool's two connections, waiting on the locked dlq: " + sessions());
            assertEquals(2L, sessions().getOrDefault(WORK, 0L), "the work pool is saturated at its share of 2: " + sessions());

            HttpResponse<String> probe = postNoXml(client, inPort);
            assertEquals(202, probe.statusCode(), "a save is accepted while the work pool is saturated: " + probe.body());
            String id = probe.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("a 202 names its message: " + probe.headers()));
            assertEquals(1, count("select count(*) from messages where id = $1 and step = 0", id), "its ingress row is committed at the 202");
            assertEquals(2, workWaitingOnALock(), "the same two parks still wait: the save took no connection a park gave up: " + sessions());
            assertEquals(2L, sessions().getOrDefault(WORK, 0L), "and the work pool did not grow for it: " + sessions());

            locker.query("rollback").execute().await();
            NodeIT.until(() -> count("select count(*) from dlq") == 4, "the three parks and the probe's go through once the lock is gone");

            nodes.remove(node);
            OnVirtualThread.call(() -> { node.close(); return null; });
            NodeIT.until(() -> sessions().isEmpty(), "the closed node closed both pools and its own connections: " + sessions());
        }
    }
}
