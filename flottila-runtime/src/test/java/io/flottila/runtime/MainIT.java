package io.flottila.runtime;

import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.OnVirtualThread;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VerticleBase;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09d3: {@code Main} in process. {@code Main.start(env, err)} is {@code run} without the shutdown hook: it hands back the code
 * and what it started, so a case can run {@code Main.stopper(node, vertx)} — the hook's body — itself and leak nothing. The
 * stopper closes the node (the admin port refuses, the store is flushed) and only then Vert.x; it closes Vert.x even when the
 * node's close throws. A {@link Witness} verticle deployed on the started Vert.x marks the moment Vert.x closes: closing
 * Vert.x undeploys every verticle.
 *
 * <p>Assumed shape (package-private, in {@code Main}): {@code record Started(int code, Optional<Node> node, Optional<Vertx> vertx)},
 * {@code static Started start(Map<String, String> env, PrintStream err)}, {@code static Runnable stopper(Node node, Vertx vertx)};
 * {@code run} = {@code start} + registering {@code stopper} as the hook. {@code run} itself is exercised in a separate JVM
 * ({@link MainProcessIT}): in process its hook would outlive the case.
 */
class MainIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;      // the test's own: the observer and the holder live on it, never on the Vert.x the stopper closes
    static Pool observer;

    @TempDir Path data;
    String database;
    Main.Started started;
    Witness witness;         // released in the teardown too: a case that fails while Vert.x's close waits on it leaks nothing
    boolean stopped;         // the case ran the stopper to its end; otherwise the teardown closes the node and its Vert.x

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
        database = "main_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
    }

    @AfterEach
    void closeWhatStartLeft() throws Exception {
        if (witness != null) witness.release.tryComplete();
        if (started == null || stopped) return;
        OnVirtualThread.call(() -> {
            try {
                if (started.node().isPresent()) started.node().get().close();
            } finally {
                if (started.vertx().isPresent()) started.vertx().get().close().await();
            }
            return null;
        });
    }

    /** The environment of a node on the case database, data under the case's temporary directory. */
    Map<String, String> env(int adminPort) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", "main-it");
        env.put("FLOTTILA_ADMIN_PORT", String.valueOf(adminPort));
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        return env;
    }

    /** {@code Main.start} on a virtual thread, as {@code run} is called; its stderr lines land in {@code err}. */
    Main.Started start(Map<String, String> env, ByteArrayOutputStream err) throws Exception {
        PrintStream stream = new PrintStream(err, true, StandardCharsets.UTF_8);
        started = OnVirtualThread.call(() -> Main.start(env, stream));
        return started;
    }

    @Test
    void startServesHealthAndItsStopperClosesNodeThenVertx() throws Exception {
        int port = freePort();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        start(env(port), err);
        assertEquals(0, started.code(), "a node that serves answers 0: " + err.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8), "nothing on stderr when the node starts");
        assertTrue(started.node().isPresent() && started.vertx().isPresent(), "start hands back the node and its Vert.x: " + started);
        Node node = started.node().get();
        Vertx main = started.vertx().get();
        assertEquals(port, node.adminPort(), "the node listens on the configured admin port");
        assertEquals(200, NodeIT.get(port, "/health").statusCode(), "/health answers 200 on the configured admin port");

        witness = new Witness();
        main.deployVerticle(witness).await();
        Future<Void> recorded;
        PgConnection holder = PgConnection.connect(vertx, options(database)).await();
        OnVirtualThread.Call<Void> stopping;
        try {
            Transaction held = holder.begin().await();
            // an uncommitted payload row with the snapshot's hash: the snapshot's batch waits on it, inside the node's close
            holder.preparedQuery("insert into payloads(hash, size, algorithm) values ($1, 1, 'sha256-64')").execute(Tuple.of(NodeIT.hashBytes(4711L))).await();
            recorded = node.messages().record(NodeIT.snapshot("m-flushed", 4711L));
            NodeIT.until(() -> lockWaiters() == 1, "the snapshot's batch waits on the held payload row");
            Runnable stopper = Main.stopper(node, main);
            stopping = OnVirtualThread.start(stopper::run);
            NodeIT.until(() -> NodeIT.refuses(port), "the stopper closed the node's admin server: the port refuses");
            assertFalse(stopping.isDone(), "the stopper waits for the node's close: the snapshot's flush is still held");
            assertEquals(1, witness.stopped.getCount(), "Vert.x still runs while the node's close waits for its store: the node closes first");
            held.rollback().await();
        } finally {
            holder.close().await();
        }
        assertTrue(witness.stopped.await(20, TimeUnit.SECONDS), "the stopper closed Vert.x after the node: the witness is being undeployed");
        waitsForVertxClose(stopping);
        OnVirtualThread.join(stopping);
        stopped = true;
        assertEquals(0, witness.stopped.getCount(), "Vert.x is closed by the time the stopper returns: it waits for the close, not only starts it");
        assertTrue(recorded.succeeded(), "the node's close flushed the snapshot recorded before it, on a Vert.x still running: " + recorded.cause());
        NodeIT.until(() -> connections() == 0, "the node's pool is closed: no connection to the case database is left");
    }

    /**
     * The node's close throws when its admin verticle is gone already (Vert.x refuses to undeploy an unknown deployment, the
     * node's close rethrows it): the stopper still closes Vert.x, and the failure is not swallowed.
     */
    @Test
    void theStopperStillClosesVertxWhenTheNodeFailsToClose() throws Exception {
        int port = freePort();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        start(env(port), err);
        assertEquals(0, started.code(), "the node starts: " + err.toString(StandardCharsets.UTF_8));
        Node node = started.node().orElseThrow();
        Vertx main = started.vertx().orElseThrow();
        Set<String> deployed = main.deploymentIDs();
        assertEquals(1, deployed.size(), "the admin server is the started Vert.x's only verticle: " + deployed);
        main.undeploy(deployed.iterator().next()).await();
        NodeIT.until(() -> NodeIT.refuses(port), "the admin verticle is gone under the node");

        witness = new Witness();
        main.deployVerticle(witness).await();
        Runnable stopper = Main.stopper(node, main);
        OnVirtualThread.Call<Void> stopping = OnVirtualThread.start(stopper::run);
        assertTrue(witness.stopped.await(20, TimeUnit.SECONDS), "the stopper closes Vert.x although the node's close threw: the witness is being undeployed");
        waitsForVertxClose(stopping);
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> OnVirtualThread.join(stopping),
                "the node's close failure reaches the stopper's caller (the hook thread reports it)");
        stopped = true;
        assertEquals(0, witness.stopped.getCount(), "Vert.x is closed by the time the stopper returns, although the node's close threw");
        assertEquals("Unknown deployment", failed.getMessage(), "the failure is the node's close undeploying its admin verticle a second time");
    }

    @Test
    void startRefusesABadConfigurationWith2AndStartsNothing() throws Exception {
        Map<String, String> env = env(freePort());
        env.remove("FLOTTILA_ENV");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        start(env, err);
        List<String> lines = err.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(2, started.code(), "a refused configuration answers 2: " + lines);
        assertTrue(started.node().isEmpty() && started.vertx().isEmpty(), "nothing started, nothing to stop: " + started);
        assertEquals(1, lines.size(), "one line on stderr: " + lines);
        assertTrue(lines.get(0).startsWith("flottila: configuration refused: ") && lines.get(0).contains("FLOTTILA_ENV"), lines.get(0));
        assertEquals(0, connections(), "the refused node never connected to its database");
    }

    @Test
    void startAnswers1AndHandsBackNothingWhenTheNodeDoesNotStart() throws Exception {
        Map<String, String> env = env(freePort());
        env.put("FLOTTILA_PG_PORT", String.valueOf(freePort()));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        start(env, err);
        List<String> lines = err.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(1, started.code(), "a node that cannot reach Postgres answers 1: " + lines);
        assertTrue(started.node().isEmpty() && started.vertx().isEmpty(), "the Vert.x start made is closed again and not handed back: " + started);
        assertEquals(1, lines.size(), "one line on stderr, the Flyway message's line breaks folded: " + lines);
        assertTrue(lines.get(0).startsWith("flottila: the node did not start: "), lines.get(0));
    }

    // --- helpers

    /**
     * Marks the moment its Vert.x closes: Vert.x's close undeploys it, which stops it ({@code stopped}). Its stop completes only
     * once the case completes {@code release}, so Vert.x's close cannot complete before: a stopper that only starts the close
     * returns while the stop is pending.
     */
    static final class Witness extends VerticleBase {
        final CountDownLatch stopped = new CountDownLatch(1);
        final Promise<Void> release = Promise.promise();

        @Override
        public Future<?> stop() {
            stopped.countDown();
            return release.future();
        }
    }

    /**
     * With the witness's stop pending (Vert.x's close cannot complete), waits until the stopper has either returned or parked,
     * asserts it has not returned — it waits for Vert.x's close, it does not only start it — and then releases the stop.
     */
    void waitsForVertxClose(OnVirtualThread.Call<Void> stopping) {
        Thread thread = stopping.thread();
        NodeIT.until(() -> stopping.isDone() || (thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING)
                && LockSupport.getBlocker(thread) != null, "the stopper returned or parked after Vert.x's close began");
        assertFalse(stopping.isDone(), "the stopper has not returned while Vert.x's close waits on the witness's stop");
        witness.release.complete();
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.ofLiteral("127.0.0.1"))) {
            return s.getLocalPort();
        }
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
}
