package io.flottila.runtime;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Component;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Hash;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Keys;
import io.flottila.api.Producer;
import io.flottila.api.Props;
import io.flottila.components.http.HttpComponent;
import io.flottila.components.mock.DisguisedMock;
import io.flottila.components.mock.nested.NestedMock;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.deploy.PackageDraft;
import io.flottila.runtime.node.ComponentContext;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.PlainComponent;
import io.flottila.runtime.node.TestJournalKey;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.PostgresImage;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Transaction;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
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
        TestJournalKey.with(env);   // a node needs a journal master key (CORE-4c1, D-106): a required configuration
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
        env.put("FLOTTILA_PG_DURABILITY", "local");   // FIX-136: a deployed node defaults to ha; the case's one Postgres has no standby
        env.put("FLOTTILA_BLOB_TREE_LIMIT", "65536");
        // P1-09c3a (D-070): a deployed node refuses the dir archive store and a plain-http endpoint; it builds its S3 client at
        // start and contacts S3 only to restore a recorded package (none here), so an https endpoint nothing listens on will do
        env.put("FLOTTILA_ARCHIVE_STORE", "s3");
        env.put("FLOTTILA_S3_BUCKET", "flottila-archives");
        env.put("FLOTTILA_S3_REGION", "eu-central-1");
        env.put("FLOTTILA_S3_ENDPOINT", "https://127.0.0.1:9");
        env.put("FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE");
        env.put("FLOTTILA_S3_SECRET_KEY", "secret-example");
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

    /**
     * P1-09c1 (D-067): the node's admin API shows and releases held work items over its own Postgres queue — a row held in the
     * case database is listed with its {@code held_at}, and a release over the API clears the hold and the lease in that database.
     */
    @Test
    void theAdminApiReleasesAHeldRowOfTheNodesQueue() throws Exception {
        start(env("dev"), ctx -> List.of());
        PgConnection db = PgConnection.connect(vertx, options(database)).await();
        try {
            long seq = db.query("insert into work(id, flow, step, partition_key, attempts, held_at, locked_by, locked_until)"
                    + " values ('m-1', 'demo/invoice', 2, 'K', 3, now(), 'w1', now() + interval '1 hour') returning seq").execute().await()
                    .iterator().next().getLong(0);
            JsonObject page = new JsonObject(get(node.adminPort(), "/admin/work/held").body());
            assertEquals(1, page.getJsonArray("items").size(), page.encode());
            JsonObject item = page.getJsonArray("items").getJsonObject(0);
            assertEquals(seq, item.getLong("seq"), item.encode());
            assertEquals("m-1", item.getString("messageId"));
            assertEquals(3, item.getInteger("attempts"));
            assertNull(page.getValue("next"));
            try (HttpClient client = http()) {
                HttpResponse<String> released = send(client, "POST", node.adminPort(), "/admin/work/" + seq + "/release", new byte[0], "application/json");
                assertEquals(200, released.statusCode(), released.body());
                assertEquals(new JsonObject().put("seq", seq), new JsonObject(released.body()));
            }
            io.vertx.sqlclient.Row row = db.preparedQuery("select held_at, locked_by, locked_until, attempts from work where seq = $1")
                    .execute(Tuple.of(seq)).await().iterator().next();
            assertNull(row.getValue("held_at"), "released in the node's database");
            assertNull(row.getValue("locked_until"), "the lease cleared");
            assertEquals(3, row.getInteger("attempts"), "attempts kept");
        } finally {
            db.close().await();
        }
    }

    /**
     * Expectation changed by CORE-4c2 (D-105): the spool tier and its per-start key are gone — a body the memory tier does not
     * hold is a large body in the journal — so a started node keeps no {@code <data>/spool}; its bodies live under the journal
     * directory ({@code <data>/journal} by default). (The case used to pin that a start clears the spool a previous run left.)
     */
    @Test
    void aStartedNodeKeepsNoSpool() throws Exception {
        Path besides = Files.writeString(data.resolve("not-the-spool.txt"), "outside the journal");
        start(env("ci"), ctx -> List.of(new PlainComponent("sftp")));
        assertEquals(200, get(node.adminPort(), "/health").statusCode(), "the node runs");
        assertFalse(Files.exists(data.resolve("spool")), "no spool directory");
        assertTrue(Files.isDirectory(data.resolve("journal").resolve("segments")), "the journal is where bodies go");
        assertTrue(Files.exists(besides), "the rest of the data directory stays");
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
            // an uncommitted messages row with the snapshot's (id, step): the snapshot's batch waits on it, inside close's flush (P2-07c)
            holder.preparedQuery("insert into messages(id, step, flow, body_hash) values ('m-flushed', 0, 'node-it', $1)").execute(Tuple.of(hashBytes(4711L))).await();
            recorded = started.messages().record(snapshot("m-flushed", 4711L));
            until(() -> lockWaiters() == 1, "the snapshot's batch waits on the held messages row");
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
     * P2-07 (D-088): the node runs its retention job — the first run at once, on the node's own clock, without holding the start —
     * and closes it before the pool: close waits for the batch in flight, which commits, then the pool closes. The case's messages
     * table is locked by another session while the node starts, so the first run's batch waits on it; a row older than the default
     * max age (P7D) is gone once close returned, a fresh row stays, and no store WARNING says a batch failed.
     */
    @Test
    void theNodeRunsRetentionAtStartAndClosesItBeforeThePool() throws Exception {
        Migrations.migrate(url, pg.getUsername(), pg.getPassword());   // the node's own migrate then finds nothing to do and touches no table
        Logger flottila = Logger.getLogger("io.flottila");
        Warnings warnings = new Warnings();
        flottila.addHandler(warnings);
        PgConnection holder = PgConnection.connect(vertx, options(database)).await();
        try {
            holder.preparedQuery("insert into messages(id, step, flow, body_hash, created_at) values ('m-aged', 0, 'shop/orders', $1, now() - interval '8 days'),"
                    + " ('m-fresh', 0, 'shop/orders', $1, now() - interval '6 days')").execute(Tuple.of(hashBytes(7L))).await();
            Transaction held = holder.begin().await();
            holder.query("lock table messages in access exclusive mode").execute().await();
            NodeConfig config = NodeConfig.from(env("ci"));
            OnVirtualThread.Call<Node> starting = OnVirtualThread.start(() -> Node.start(config, vertx, ctx -> List.of(), List.of(), List.of()));
            until(() -> lockWaiters() == 1, "the node's first retention run starts at once: its batch waits on the locked messages table");
            node = OnVirtualThread.join(starting);   // the start does not wait for the run
            int port = node.adminPort();
            assertEquals(200, get(port, "/health").statusCode(), "the node serves while its first retention batch waits");

            Node started = node;
            closing = OnVirtualThread.start(started::close);
            until(() -> refuses(port), "close stopped the admin server");
            until(() -> closing.isDone() || closing.thread().getState() == Thread.State.WAITING,
                    "close goes on to the retention job and waits there (or, wrongly, ends)");
            assertFalse(closing.isDone(), "close waits for the retention batch in flight");
            assertEquals(1, lockWaiters(), "the batch still waits on the table");
            assertTrue(connections() > 0, "the pool is open while close waits for the batch");
            held.rollback().await();
        } finally {
            holder.close().await();
        }
        try {
            OnVirtualThread.join(closing);
            until(() -> connections() == 0, "close closed the pool after the retention job");
            PgConnection reader = PgConnection.connect(vertx, options(database)).await();
            try {
                List<String> left = new java.util.ArrayList<>();
                for (io.vertx.sqlclient.Row r : reader.query("select id from messages order by id").execute().await()) left.add(r.getString(0));
                assertEquals(List.of("m-fresh"), left, "the batch in flight committed before the pool closed: the row older than P7D is gone");
            } finally {
                reader.close().await();
            }
            assertEquals(List.of(), warnings.records.stream().filter(r -> r.contains("io.flottila.runtime.store")).toList(),
                    "no retention batch failed under a closed pool");
        } finally {
            flottila.removeHandler(warnings);
        }
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

    // --- P2-02L (D-083): the node's poller lease store. FLOTTILA_VALKEY_URI set: the leases are held in that Valkey, the client is
    // closed at close; absent: they are held in memory and start logs one WARNING naming the missing Valkey.

    /** The lease of {@link #pollingShop()}'s flow in Valkey: the store's prefix, then {@code <package>/<flow>/<scheme>://<endpoint id>}. */
    static final String FEED_LEASE = "flottila:poller:shop/feed/poll://drop";

    /** The {@code poll} scheme: the {@link Doors} of the deploy tests, every endpoint of which polls, so the node leases its consumers. */
    static final class PollingDoors implements Component {
        final Doors inner = new Doors("poll");

        @Override public String scheme() { return inner.scheme(); }

        @Override public String configSchema() { return inner.configSchema(); }

        @Override public io.flottila.api.Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return inner.consumer(cfg, handler); }

        @Override public Producer producer(EndpointConfig cfg) { return inner.producer(cfg); }

        @Override public Future<Health> health() { return inner.health(); }

        @Override public boolean polls(EndpointConfig cfg) { return true; }

        boolean aConsumerRuns() { return inner.doors().stream().anyMatch(Doors.Door::started); }
    }

    /** {@code shop} 1.0.0: flow {@code feed} from the polling endpoint {@code drop} to {@code partner}, both on the {@code poll} scheme. */
    static byte[] pollingShop() {
        return PackageDraft.of("shop", "1.0.0")
                .endpoint("drop", "poll", "in", new JsonObject().put("path", "/drop"))
                .endpoint("partner", "poll", "out", new JsonObject().put("url", "poll://partner"))
                .flow("feed", "1.0.0", "sync", "drop", 1024, PackageDraft.toPartner()).zip();
    }

    /** Every record at WARNING or above under {@code io.flottila}, formatted; attached while a case runs. */
    static final class Warnings extends Handler {
        final List<String> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord r) {
            if (r.getLevel().intValue() >= Level.WARNING.intValue()) records.add(r.getLevel() + " " + r.getLoggerName() + ": " + new SimpleFormatter().formatMessage(r));
        }

        @Override public void flush() {}

        @Override public void close() {}

        /** The WARNING records whose text names Valkey (any case). */
        List<String> namingValkey() {
            return records.stream().filter(r -> r.startsWith(Level.WARNING + " ") && r.toLowerCase(java.util.Locale.ROOT).contains("valkey")).toList();
        }
    }

    static boolean exists(RedisAPI api, String key) {
        return api.exists(List.of(key)).await().toInteger() == 1;
    }

    /** {@code connected_clients} of {@code INFO clients}: every connection to the server, the asking one included. */
    static int connectedClients(RedisAPI api) {
        String info = api.info(List.of("clients")).await().toString();
        for (String line : info.split("\\r?\\n")) {
            if (line.startsWith("connected_clients:")) return Integer.parseInt(line.substring("connected_clients:".length()).trim());
        }
        throw new AssertionError("no connected_clients in INFO clients: " + info);
    }

    /**
     * FLOTTILA_VALKEY_URI set: a polling flow deployed on the node is leased in that Valkey — its key appears there, held by the
     * node ({@code <FLOTTILA_NODE_ID>#<n>}), and its consumer runs — and no WARNING names Valkey. Close releases the key (before
     * the client closes: the release still reaches Valkey) and closes the node's client: only the observer stays connected.
     */
    @Test
    void aNodeWithAValkeyUriHoldsItsPollerLeasesThereAndClosesTheClientAtClose() throws Exception {
        Logger flottila = Logger.getLogger("io.flottila");
        Warnings warnings = new Warnings();
        flottila.addHandler(warnings);
        try (GenericContainer<?> valkey = new GenericContainer<>(DockerImageName.parse("valkey/valkey:8")).withExposedPorts(6379)) {
            valkey.start();
            String uri = "redis://" + valkey.getHost() + ":" + valkey.getMappedPort(6379);
            Redis observer = Redis.createClient(vertx, uri);
            try {
                RedisAPI api = RedisAPI.api(observer);
                int alone = connectedClients(api);
                assertFalse(exists(api, FEED_LEASE), "fixture: a fresh Valkey holds no lease");
                Map<String, String> env = env("ci");
                env.put("FLOTTILA_VALKEY_URI", uri);
                PollingDoors poll = new PollingDoors();
                Node started = start(env, ctx -> List.of(poll));

                putOk(started.adminPort(), "shop", pollingShop());

                until(() -> exists(api, FEED_LEASE), "the polling flow's lease key " + FEED_LEASE + " appears in the node's Valkey");
                String owner = api.get(FEED_LEASE).await().toString();
                assertTrue(owner.startsWith("node-it#"), "the key is held by this node, <FLOTTILA_NODE_ID>#<n>: " + owner);
                until(poll::aConsumerRuns, "the flow's consumer runs under the lease held in Valkey");
                assertTrue(connectedClients(api) > alone, "the node is connected to the Valkey");
                assertEquals(List.of(), warnings.namingValkey(), "a node with a Valkey warns nothing about it: " + warnings.records);

                closeNode(started);
                node = null;

                assertFalse(exists(api, FEED_LEASE), "close released the lease in Valkey: the release ran before the client closed");
                until(() -> connectedClients(api) == alone, "close closed the node's Valkey client: only the observer's connection is left");
            } finally {
                if (node != null) {
                    closeNode(node);
                    node = null;
                }
                observer.close();
            }
        } finally {
            flottila.removeHandler(warnings);
        }
    }

    /**
     * FLOTTILA_VALKEY_URI absent: the node leases in memory — a polling flow's consumer still starts — and its start logs exactly
     * one WARNING naming the missing Valkey, before any deploy; holding a lease adds no other.
     */
    @Test
    void aNodeWithoutAValkeyUriLeasesInMemoryAndWarnsOnceAtStart() throws Exception {
        Logger flottila = Logger.getLogger("io.flottila");
        Warnings warnings = new Warnings();
        flottila.addHandler(warnings);
        try {
            Map<String, String> env = env("ci");
            assertFalse(env.containsKey("FLOTTILA_VALKEY_URI"), "fixture: no Valkey configured");
            PollingDoors poll = new PollingDoors();

            Node started = start(env, ctx -> List.of(poll));

            assertEquals(1, warnings.namingValkey().size(), "start logged exactly one WARNING naming the missing Valkey: " + warnings.records);
            putOk(started.adminPort(), "shop", pollingShop());
            until(poll::aConsumerRuns, "the polling flow's consumer starts under a lease held in memory");
            assertEquals(1, warnings.namingValkey().size(), "holding a lease in memory warns no more: " + warnings.records);
        } finally {
            flottila.removeHandler(warnings);
        }
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

    /**
     * The files P2-06 added to the demo: the async door, the SFTP inbox (its secrets are references only a stack node has) and the two
     * flows of them. The ITs that mean "the demo" mean its invoice-sync flow behind http-in, which is what {@link #demoFiles} keeps.
     */
    static boolean addedByP206(String path) {
        return path.equals("endpoints/http-in-async.yaml") || path.equals("endpoints/sftp-inbox.yaml")
                || path.startsWith("flows/invoice-async.") || path.startsWith("flows/sftp-invoice.") || path.startsWith("fixtures/inbox/");
    }

    /**
     * The demo package's invoice-sync part as a zip, its http-in endpoint on 127.0.0.1:{@code inPort}, its partners at
     * {@code partnerBase}; every rewrite asserted.
     */
    static Map<String, byte[]> demoFiles(int inPort, String partnerBase) throws IOException {
        Map<String, byte[]> files = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(DEMO)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String path = DEMO.relativize(p).toString().replace('\\', '/');
                if (!addedByP206(path)) files.put(path, Files.readAllBytes(p));
            }
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

    /**
     * FIX-108 (D-094): a node with {@code FLOTTILA_CPU_SAMPLING=PT0.004S} runs the CPU sampler: after five invoices through the demo,
     * {@code GET /admin/flows/demo%2Finvoice-sync/cpu} answers sampling on with its period and a row per step — validate, extractKey,
     * route, toPartner, partner — each with five executions (the aggregate trails by one to two read cycles, 5–10 s, F-22: polled, bounded). Its close
     * stops the sampler's stream. The same node started again without the key (it restores the demo) answers sampling off.
     */
    @Test
    void aSamplingNodeShowsEachStepsCpuAndWithoutSamplingAnswersOff() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        io.vertx.core.http.HttpServer partner = partner(new CopyOnWriteArrayList<>(), Future.succeededFuture(), ack);
        int messages = 5;
        String cpuPath = "/admin/flows/demo%2Finvoice-sync/cpu";
        try (HttpClient client = http()) {
            int inPort = freePort();
            byte[] zip = zip(demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort()));
            Map<String, String> sampling = env("ci");
            sampling.put("FLOTTILA_CPU_SAMPLING", "PT0.004S");
            Node first = startDemoNode(sampling);
            putOk(first.adminPort(), "demo", zip);
            byte[] invoice = Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml"));
            for (int i = 0; i < messages; i++) {
                HttpResponse<String> sent = send(client, "POST", inPort, "/in", invoice, "application/xml");
                assertEquals(200, sent.statusCode(), "fixture: invoice " + i + " went through: " + sent.body());
            }

            // the aggregate trails by one to two read cycles (5–10 s, F-22): asked again until every step shows every message, at most 30 s
            JsonObject cpu;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (true) {
                HttpResponse<String> r = get(first.adminPort(), cpuPath);
                assertEquals(200, r.statusCode(), r.body());
                cpu = new JsonObject(r.body());
                JsonArray items = cpu.getJsonArray("items");
                if (items != null && items.size() == 5 && items.stream().allMatch(i -> ((JsonObject) i).getLong("executions") == messages)) break;
                if (System.nanoTime() - deadline > 0) fail("not within 30 s: a row per step, each with " + messages + " executions; last answer " + cpu.encode());
                Thread.onSpinWait();
            }
            assertEquals("demo/invoice-sync", cpu.getString("flow"));
            assertEquals("on", cpu.getString("sampling"));
            assertEquals("PT0.004S", cpu.getString("period"));
            Instant.parse(cpu.getString("since"));
            Map<Integer, String> steps = new java.util.TreeMap<>();
            cpu.getJsonArray("items").forEach(i -> steps.put(((JsonObject) i).getInteger("step"), ((JsonObject) i).getString("name")));
            assertEquals(Map.of(1, "validate", 2, "extractKey", 3, "route", 4, "toPartner", 5, "partner"), steps, "every step of the flow: " + cpu);
            assertEquals(Set.of("samples", "attributed", "pending", "unattributed", "platform", "lost", "dropped"), cpu.getJsonObject("totals").fieldNames(), cpu.encode());

            closeNode(first);
            node = null;
            assertEquals(0, jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings().stream()
                    .filter(r -> r.getState() == jdk.jfr.RecordingState.RUNNING && "true".equals(r.getSettings().get("jdk.CPUTimeSample#enabled"))).count(),
                    "the closed node's sampler stream is gone");

            Node second = startDemoNode(env("ci"));
            HttpResponse<String> off = get(second.adminPort(), cpuPath);
            assertEquals(200, off.statusCode(), off.body());
            assertEquals(new JsonObject().put("flow", "demo/invoice-sync").put("sampling", "off").put("items", new JsonArray()), new JsonObject(off.body()),
                    "the same node without FLOTTILA_CPU_SAMPLING");
        } finally {
            partner.close().await();
        }
    }

    /** A phase boundary the case commits between its two nodes: JFR times of one recording are ordered. */
    @jdk.jfr.Name("io.flottila.test.NodeItPhase")
    @jdk.jfr.Label("NodeIT phase")
    @jdk.jfr.StackTrace(false)
    static final class Phase extends jdk.jfr.Event {
        @jdk.jfr.Label("Label") String label;
    }

    /**
     * FIX-108 owner decision 2026-11-03 (sampling off costs nothing): a node without {@code FLOTTILA_CPU_SAMPLING} marks no step — an
     * external recording that enables {@code io.flottila.Step} sees none of {@code demo/invoice-sync} over three invoices —, while the
     * same node started again with sampling on (it restores the demo), under the same recording, marks each of the five steps once per
     * invoice (the witness that the recording sees the event). A phase event the case commits between the two nodes splits the
     * recording. The CPU rows of a sampling node: {@code aSamplingNodeShowsEachStepsCpuAndWithoutSamplingAnswersOff}.
     */
    @Test
    void aNodeWithoutSamplingMarksNoStepAndASamplingNodeMarksEach() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        io.vertx.core.http.HttpServer partner = partner(new CopyOnWriteArrayList<>(), Future.succeededFuture(), ack);
        int messages = 3;
        Path file = data.resolve("external-steps.jfr");
        try (HttpClient client = http(); jdk.jfr.Recording external = new jdk.jfr.Recording()) {
            external.enable("io.flottila.Step");
            external.enable(Phase.class);
            external.start();
            int inPort = freePort();
            byte[] zip = zip(demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort()));
            byte[] invoice = Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml"));

            Node off = startDemoNode(env("ci"));
            putOk(off.adminPort(), "demo", zip);
            for (int i = 0; i < messages; i++) {
                HttpResponse<String> sent = send(client, "POST", inPort, "/in", invoice, "application/xml");
                assertEquals(200, sent.statusCode(), "fixture: invoice " + i + " went through the node without sampling: " + sent.body());
            }
            closeNode(off);
            node = null;
            Phase between = new Phase();
            between.label = "sampling on from here";
            between.commit();

            Map<String, String> sampling = env("ci");
            sampling.put("FLOTTILA_CPU_SAMPLING", "PT0.004S");
            startDemoNode(sampling);
            for (int i = 0; i < messages; i++) {
                HttpResponse<String> sent = send(client, "POST", inPort, "/in", invoice, "application/xml");
                assertEquals(200, sent.statusCode(), "fixture: invoice " + i + " went through the sampling node: " + sent.body());
            }
            external.stop();
            external.dump(file);
        } finally {
            partner.close().await();
        }
        List<jdk.jfr.consumer.RecordedEvent> all = jdk.jfr.consumer.RecordingFile.readAllEvents(file);
        List<jdk.jfr.consumer.RecordedEvent> phases = all.stream().filter(e -> e.getEventType().getName().equals("io.flottila.test.NodeItPhase")).toList();
        assertEquals(1, phases.size(), "fixture: the phase event was recorded");
        Instant boundary = phases.get(0).getStartTime();
        List<jdk.jfr.consumer.RecordedEvent> steps = all.stream()
                .filter(e -> e.getEventType().getName().equals("io.flottila.Step") && e.getString("flow").endsWith("invoice-sync")).toList();
        List<String> withoutSampling = steps.stream().filter(e -> e.getStartTime().isBefore(boundary))
                .map(e -> e.getString("flow") + " " + e.getInt("step") + " " + e.getString("name")).toList();
        Map<String, Long> withSampling = steps.stream().filter(e -> e.getStartTime().isAfter(boundary))
                .collect(java.util.stream.Collectors.groupingBy(e -> e.getString("name"), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        assertEquals(Map.of("validate", 3L, "extractKey", 3L, "route", 3L, "toPartner", 3L, "partner", 3L), withSampling,
                "witness: with sampling on the node marks each step once per invoice, and the external recording sees it");
        assertEquals(List.of(), withoutSampling, "a node without sampling marks no step: the external recording sees no io.flottila.Step of its invoices");
    }

    /**
     * FIX-108 review r1, blocking 2: a sampling node whose sampler recording is stopped from outside while the node runs (noticed at its next read)
     * answers {@code GET /admin/flows/demo%2Finvoice-sync/cpu} with {@code sampling:"failed"} and the last aggregate: the period, the
     * since, a row per step with the executions it had. Before the end the same route answered "on" (polled, bounded).
     */
    @Test
    void aSamplingNodeWhoseStreamEndsAnswersFailedWithTheLastAggregate() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        io.vertx.core.http.HttpServer partner = partner(new CopyOnWriteArrayList<>(), Future.succeededFuture(), ack);
        int messages = 2;
        String cpuPath = "/admin/flows/demo%2Finvoice-sync/cpu";
        try (HttpClient client = http()) {
            int inPort = freePort();
            Map<String, String> sampling = env("ci");
            sampling.put("FLOTTILA_CPU_SAMPLING", "PT0.004S");
            Node n = startDemoNode(sampling);
            putOk(n.adminPort(), "demo", zip(demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort())));
            byte[] invoice = Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml"));
            for (int i = 0; i < messages; i++) {
                HttpResponse<String> sent = send(client, "POST", inPort, "/in", invoice, "application/xml");
                assertEquals(200, sent.statusCode(), "fixture: invoice " + i + " went through: " + sent.body());
            }
            JsonObject on = cpuUntil(n.adminPort(), cpuPath, a -> "on".equals(a.getString("sampling")) && a.getJsonArray("items").size() == 5
                    && a.getJsonArray("items").stream().allMatch(i -> ((JsonObject) i).getLong("executions") == messages), "sampling on, every step with every message");

            List<jdk.jfr.Recording> streams = jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings().stream()
                    .filter(r -> r.getState() == jdk.jfr.RecordingState.RUNNING && "true".equals(r.getSettings().get("jdk.CPUTimeSample#enabled"))).toList();
            assertEquals(1, streams.size(), "fixture: the node's sampler stream is the one CPU-time recording");
            assertTrue(streams.get(0).stop(), "fixture: the stream's recording stops");

            JsonObject failed = cpuUntil(n.adminPort(), cpuPath, a -> "failed".equals(a.getString("sampling")), "sampling failed once the stream ended");
            assertEquals("demo/invoice-sync", failed.getString("flow"));
            assertEquals("PT0.004S", failed.getString("period"));
            assertEquals(on.getString("since"), failed.getString("since"), "the same sampler's since");
            Map<Integer, String> steps = new java.util.TreeMap<>();
            for (Object i : failed.getJsonArray("items")) {
                JsonObject item = (JsonObject) i;
                steps.put(item.getInteger("step"), item.getString("name"));
                assertEquals(messages, item.getLong("executions"), "the last aggregate: " + failed);
            }
            assertEquals(Map.of(1, "validate", 2, "extractKey", 3, "route", 4, "toPartner", 5, "partner"), steps, "every step of the flow: " + failed);
            assertEquals(Set.of("samples", "attributed", "pending", "unattributed", "platform", "lost", "dropped"), failed.getJsonObject("totals").fieldNames(),
                    failed.encode());
        } finally {
            partner.close().await();
        }
    }

    /** {@code GET path} on {@code admin} until its 200 answer satisfies {@code done}, at most 30 s (the aggregate trails by one to two read cycles, 5–10 s, F-22). */
    static JsonObject cpuUntil(int admin, String path, java.util.function.Predicate<JsonObject> done, String what) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            HttpResponse<String> r = get(admin, path);
            assertEquals(200, r.statusCode(), r.body());
            JsonObject answer = new JsonObject(r.body());
            if (done.test(answer)) return answer;
            if (System.nanoTime() - deadline > 0) fail("not within 30 s: " + what + "; last answer " + answer.encode());
            Thread.onSpinWait();
        }
    }

    /**
     * P1-09c3a (D-070): a node with FLOTTILA_ARCHIVE_STORE s3 over Postgres and LocalStack. A PUT of the demo stores the archive's
     * bytes as the object {@code <prefix>/archives/<sha256>.zip} in the configured bucket — the node's S3 archive store, not its
     * disk — and writes the package's {@code deployments} row in the node's Postgres, naming that SHA-256.
     */
    @Test
    void aPutOnAnS3NodeStoresTheArchiveBySha256AndRecordsTheRow() throws Exception {
        String bucket = "flottila-node-it";
        String prefix = "node-it-prefix";
        try (LocalStackContainer localstack = new LocalStackContainer("localstack/localstack:4").withServices("s3")) {
            localstack.start();
            try (S3Client s3 = S3Client.builder().endpointOverride(localstack.getEndpoint()).region(Region.of(localstack.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                    .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
                 HttpClient client = http()) {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                Map<String, String> env = env("ci");   // ci is no deployed environment: a plain-http endpoint is allowed
                env.put("FLOTTILA_ARCHIVE_STORE", "s3");
                env.put("FLOTTILA_S3_BUCKET", bucket);
                env.put("FLOTTILA_S3_REGION", localstack.getRegion());
                env.put("FLOTTILA_S3_ENDPOINT", localstack.getEndpoint().toString());
                env.put("FLOTTILA_S3_PATH_STYLE", "true");
                env.put("FLOTTILA_S3_PREFIX", prefix);
                env.put("FLOTTILA_S3_ACCESS_KEY", localstack.getAccessKey());
                env.put("FLOTTILA_S3_SECRET_KEY", localstack.getSecretKey());
                byte[] zip = zip(demoFiles(freePort(), "http://127.0.0.1:9"));
                String sha = sha256(zip);
                startDemoNode(env);

                HttpResponse<String> put = send(client, "PUT", node.adminPort(), "/admin/packages/demo?revision=9df3f78", zip, "application/zip");

                assertEquals(200, put.statusCode(), put.body());
                assertEquals("sha256:" + sha, new JsonObject(put.body()).getString("archive"), "the deploy's answer names the archive: " + put.body());
                String key = prefix + "/archives/" + sha + ".zip";
                byte[] stored;
                try {
                    stored = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
                } catch (NoSuchKeyException missing) {
                    throw new AssertionError("no object s3://" + bucket + "/" + key + " after the PUT: the node did not store the archive in its S3 archive store", missing);
                }
                assertTrue(java.util.Arrays.equals(zip, stored), "the object holds the PUT's bytes: " + stored.length + " bytes stored, " + zip.length + " put");
                try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
                     Statement q = c.createStatement();
                     java.sql.ResultSet rows = q.executeQuery("select package, version, revision, archive_sha256, deployed_at from deployments")) {
                    assertTrue(rows.next(), "the PUT wrote the package's row in the node's Postgres");
                    assertEquals("demo", rows.getString("package"));
                    assertEquals("0.1.0", rows.getString("version"));
                    assertEquals("9df3f78", rows.getString("revision"));
                    assertEquals(sha, rows.getString("archive_sha256"), "the row names the stored archive's SHA-256");
                    assertNotNull(rows.getTimestamp("deployed_at"), "the row carries its deploy time");
                    assertFalse(rows.next(), "one row per package");
                }
            } finally {
                if (node != null) {
                    Node started = node;
                    node = null;
                    OnVirtualThread.call(() -> { started.close(); return null; });   // before LocalStack stops
                }
            }
        }
    }

    // --- P1-09c3b (D-070): restore at node start, before the admin port opens
    //
    // The packet names these three cases NodeTest; NodeTest's node has no Postgres (a closed port) and the restore runs after the
    // migration, so they run here, against the case database, under the packet's method names. Each node of a case runs the
    // Doors component (scheme rec) of the deploy tests: a start held or failed by flow, a hook inside every stop. The ci node
    // stores archives in its dir store, <dataDir>/archives; a restart on the same data dir and database is the next node of
    // the pool.

    /** {@code name} 1.0.0 on the Doors component: a sync flow per id from its own in endpoint {@code <id>-in} to {@code partner}. */
    static PackageDraft doorsPackage(String name, String... flows) {
        PackageDraft p = PackageDraft.of(name, "1.0.0")
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : flows) {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 1024, PackageDraft.toPartner());
        }
        return p;
    }

    /** A node on the case database with {@code doors} as its only component. */
    Node startWith(Map<String, String> env, Doors doors) throws Exception {
        NodeConfig config = NodeConfig.from(env);
        return OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(doors), List.of(), List.of()));
    }

    static void closeNode(Node n) throws Exception {
        OnVirtualThread.call(() -> { n.close(); return null; });
    }

    static void putOk(int admin, String name, byte[] zip) throws Exception {
        try (HttpClient client = http()) {
            HttpResponse<String> put = send(client, "PUT", admin, "/admin/packages/" + name, zip, "application/zip");
            assertEquals(200, put.statusCode(), "fixture: " + name + " deploys: " + put.body());
        }
    }

    /** {@code GET /health}, its status asserted, its body. */
    static JsonObject health(int admin, int status) throws Exception {
        HttpResponse<String> h = get(admin, "/health");
        assertEquals(status, h.statusCode(), h.body());
        return new JsonObject(h.body());
    }

    /** The items of {@code GET /admin/<what>} by their {@code key} field. */
    static Map<String, JsonObject> listed(int admin, String what, String key) throws Exception {
        HttpResponse<String> r = get(admin, "/admin/" + what);
        assertEquals(200, r.statusCode(), r.body());
        Map<String, JsonObject> byKey = new java.util.TreeMap<>();
        new JsonObject(r.body()).getJsonArray("items").forEach(i -> byKey.put(((JsonObject) i).getString(key), (JsonObject) i));
        return byKey;
    }

    /** The item {@code key} of a listing; fails the case naming {@code what} when it is not listed. */
    static JsonObject itemOf(Map<String, JsonObject> listing, String key, String what) {
        JsonObject item = listing.get(key);
        assertNotNull(item, what + ": " + key + " is listed: " + listing);
        return item;
    }

    /** A node 1 on {@code data} that deployed {@code packages} (name → zip) over its admin API, then closed. */
    void deployedAndClosed(Map<String, byte[]> packages) throws Exception {
        Node first = startWith(env("ci"), new Doors());
        try {
            for (Map.Entry<String, byte[]> p : packages.entrySet()) putOk(first.adminPort(), p.getKey(), p.getValue());
        } finally {
            closeNode(first);
        }
    }

    // --- P2-02c (D-095): DELETE /admin/packages/{name} over a node

    /** The names in the case database's {@code deployments} table, sorted. */
    List<String> recordedPackages() throws SQLException {
        List<String> names = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword());
             Statement q = c.createStatement();
             java.sql.ResultSet rows = q.executeQuery("select package from deployments order by package")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    static HttpResponse<String> delete(int port, String path) throws IOException, InterruptedException {
        try (HttpClient client = http()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    /** The status of an invoice POSTed to the demo's in endpoint on a fresh connection; -1 when the connection is refused. */
    static int postInvoice(int inPort) throws IOException, InterruptedException {
        try (HttpClient client = http()) {
            return send(client, "POST", inPort, "/in", Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")), "application/xml").statusCode();
        } catch (ConnectException refused) {
            return -1;
        }
    }

    /**
     * PUT demo and a library {@code lib}, an invoice through demo's in endpoint reaches the partner; DELETE demo answers 200
     * {name, version, flowsRemoved}, deletes its row in Postgres and its directories, and its endpoint no longer runs an invoice
     * (refused or 404, the partner sees nothing more); its flow answers as unknown; a second DELETE is 404. The node restarted on
     * its data directory and database restores lib, not demo.
     */
    @Test
    void aPutThenDeleteUndeploysAndARestartDoesNotRestoreIt() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, Future.succeededFuture(), ack);
        try {
            int inPort = freePort();
            byte[] zip = zip(demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort()));
            startDemoNode(env("ci"));
            putOk(node.adminPort(), "demo", zip);
            putOk(node.adminPort(), "lib", PackageDraft.of("lib", "1.0.0").file("xslt/lib.xsl", "<xsl:stylesheet/>").zip());
            assertEquals(200, postInvoice(inPort), "fixture: the demo serves");
            assertEquals(1, partnerSaw.size(), "fixture: the partner saw the invoice: " + partnerSaw);
            assertEquals(List.of("demo", "lib"), recordedPackages(), "fixture: both are recorded");

            HttpResponse<String> deleted = delete(node.adminPort(), "/admin/packages/demo");

            assertEquals(200, deleted.statusCode(), deleted.body());
            assertEquals(new JsonObject().put("name", "demo").put("version", "0.1.0").put("flowsRemoved", new JsonArray().add("demo/invoice-sync")),
                    new JsonObject(deleted.body()), "the undeploy's answer");
            assertEquals(List.of("lib"), recordedPackages(), "demo's row is deleted, lib's stays");
            assertEquals(Set.of("lib"), listed(node.adminPort(), "packages", "name").keySet(), "GET /admin/packages lists lib only");
            assertEquals(Set.of(), listed(node.adminPort(), "flows", "id").keySet(), "no flow is listed");
            HttpResponse<String> cpu = get(node.adminPort(), "/admin/flows/demo%2Finvoice-sync/cpu");
            assertEquals(404, cpu.statusCode(), "demo's flow answers as an unknown flow: " + cpu.body());
            int after = postInvoice(inPort);
            assertTrue(after == -1 || after == 404, "demo's in endpoint no longer runs an invoice (refused or 404): " + after);
            assertEquals(1, partnerSaw.size(), "the partner saw nothing more: " + partnerSaw);
            Path demoDir = data.resolve("packages").resolve("demo");
            if (Files.exists(demoDir)) {
                try (Stream<Path> left = Files.list(demoDir)) {
                    assertEquals(List.of(), left.toList(), "demo's directories are pruned");
                }
            }
            assertEquals(404, delete(node.adminPort(), "/admin/packages/demo").statusCode(), "a second DELETE: no package demo");

            closeNode(node);
            node = null;
            startDemoNode(env("ci"));

            assertEquals(Set.of("lib"), listed(node.adminPort(), "packages", "name").keySet(), "the restart restores lib, not demo");
            assertEquals("RUNNING", itemOf(listed(node.adminPort(), "packages", "name"), "lib", "restored").getString("state"), "witness: the restore ran");
            assertEquals(Set.of(), listed(node.adminPort(), "flows", "id").keySet(), "no flow runs");
            int restarted = postInvoice(inPort);
            assertTrue(restarted == -1 || restarted == 404, "demo's in endpoint does not run after the restart: " + restarted);
            assertEquals(1, partnerSaw.size(), "the partner saw nothing more: " + partnerSaw);
            assertEquals(List.of("lib"), recordedPackages());
        } finally {
            partner.close().await();
        }
    }

    /**
     * The restore runs inside {@code Node.start}, before the admin server: while the restore holds shop/a's start (a held consumer
     * start), the configured admin port refuses connections and the start has not returned; released, the start returns and
     * the port serves both flows, RUNNING.
     */
    @Test
    void restoreRunsBeforeTheAdminPortOpens() throws Exception {
        deployedAndClosed(Map.of("shop", doorsPackage("shop", "a", "b").zip()));
        int admin = freePort();
        Map<String, String> env = env("ci");
        env.put("FLOTTILA_ADMIN_PORT", String.valueOf(admin));
        NodeConfig config = NodeConfig.from(env);
        Doors second = new Doors();
        Doors.Gate held = second.holdStartOf("shop/a");
        OnVirtualThread.Call<Node> starting = OnVirtualThread.start(() -> Node.start(config, vertx, ctx -> List.of(second), List.of(), List.of()));
        AssertionError whileHeld = null;
        try {
            until(() -> held.reached().getCount() == 0 || starting.isDone(), "the restore calls shop/a's start");
            assertEquals(0, held.reached().getCount(), "the start ended without restoring shop/a: " + starting.outcome());
            assertTrue(refuses(admin), "while the restore runs, the admin port is not open");
            assertFalse(starting.isDone(), "Node.start waits for its restore");
        } catch (AssertionError e) {
            whileHeld = e;
        }
        held.release();
        try {
            node = OnVirtualThread.join(starting);   // a node that started is closed after the case, whatever the case found
        } catch (Exception startFailed) {
            if (whileHeld == null) throw startFailed;
            whileHeld.addSuppressed(startFailed);
        }
        if (whileHeld != null) throw whileHeld;
        assertEquals(admin, node.adminPort());
        assertFalse(refuses(admin), "witness: once the restore returned, the admin port listens");
        Map<String, JsonObject> flows = listed(admin, "flows", "id");
        assertEquals(Set.of("shop/a", "shop/b"), flows.keySet(), "both restored flows are listed: " + flows);
        for (JsonObject f : flows.values()) assertEquals("RUNNING", f.getString("state"), f.encode());
    }

    /**
     * The {@code deployments} health check: UP while every package and flow runs; DOWN — /health 503, the other checks UP, the
     * wire showing the status only, never a name (D-057 a) — while a restored flow did not start, and while a package could not be
     * restored (its archive gone); UP again once a PUT of that package ran it.
     */
    @Test
    void healthIsDownWhileAFlowOrPackageIsFailed() throws Exception {
        byte[] shop = doorsPackage("shop", "a", "b").zip();
        byte[] store = doorsPackage("store", "c").zip();
        node = startWith(env("ci"), new Doors());
        putOk(node.adminPort(), "shop", shop);
        putOk(node.adminPort(), "store", store);
        JsonObject up = health(node.adminPort(), 200);
        assertEquals("UP", up.getJsonObject("checks").getString("deployments"), "the node reports a deployments check, UP while everything runs: " + up.encode());
        closeNode(node);
        node = null;

        Doors second = new Doors();
        second.failStartOf("shop/b", "address in use");
        node = startWith(env("ci"), second);
        assertEquals("FAILED", itemOf(listed(node.adminPort(), "flows", "id"), "shop/b", "restored with b failed").getString("state"), "fixture: b did not start on restore");
        JsonObject flowDown = health(node.adminPort(), 503);
        assertEquals("DOWN", flowDown.getString("status"), flowDown.encode());
        assertEquals("DOWN", flowDown.getJsonObject("checks").getString("deployments"), "a FAILED flow: " + flowDown.encode());
        assertEquals("UP", flowDown.getJsonObject("checks").getString("postgres"), "the other checks are UP: " + flowDown.encode());
        assertFalse(flowDown.encode().contains("shop"), "the wire shows the status only, never which flow: " + flowDown.encode());
        closeNode(node);
        node = null;

        Files.delete(data.resolve("archives").resolve(sha256(store) + ".zip"));
        node = startWith(env("ci"), new Doors());
        Map<String, JsonObject> packages = listed(node.adminPort(), "packages", "name");
        assertEquals("FAILED", itemOf(packages, "store", "restored without store's archive").getString("state"), "fixture: store's archive is gone: " + packages);
        assertEquals("RUNNING", itemOf(packages, "shop", "restored").getString("state"), "fixture: shop restored: " + packages);
        assertEquals(Set.of("RUNNING"), listed(node.adminPort(), "flows", "id").values().stream().map(f -> f.getString("state")).collect(java.util.stream.Collectors.toSet()),
                "fixture: every flow listed runs, shop/b included");
        JsonObject packageDown = health(node.adminPort(), 503);
        assertEquals("DOWN", packageDown.getJsonObject("checks").getString("deployments"), "a FAILED package: " + packageDown.encode());
        assertFalse(packageDown.encode().contains("store"), "the wire shows the status only, never which package: " + packageDown.encode());

        putOk(node.adminPort(), "store", store);
        JsonObject again = health(node.adminPort(), 200);
        assertEquals("UP", again.getJsonObject("checks").getString("deployments"), "recovered by a PUT: " + again.encode());
    }

    /**
     * The admin port is taken: {@code Node.start} restores shop, then fails on the admin server's bind — and stops the restored
     * flows (each door started, then stopped), closes their producers, and closes Deployments before the pool: when each door
     * stops, the node's pool still holds its connections to the case database; afterwards none is left and no verticle stays.
     */
    @Test
    void anAdminStartFailureStopsTheRestoredFlows() throws Exception {
        deployedAndClosed(Map.of("shop", doorsPackage("shop", "a", "b").zip()));
        Doors second = new Doors();
        List<String> atStop = new CopyOnWriteArrayList<>();
        second.onStop(door -> atStop.add(door.flow() + (Thread.currentThread().isVirtual()
                ? " connections=" + connections() : " stopped on a platform thread, connections not read")));
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            Map<String, String> env = env("ci");
            env.put("FLOTTILA_ADMIN_PORT", String.valueOf(taken.getLocalPort()));
            Set<String> before = Set.copyOf(vertx.deploymentIDs());
            Exception failed = null;
            try {
                node = startWith(env, second);
            } catch (Exception expected) {
                failed = expected;
            }
            assertNotNull(failed, "start fails while another socket holds the admin port");
            assertTrue(isBindError(failed), "the failure is the admin port's bind error: " + causes(failed));
            List<Doors.Door> restored = second.doors();
            assertEquals(Set.of("shop/a", "shop/b"), restored.stream().map(Doors.Door::flow).collect(java.util.stream.Collectors.toSet()),
                    "the restore built a door per flow before the admin server started: " + second.events());
            for (Doors.Door d : restored) {
                assertTrue(d.history().contains("started") && d.stopped(), d + " was restored, then stopped by the failed start: " + d.history());
            }
            until(() -> second.openProducers().isEmpty(), "every producer of the restored flows is closed: " + second.producers());
            assertEquals(2, atStop.size(), "each door stopped once: " + atStop);
            for (String s : atStop) {
                assertTrue(s.matches(".* connections=[1-9][0-9]*"), "Deployments closes before the pool: at each stop the pool's connections are open: " + atStop);
            }
            until(() -> connections() == 0, "then the pool closed: no connection to the case database is left");
            assertEquals(before, Set.copyOf(vertx.deploymentIDs()), "no verticle stays deployed");
        }
    }

    /**
     * (implementer, P1-09c3b) The admin port is taken and a restored flow's consumer does not stop: {@code Node.start} throws the
     * bind error, not the stop's failure — that one is added to it as suppressed — and closes the pool all the same.
     */
    @Test
    void aRestoredFlowThatDoesNotStopIsSuppressedUnderTheAdminStartFailure() throws Exception {
        deployedAndClosed(Map.of("shop", doorsPackage("shop", "a").zip()));
        Doors second = new Doors();
        second.failStop("shop/a", "the restored consumer did not stop");
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            Map<String, String> env = env("ci");
            env.put("FLOTTILA_ADMIN_PORT", String.valueOf(taken.getLocalPort()));
            Exception failed = null;
            try {
                node = startWith(env, second);
            } catch (Exception expected) {
                failed = expected;
            }
            assertNotNull(failed, "start fails while another socket holds the admin port");
            assertTrue(isBindError(failed), "the start's failure is the bind error, not the stop's: " + causes(failed));
            List<String> suppressed = java.util.Arrays.stream(failed.getSuppressed()).map(s -> String.valueOf(s.getMessage())).toList();
            assertTrue(suppressed.stream().anyMatch(m -> m.contains("the restored consumer did not stop")), "the failed stop is suppressed: " + suppressed);
            assertTrue(second.door("shop/a").stopped(), "the restored flow was stopped: " + second.events());
            until(() -> connections() == 0, "the pool closed all the same");
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

    /** {@code name} 1.0.0 on http: the sync flow {@code relay} from {@code in} (127.0.0.1:{@code inPort}, /in) to {@code partnerUrl}. */
    static PackageDraft relay(String name, int inPort, String partnerUrl) {
        return PackageDraft.of(name, "1.0.0")
                .endpoint("in", "http", "in", new JsonObject().put("host", "127.0.0.1").put("port", inPort).put("path", "/in"))
                .endpoint("partner", "http", "out", new JsonObject().put("url", partnerUrl).put("maxBodyBytes", 4096))
                .flow("relay", "1.0.0", "sync", "in", 4096, PackageDraft.toPartner());
    }

    /**
     * P1-09g (D-072): Vert.x 5.1 shares one server between the servers that listen on one host:port in a Vert.x instance (the
     * second joins the first's and connections are balanced between them, no bind error), so a second package on the first's
     * http port would take part of its traffic. The PUT of package two is 422 naming both flows and endpoints; package one alone
     * is listed, and N POSTs, each on a connection of its own, all reach package one's partner path.
     */
    @Test
    void aSecondPackageOnTheFirstsHttpPortIs422AndTheFirstServesOn() throws Exception {
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, Future.succeededFuture(), Buffer.buffer("<ok/>"));
        try {
            int inPort = freePort();
            String partnerBase = "http://127.0.0.1:" + partner.actualPort();
            start(env("ci"), ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())));
            int admin = node.adminPort();
            putOk(admin, "one", relay("one", inPort, partnerBase + "/one").zip());

            HttpResponse<String> put;
            try (HttpClient client = http()) {
                put = send(client, "PUT", admin, "/admin/packages/two", relay("two", inPort, partnerBase + "/two").zip(), "application/zip");
            }

            assertEquals(422, put.statusCode(), "the second package on the first's port is refused: " + put.body());
            assertEquals(new JsonArray().add("flow 'relay' (endpoint 'in') listens on 127.0.0.1:" + inPort + ", which overlaps 127.0.0.1:" + inPort
                    + " of flow 'one/relay' (endpoint 'in'): one listener per port on a node (D-072)"), new JsonObject(put.body()).getJsonArray("problems"), put.body());
            assertEquals(Set.of("one"), listed(admin, "packages", "name").keySet(), "only the first package is deployed");
            int n = 8;
            for (int i = 0; i < n; i++) {
                try (HttpClient fresh = http()) {   // a connection of its own: a shared server balances connections, not requests
                    HttpResponse<String> r = send(fresh, "POST", inPort, "/in", ("<n>" + i + "</n>").getBytes(StandardCharsets.UTF_8), "application/xml");
                    assertEquals(200, r.statusCode(), "request " + i + ": " + r.body());
                }
            }
            assertEquals(n, partnerSaw.size(), "the partner saw every request once: " + partnerSaw);
            assertTrue(partnerSaw.stream().allMatch(s -> s.startsWith("/one ")), "every request reached package one: " + partnerSaw);
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

    // --- P1-09c2 (D-067, GATE check 7): replay and DLQ retry over the admin API

    /** The demo flow's {@code retry.attempts}: retries after the first try (RetryPolicy). */
    static final int DEMO_RETRIES = 3;

    /** {@code POST path} on the admin port with an empty body, as the CLI sends it: no Origin, and this node has no token. */
    static HttpResponse<String> post(HttpClient client, int admin, String path) throws IOException, InterruptedException {
        return send(client, "POST", admin, path, new byte[0], "application/json");
    }

    /** The {@code steps} of {@code GET /admin/messages/{id}}; empty while nothing of it is recorded (404). */
    static JsonArray stepsOf(int admin, String id) {
        try {
            HttpResponse<String> r = get(admin, "/admin/messages/" + id);
            if (r.statusCode() == 404) return new JsonArray();
            assertEquals(200, r.statusCode(), r.body());
            return new JsonObject(r.body()).getJsonArray("steps");
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("the admin API did not answer", e);
        }
    }

    /** The rows {@code GET /admin/dlq} lists. */
    static JsonArray dlqItems(int admin) {
        try {
            HttpResponse<String> r = get(admin, "/admin/dlq");
            assertEquals(200, r.statusCode(), r.body());
            return new JsonObject(r.body()).getJsonArray("items");
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("the admin API did not answer", e);
        }
    }

    /** Each row as {@code "<step> <name>"}. */
    static List<String> stepNames(JsonArray steps) {
        return steps.stream().map(JsonObject.class::cast).map(s -> s.getInteger("step") + " " + s.getString("name")).toList();
    }

    /** The one message found by {@code invoiceId:4711}, once its six rows (ingress and five steps) are recorded. */
    static String invoiceByKey(int admin) {
        List<String> found = new CopyOnWriteArrayList<>();
        until(() -> {
            try {
                found.clear();
                new JsonObject(get(admin, "/admin/messages?key=invoiceId:4711").body()).getJsonArray("items").forEach(id -> found.add((String) id));
                return found.size() == 1 && stepsOf(admin, found.get(0)).size() == 6;
            } catch (IOException | InterruptedException e) {
                throw new AssertionError("the admin API did not answer", e);
            }
        }, "the invoice is found by its key and its six rows are recorded");
        return found.get(0);
    }

    /**
     * GATE check 7, replay: the demo (sync) has sent an invoice to the partner. {@code POST /admin/messages/{id}/replay?from=3}
     * answers 200 with exactly {@code {id, replayOf, from, outcome}}: a new id, the original's, 3, COMPLETE. The new message ran
     * steps 3..5 (route, toPartner, partner) and nothing before: its timeline starts with its input at step 2 — the original's
     * recorded step-2 output, by hash — and every row of it is marked {@code replayOf} the original. The partner got the same
     * mapped invoice a second time; the original's timeline is as it was, unmarked.
     */
    @Test
    void replayFromStep3IsANewMessageMarkedReplayOf() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, Future.succeededFuture(), ack);
        try (HttpClient client = http()) {
            int inPort = freePort();
            byte[] zip = zip(demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort()));
            startDemoNode(env("ci"));
            int admin = node.adminPort();
            assertEquals(200, send(client, "PUT", admin, "/admin/packages/demo", zip, "application/zip").statusCode(), "fixture: the demo deploys");
            HttpResponse<String> invoice = send(client, "POST", inPort, "/in", Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")), "application/xml");
            assertEquals(200, invoice.statusCode(), "fixture: the invoice went through: " + invoice.body());
            String original = invoiceByKey(admin);
            JsonArray originalSteps = stepsOf(admin, original);
            assertEquals(1, partnerSaw.size(), "fixture: the partner saw the invoice once: " + partnerSaw);

            HttpResponse<String> replayed = post(client, admin, "/admin/messages/" + original + "/replay?from=3");

            assertEquals(200, replayed.statusCode(), replayed.body());
            JsonObject answer = new JsonObject(replayed.body());
            String id = answer.getString("id");
            assertTrue(id != null && !id.equals(original), "the replay is a new message: " + answer);
            assertEquals(new JsonObject().put("id", id).put("replayOf", original).put("from", 3).put("outcome", "COMPLETE"), answer, "the replay's answer");
            assertEquals(2, partnerSaw.size(), "the replay reached the partner, once: " + partnerSaw);
            assertEquals(partnerSaw.get(0), partnerSaw.get(1), "the same mapped invoice: steps 3..5 ran on the recorded step-2 output");
            until(() -> stepsOf(admin, id).size() == 4, "the replay's rows are recorded: its input and steps 3..5");
            JsonArray steps = stepsOf(admin, id);
            assertEquals(List.of("2 replay", "3 route", "4 toPartner", "5 partner"), stepNames(steps), "steps 3..n ran, nothing before: " + steps.encode());
            for (Object each : steps) {
                JsonObject row = (JsonObject) each;
                assertEquals("OK", row.getString("outcome"), row.encode());
                assertEquals(original, row.getJsonObject("props").getString("replayOf"), "every row of the new message is marked replayOf: " + row.encode());
            }
            JsonObject recordedStep2 = originalSteps.stream().map(JsonObject.class::cast).filter(s -> s.getInteger("step") == 2).findFirst().orElseThrow();
            assertEquals(recordedStep2.getJsonObject("body").getString("hash"), steps.getJsonObject(0).getJsonObject("body").getString("hash"),
                    "the replay's input is the original's recorded step-2 output: " + recordedStep2.encode());
            assertEquals(originalSteps, stepsOf(admin, original), "the original's timeline is as it was");
            assertTrue(originalSteps.stream().map(JsonObject.class::cast).noneMatch(s -> s.getJsonObject("props").containsKey("replayOf")),
                    "the original is not marked: " + originalSteps.encode());
        } finally {
            partner.close().await();
        }
    }

    /**
     * GATE check 7, DLQ: the demo switched to at-least-once, its partner down (503). The worker tries the partner step once and
     * retries it per the flow's policy ({@value #DEMO_RETRIES} retries), then parks the message: {@code GET /admin/dlq} lists its
     * row at step 5 (partner) naming the 503. The partner comes back; {@code POST /admin/dlq/{id}/retry} answers 200 with exactly
     * {@code {id, replayOf, from, outcome}} — a new id, the parked one, 5, COMPLETE — the partner gets the mapped invoice once
     * more and answers it, the row is gone from {@code GET /admin/dlq}, and the new message's rows are the parked input at step 4
     * and the partner step, marked {@code replayOf}.
     */
    @Test
    void partnerDownParksAndRetryDeliversOncePartnerIsBack() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        AtomicBoolean down = new AtomicBoolean(true);
        io.vertx.core.http.HttpServer partner = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            boolean isDown = down.get();
            partnerSaw.add((isDown ? "503 " : "200 ") + req.path() + " " + b);
            if (isDown) req.response().setStatusCode(503).end("partner down");
            else req.response().putHeader("content-type", "application/xml").end(ack);
        })).listen(0, "127.0.0.1").await();
        try (HttpClient client = http()) {
            int inPort = freePort();
            Map<String, byte[]> files = demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort());
            files.put("flows/invoice-sync.yaml", replaced(files.get("flows/invoice-sync.yaml"), Map.of("mode: sync", "mode: at-least-once")));
            assertTrue(new String(files.get("flows/invoice-sync.yaml"), StandardCharsets.UTF_8).contains("attempts: " + DEMO_RETRIES),
                    "fixture: the demo flow retries its partner step " + DEMO_RETRIES + " times");
            startDemoNode(env("ci"));
            int admin = node.adminPort();
            assertEquals(200, send(client, "PUT", admin, "/admin/packages/demo", zip(files), "application/zip").statusCode(), "fixture: the demo deploys");
            HttpResponse<String> accepted = send(client, "POST", inPort, "/in", Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")), "application/xml");
            assertEquals(202, accepted.statusCode(), accepted.body());
            String parked = accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("the 202 names its message id: " + accepted.headers()));

            until(() -> !dlqItems(admin).isEmpty(), "the message is parked once the partner step's retries are spent");
            JsonArray rows = dlqItems(admin);
            assertEquals(1, rows.size(), "one DLQ row: " + rows.encode());
            JsonObject row = rows.getJsonObject(0);
            assertEquals(parked, row.getString("id"), row.encode());
            assertEquals("demo/invoice-sync", row.getString("flow"), row.encode());
            assertEquals(5, row.getInteger("step"), "parked at the partner step: " + row.encode());
            assertEquals("partner", row.getString("name"), row.encode());
            assertTrue(row.getString("error").contains("503"), "the row names the partner's 503: " + row.encode());
            assertEquals(1 + DEMO_RETRIES, partnerSaw.size(), "the first try and " + DEMO_RETRIES + " retries, no more: " + partnerSaw);
            assertTrue(partnerSaw.stream().allMatch(s -> s.startsWith("503 /echo ")), "each answered 503: " + partnerSaw);

            down.set(false);
            HttpResponse<String> retried = post(client, admin, "/admin/dlq/" + parked + "/retry");

            assertEquals(200, retried.statusCode(), retried.body());
            JsonObject answer = new JsonObject(retried.body());
            String id = answer.getString("id");
            assertTrue(id != null && !id.equals(parked), "the retry is a new message: " + answer);
            assertEquals(new JsonObject().put("id", id).put("replayOf", parked).put("from", 5).put("outcome", "COMPLETE"), answer, "the retry's answer");
            assertEquals(2 + DEMO_RETRIES, partnerSaw.size(), "delivered once the partner is back, once: " + partnerSaw);
            String delivered = partnerSaw.getLast();
            assertTrue(delivered.startsWith("200 /echo ") && delivered.contains("<PartnerInvoice number=\"4711\""), "the mapped invoice reached the partner and was answered: " + delivered);
            assertEquals(new JsonArray(), dlqItems(admin), "the row is gone");
            until(() -> stepsOf(admin, id).size() == 2, "the retry's rows are recorded: its input and the partner step");
            JsonArray steps = stepsOf(admin, id);
            assertEquals(List.of("4 replay", "5 partner"), stepNames(steps), "only the parked step ran, on the parked input: " + steps.encode());
            for (Object each : steps) {
                JsonObject step = (JsonObject) each;
                assertEquals("OK", step.getString("outcome"), step.encode());
                assertEquals(parked, step.getJsonObject("props").getString("replayOf"), "marked replayOf the parked message: " + step.encode());
            }
        } finally {
            partner.close().await();
        }
    }

    // --- P1-09h (D-058, D-061): durable at-least-once bodies; a node commits synchronously or does not start

    /**
     * The demo package with its flow switched to at-least-once (its 1 MiB body limit is FLOTTILA_BLOB_DURABLE_MAX's default):
     * the 202 promises the body. Expectation changed by CORE-4c2 (D-105): the promise is the journal's — another session sees,
     * the moment the 202 arrives, the ingress body's kept ref ({@code body_refs}, the flow keeping its input) on this node and
     * no {@code bodies} row; the worker reads the body back from the journal and sends it on. The invoice is padded above the
     * memory tier (4 KiB here, so RECORD_MAX is 4 KiB too): a large body of its own in the journal, and no {@code <data>/spool}.
     */
    @Test
    void anAcceptedAtLeastOnceMessageIsKeptInTheJournalNotInBodies() throws Exception {
        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        List<String> partnerSaw = new CopyOnWriteArrayList<>();
        io.vertx.core.http.HttpServer partner = partner(partnerSaw, Future.succeededFuture(), ack);
        try (HttpClient client = http()) {
            int inPort = freePort();
            Map<String, byte[]> files = demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort());
            files.put("flows/invoice-sync.yaml", replaced(files.get("flows/invoice-sync.yaml"), Map.of("mode: sync", "mode: at-least-once")));
            Map<String, String> env = env("ci");
            env.put("FLOTTILA_BLOB_MEMORY_TIER", "4096");
            startDemoNode(env);
            HttpResponse<String> put = send(client, "PUT", node.adminPort(), "/admin/packages/demo", zip(files), "application/zip");
            assertEquals(200, put.statusCode(), put.body());

            byte[] invoice = (Files.readString(DEMO.resolve("fixtures/sample-invoice.xml")) + "<!-- " + "padding ".repeat(1024) + "-->")
                    .getBytes(StandardCharsets.UTF_8);
            assertTrue(invoice.length > 8192, "fixture: above the 4 KiB memory tier");
            HttpResponse<String> accepted = send(client, "POST", inPort, "/in", invoice, "application/xml");
            assertEquals(202, accepted.statusCode(), accepted.body());
            String id = accepted.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("the 202 names its message id: " + accepted.headers()));
            assertEquals(List.of("node-it"), keptRefNodes(id), "no wait: what the 202 promised is kept in this node's journal, under (id, 0, body)");
            assertEquals(Optional.empty(), bodiesRow(id), "and no bytes went to Postgres");
            assertFalse(Files.exists(data.resolve("spool")), "nor to a spool");

            until(() -> partnerSaw.size() == 1, "the worker sends the invoice on, its body read back from the journal");
            assertTrue(partnerSaw.get(0).startsWith("/echo ") && partnerSaw.get(0).contains("<PartnerInvoice number=\"4711\""), "the mapped invoice went to the domestic partner: " + partnerSaw);
        } finally {
            partner.close().await();
        }
    }

    /** The nodes of the message's ingress body ref ({@code body_refs} under (id, 0, body)), as a session of its own sees them. */
    List<String> keptRefNodes(String messageId) {
        PgConnection reader = PgConnection.connect(vertx, options(database)).await();
        try {
            List<String> nodes = new java.util.ArrayList<>();
            for (io.vertx.sqlclient.Row r : reader.preparedQuery("select node from body_refs where message_id = $1 and step = 0 and part = 'body'")
                    .execute(Tuple.of(messageId)).await()) {
                nodes.add(r.getString(0));
            }
            return nodes;
        } finally {
            reader.close().await();
        }
    }

    /**
     * FIX-136 P2 (D-127 draft). Rewritten from aNodeRefusesAsynchronousCommit: D-061 is amended by the session pin — its start
     * refusal of a database-level async commit is replaced by the node's startup parameters {@code synchronous_commit=on} and
     * {@code statement_timeout=0}, which a session's start sets above any database or role default. The case database runs
     * {@code synchronous_commit=off} and a 45 s statement timeout for every new session (witness: a plain session shows both); the
     * node starts on it anyway, and the session its at-least-once intake commits on — seen by a BEFORE INSERT trigger on
     * {@code messages} noting that session's own settings — commits {@code on} without a statement timeout.
     */
    @Test
    void aDatabaseLevelAsynchronousCommitDoesNotWeakenTheNodesSessions() throws Exception {
        synchronousCommit("off");
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement alter = admin.createStatement()) {
            alter.execute("ALTER DATABASE \"" + database + "\" SET statement_timeout = '45s'");
        }
        PgConnection plain = PgConnection.connect(vertx, options(database)).await();
        try {
            assertEquals("off", plain.query("show synchronous_commit").execute().await().iterator().next().getString(0),
                    "witness: a new session of the case database commits asynchronously");
            assertEquals("45s", plain.query("show statement_timeout").execute().await().iterator().next().getString(0),
                    "witness: and runs with the database's statement timeout");
        } finally {
            plain.close().await();
        }

        Buffer ack = Buffer.buffer(Files.readAllBytes(DEMO.resolve("fixtures/partner-ack.xml")));
        io.vertx.core.http.HttpServer partner = partner(new CopyOnWriteArrayList<>(), Future.succeededFuture(), ack);
        try (HttpClient client = http()) {
            int inPort = freePort();
            Map<String, byte[]> files = demoFiles(inPort, "http://127.0.0.1:" + partner.actualPort());
            files.put("flows/invoice-sync.yaml", replaced(files.get("flows/invoice-sync.yaml"), Map.of("mode: sync", "mode: at-least-once")));
            startDemoNode(env("ci"));   // before FIX-136: refused, "synchronous_commit is off for the node's sessions"
            assertEquals(200, get(node.adminPort(), "/health").statusCode(), "the node starts on the database whose default is off");
            HttpResponse<String> put = send(client, "PUT", node.adminPort(), "/admin/packages/demo", zip(files), "application/zip");
            assertEquals(200, put.statusCode(), put.body());

            PgConnection ddl = PgConnection.connect(vertx, options(database)).await();
            try {
                ddl.query("create table commit_settings(sc text, st text)").execute().await();
                ddl.query("create function note_settings() returns trigger language plpgsql as $$ begin insert into commit_settings "
                        + "values (current_setting('synchronous_commit'), current_setting('statement_timeout')); return new; end $$").execute().await();
                ddl.query("create trigger note_settings before insert on messages for each row execute function note_settings()").execute().await();
            } finally {
                ddl.close().await();
            }

            HttpResponse<String> accepted = send(client, "POST", inPort, "/in", Files.readAllBytes(DEMO.resolve("fixtures/sample-invoice.xml")), "application/xml");
            assertEquals(202, accepted.statusCode(), accepted.body());
            PgConnection reader = PgConnection.connect(vertx, options(database)).await();
            try {
                List<String> seen = new java.util.ArrayList<>();
                for (io.vertx.sqlclient.Row r : reader.query("select sc || ' ' || st from commit_settings").execute().await()) seen.add(r.getString(0));
                assertFalse(seen.isEmpty(), "witness: the trigger saw the intake's insert of the ingress row");
                assertEquals(List.of(), seen.stream().filter(s -> !s.equals("on 0")).toList(),
                        "the intake's session commits on, without a statement timeout, over the database's off and 45s: " + seen);
            } finally {
                reader.close().await();
            }
        } finally {
            partner.close().await();
        }
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

    /**
     * P2-15a acceptance 6 (D-101 (5)): a node states, once at start and in the admin health's notices, that its at-least-once
     * items wait for it while it is down (one journal copy, until CORE-5) and that no other member is live — never a refusal
     * (health 200). "Once": two renews of its node lease later (a short lease, FLOTTILA_NODE_LEASE PT2S, renewed every PT0.5S,
     * seen as {@code nodes.lease_until} moving on the case database) each line is still logged once.
     */
    @Test
    void aSingleCopyNodeStatesItOnceAtStartAndInTheAdminHealth() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        Handler all = new Handler() {
            @Override public void publish(LogRecord r) { lines.add(r.getLevel() + " " + new SimpleFormatter().formatMessage(r)); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        all.setLevel(Level.ALL);
        Logger flottila = Logger.getLogger("io.flottila");
        flottila.addHandler(all);
        try {
            Map<String, String> env = env("dev");
            env.put("FLOTTILA_NODE_LEASE", "PT2S");
            env.put("FLOTTILA_NODE_LEASE_RENEW", "PT0.5S");
            Node n = start(env, ctx -> List.of());

            JsonObject h = health(n.adminPort(), 200);   // a notice is no failure
            List<String> notices = h.getJsonArray("notices", new JsonArray()).stream().map(String::valueOf).toList();
            assertTrue(notices.stream().anyMatch(t -> t.contains("one journal copy") && t.contains("D-101 (5)")),
                    "the single-copy notice in the admin health: " + h.encode());
            assertTrue(notices.stream().anyMatch(t -> t.contains("no other live member")), "the no-other-member notice: " + h.encode());

            PgConnection reader = PgConnection.connect(vertx, options(database)).await();
            try {
                Object joined = leaseUntil(reader);
                until(() -> !java.util.Objects.equals(leaseUntil(reader), joined), "the node renews its lease");
                Object renewed = leaseUntil(reader);
                until(() -> !java.util.Objects.equals(leaseUntil(reader), renewed), "and renews it again");
            } finally {
                reader.close().await();
            }
            assertEquals(1, lines.stream().filter(l -> l.contains("one journal copy")).count(),
                    "the single-copy notice logged once at start, not at every renew: " + lines);
            assertEquals(1, lines.stream().filter(l -> l.contains("no other live member")).count(),
                    "the no-other-member notice logged once: " + lines);
        } finally {
            flottila.removeHandler(all);
        }
    }

    /**
     * P2-15a review r1 decision 6: the "no other live member" notice is only stated when no other member is live. Another node's
     * live {@code nodes} row on the case database before the start (migrated first, the row inserted) silences it, in the admin
     * health and in the log; the single-copy notice stays.
     */
    @Test
    void aNodeThatFindsAnotherLiveMemberAtStartDoesNotStateItIsAlone() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        Handler all = new Handler() {
            @Override public void publish(LogRecord r) { lines.add(r.getLevel() + " " + new SimpleFormatter().formatMessage(r)); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        all.setLevel(Level.ALL);
        Logger flottila = Logger.getLogger("io.flottila");
        flottila.addHandler(all);
        try {
            OnVirtualThread.call(() -> Migrations.migrate(url, pg.getUsername(), pg.getPassword()));
            PgConnection writer = PgConnection.connect(vertx, options(database)).await();
            try {
                writer.query("insert into nodes(node, epoch, lease_until) values ('node-peer', 1, now() + interval '10 minutes')").execute().await();
            } finally {
                writer.close().await();
            }
            Node n = start(env("dev"), ctx -> List.of());

            JsonObject h = health(n.adminPort(), 200);
            List<String> notices = h.getJsonArray("notices", new JsonArray()).stream().map(String::valueOf).toList();
            assertTrue(notices.stream().anyMatch(t -> t.contains("one journal copy") && t.contains("D-101 (5)")),
                    "the single-copy notice still in the admin health: " + h.encode());
            assertFalse(notices.stream().anyMatch(t -> t.contains("no other live member")),
                    "another member is live: no no-other-member notice in the admin health: " + h.encode());
            assertEquals(1, lines.stream().filter(l -> l.contains("one journal copy")).count(), "the single-copy notice logged once: " + lines);
            assertEquals(0, lines.stream().filter(l -> l.contains("no other live member")).count(), "nor in the log: " + lines);
        } finally {
            flottila.removeHandler(all);
        }
    }

    /** The node's {@code nodes.lease_until} on the case database (null: no row yet). */
    static Object leaseUntil(PgConnection reader) {
        var rows = reader.query("select lease_until from nodes where node = 'node-it'").execute().await();
        return rows.size() == 0 ? null : rows.iterator().next().getValue(0);
    }

    // --- FIX-144 (D-123): GET /live and GET /ready over the node's real wiring
    //
    // No sleep: each case spins (to a 20 s deadline) on a condition. The node's lease runs on its own clock (System::nanoTime and
    // the Vert.x timer: Node.start takes no clock), so the fence closes the way a real outage closes it — the node's renews wait
    // on its nodes row, which a test session holds, for half a lease (FLOTTILA_NODE_LEASE PT2S, renewed every PT0.5S).

    static final List<String> READINESS = List.of("lifecycle", "fence", "journal", "deployments");

    /** The /ready body the spec (R2) gives: {@code status}, and every check UP but those named {@code down}. */
    static JsonObject readyBody(String status, String... down) {
        JsonObject checks = new JsonObject();
        for (String name : READINESS) checks.put(name, List.of(down).contains(name) ? "DOWN" : "UP");
        return new JsonObject().put("status", status).put("checks", checks);
    }

    static final JsonObject LIVE = new JsonObject().put("status", "UP");

    /** {@code GET path}, its status asserted, its JSON body. */
    static JsonObject answer(int port, String path, int status) throws Exception {
        HttpResponse<String> r = get(port, path);
        assertEquals(status, r.statusCode(), path + ": " + r.body());
        return new JsonObject(r.body());
    }

    /** Asks {@code GET path} until it answers {@code status} (20 s, no sleep); that answer's JSON body. */
    static JsonObject answering(int port, String path, int status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            HttpResponse<String> r = get(port, path);
            if (r.statusCode() == status) return new JsonObject(r.body());
            if (System.nanoTime() > deadline) fail("not within 20 s: " + path + " answering " + status + "; last " + r.statusCode() + " " + r.body());
            Thread.onSpinWait();
        }
    }

    /** The node's backends of the case database waiting on a lock in an {@code update nodes} (its renews). */
    long renewsWaitingOnALock() {
        return observer.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock' and query ilike '%update nodes%'")
                .execute(Tuple.of(database)).await().iterator().next().getLong(0);
    }

    /**
     * R2 fence, R1: a test session holds the node's {@code nodes} row, so its renews wait; half a lease after the last renewed
     * send its fence closes — /ready 503 naming fence DOWN (lifecycle, journal, deployments UP) while /live stays 200. Released,
     * the renews go through, the fence opens again and /ready is 200 (read on every request).
     */
    @Test
    void fencedNodeIsNotReadyButRemainsLive() throws Exception {
        Map<String, String> env = env("dev");
        env.put("FLOTTILA_NODE_LEASE", "PT2S");
        env.put("FLOTTILA_NODE_LEASE_RENEW", "PT0.5S");
        start(env, ctx -> List.of());
        int port = node.adminPort();

        PgConnection holder = PgConnection.connect(vertx, options(database)).await();
        try {
            Transaction tx = holder.begin().await();
            try {
                int held = holder.query("select 1 from nodes where node = 'node-it' for no key update").execute().await().size();
                assertEquals(1, held, "fixture: the node's row is held");
                until(() -> renewsWaitingOnALock() > 0, "fixture: a renew of the node waits on its held row");

                JsonObject fenced = answering(port, "/ready", 503);
                assertEquals(readyBody("DOWN", "fence"), fenced, "the fence closed (no renew answered for half a lease): not ready, fence named");
                assertEquals(LIVE, answer(port, "/live", 200), "a fenced node is still live");
            } finally {
                tx.rollback().await();
            }
        } finally {
            holder.close().await();
        }
        assertEquals(readyBody("UP"), answering(port, "/ready", 200), "the renews answer again: the fence opens and the node is ready again");
    }

    /**
     * R3: Node.close drops readiness before its deployments stop the listeners. A sync delivery is held at the partner; the
     * close reaches the door's stop (held, draining that open answer): /ready is 503 naming lifecycle DOWN — the fence and the
     * rest still UP — /live 200, and the listener still answers the delivery it took once the partner does.
     */
    @Test
    void drainDropsReadinessBeforeListenersStop() throws Exception {
        Doors doors = new Doors();
        AtomicReference<ComponentContext> context = new AtomicReference<>();
        NodeConfig config = NodeConfig.from(env("ci"));
        node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> {
            context.set(ctx);
            return List.of(doors);
        }, List.of(), List.of()));
        int port = node.adminPort();
        putOk(port, "shop", doorsPackage("shop", "a").zip());

        CountDownLatch partner = doors.partner().holdNext(1);
        Doors.Gate stop = doors.holdStopOf("shop/a");
        try {
            ComponentContext ctx = context.get();
            String id = ctx.ids().next();
            Envelope e = Envelope.ingress(id, "shop/a", Headers.empty(),
                    ctx.blobs().write(BlobKey.body(id, 0), Buffer.buffer("<invoice/>"), 1024).await());
            Future<Ack> delivery = doors.door("shop/a").deliver(e);
            doors.partner().awaitSent(1);   // the sync answer is open on the listener until the partner answers

            Node closed = node;
            node = null;
            closing = OnVirtualThread.start(closed::close);
            assertTrue(stop.reached().await(20, TimeUnit.SECONDS), "fixture: close reached the listener's stop (it drains its open answer)");
            assertFalse(delivery.isComplete(), "fixture: the delivery is still open, held at the partner");

            assertEquals(readyBody("DOWN", "lifecycle"), answer(port, "/ready", 503), "closing: not ready while the listener still drains");
            assertEquals(LIVE, answer(port, "/live", 200), "a draining node is still live");

            partner.countDown();
            Ack ack = delivery.await(20, TimeUnit.SECONDS);
            assertInstanceOf(Ack.Reply.class, ack, "the listener still answers the delivery it took after readiness dropped: " + ack);
        } finally {
            partner.countDown();
            stop.release();
        }
    }

    /**
     * R2 journal (FIX-144 deviation: the in-process node's journal cannot be fail-stopped — Node.start opens it over the real
     * files — so the outage stands in, the rule's DEGRADED-counts-DOWN half): a trigger refuses the next segment's
     * {@code journal_segments} row, the pre-open retries and the journal is DEGRADED on /health; /ready is 503 naming journal
     * DOWN, /live 200. The trigger dropped, the retry goes in and /ready is 200 again.
     */
    @Test
    void journalInOutageNodeIsNotReady() throws Exception {
        Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute("""
                    create function fix144_refuse_next_segment() returns trigger language plpgsql as $$
                    begin
                      if exists (select 1 from journal_segments where node = new.node) then
                        raise exception 'fixture: the next journal segment''s row is refused';
                      end if;
                      return new;
                    end $$""");
            s.execute("create trigger fix144_refuse_next_segment before insert on journal_segments for each row execute function fix144_refuse_next_segment()");
        }
        start(env("dev"), ctx -> List.of());
        int port = node.adminPort();
        until(() -> {
            try {
                HttpResponse<String> h = get(port, "/health");
                return "DEGRADED".equals(new JsonObject(h.body()).getJsonObject("checks").getString("journal"));
            } catch (Exception e) {
                throw new AssertionError("GET /health", e);
            }
        }, "fixture: the journal is DEGRADED on /health (its pre-open's row refused)");

        assertEquals(readyBody("DOWN", "journal"), answer(port, "/ready", 503), "a journal in outage: not ready, journal named");
        assertEquals(LIVE, answer(port, "/live", 200), "a node whose journal is in outage is still live");

        try (Connection c = DriverManager.getConnection(url, pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute("drop trigger fix144_refuse_next_segment on journal_segments");
        }
        assertEquals(readyBody("UP"), answering(port, "/ready", 200), "the retried row went in: the journal is UP and the node ready again");
    }

    /** R2 deployments: a node restored with a FAILED flow is not ready — /ready 503 naming deployments DOWN — and live. */
    @Test
    void failedFlowNodeIsNotReady() throws Exception {
        deployedAndClosed(Map.of("shop", doorsPackage("shop", "a", "b").zip()));
        Doors second = new Doors();
        second.failStartOf("shop/b", "address in use");
        node = startWith(env("ci"), second);
        int port = node.adminPort();
        assertEquals("FAILED", itemOf(listed(port, "flows", "id"), "shop/b", "restored with b failed").getString("state"), "fixture: b did not start on restore");

        assertEquals(readyBody("DOWN", "deployments"), answer(port, "/ready", 503), "a FAILED flow: not ready, deployments named");
        assertEquals(LIVE, answer(port, "/live", 200), "a node with a FAILED flow is still live");
    }

    /**
     * R1: /live touches no Postgres. The case database stops taking connections and the node's backends are ended: /health
     * reports postgres DOWN (the node's PG is unreachable), /live still answers 200 {"status":"UP"}.
     */
    @Test
    void liveDoesNotTouchPostgres() throws Exception {
        start(env("dev"), ctx -> List.of());
        int port = node.adminPort();
        try {
            observer.query("alter database \"" + database + "\" with allow_connections false").execute().await();
            observer.preparedQuery("select pg_terminate_backend(pid) from pg_stat_activity where datname = $1").execute(Tuple.of(database)).await();
            JsonObject health = answering(port, "/health", 503);
            assertEquals("DOWN", health.getJsonObject("checks").getString("postgres"), "fixture: the node's Postgres is unreachable: " + health.encode());

            for (int i = 0; i < 3; i++) assertEquals(LIVE, answer(port, "/live", 200), "live without Postgres");
        } finally {
            observer.query("alter database \"" + database + "\" with allow_connections true").execute().await();
        }
    }
}
