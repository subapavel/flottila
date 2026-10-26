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

    /** Backends connected to the case database. */
    long connections() {
        return observer.preparedQuery("select count(*) from pg_stat_activity where datname = $1").execute(Tuple.of(database)).await()
                .iterator().next().getLong(0);
    }

    static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()) {
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
