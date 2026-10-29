package io.flottila.bench;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-08b: how the ack record's per-message counters are read. Postgres (15+) publishes a backend's counts at most once a second
 * while it is busy and 10 s after it went idle, so the read waits {@link NodeBenchMain#STATS_FLUSH} first; and the record holds
 * the window's delta, not what the database counted before it.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NodeBenchAckCountersIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static final int MESSAGES = 40;

    @TempDir Path dir;

    @BeforeAll static void start() { pg.start(); }

    @AfterAll static void stop() { pg.stop(); }

    @Test
    void aReadOfTheCountersSeesTheCommitsOfABackendThatWentIdleWithItsCountsUnpublished() throws Exception {
        String db = database();
        Vertx vertx = Vertx.vertx();
        String[] args = {"--scenario", "ack", "--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db,
                "--user", pg.getUsername(), "--password", pg.getPassword()};
        NodeBenchMain.Options o = NodeBenchMain.options(args);
        Pool pool = NodeBenchMain.pool(vertx, o, 1);
        NodeBenchMain.Harness h = new NodeBenchMain.Harness(o, vertx, pool, null, null, 0, 0);
        try (Connection idle = connect(db)) {
            try (Statement s = idle.createStatement()) {
                s.execute("create table burst (x int)");
            }
            NodeBenchMain.Counters before = onVirtualThread(h::counters);
            // a burst well inside a second: the backend publishes at its first commit, the rest wait for its idle flush (a commit
            // that touched no relation leaves nothing pending and waits for a later one: the burst writes a table)
            try (Statement s = idle.createStatement()) {
                for (int i = 0; i < 50; i++) s.execute("insert into burst values (" + i + ")");
            }
            NodeBenchMain.Counters after = onVirtualThread(h::counters);   // the connection stays open: no publish at its exit
            assertTrue(after.commits() - before.commits() >= 50,
                    "the 50 commits of the idle backend are counted: " + before + " → " + after);
        } finally {
            onVirtualThread(() -> {
                pool.close().await();
                return vertx.close().await();
            });
        }
    }

    @Test
    void theRecordHoldsTheWindowsCommitsNotTheDatabasesTotal() throws Exception {
        String db = database();
        try (Connection c = connect(db); Statement s = c.createStatement()) {
            s.execute("create schema earlier");   // not public: Flyway migrates an empty public schema only
            s.execute("create table earlier.t (x int)");
            for (int i = 0; i < 5000; i++) s.execute("insert into earlier.t values (" + i + ")");   // published at the backend's exit
        }
        Path out = dir.resolve("ack.json");
        String[] args = {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db, "--user", pg.getUsername(),
                "--password", pg.getPassword(), "--scenario", "ack", "--messages", String.valueOf(MESSAGES), "--warmup", "0",
                "--concurrency", "1", "--name", "it-delta", "--out", out.toString(), "--commit", "test"};
        assertEquals(0, NodeBenchMain.run(args));
        JsonObject json = new JsonObject(Files.readString(out));
        double commits = json.getDouble("commitsPerMessage");
        // 5000 earlier commits over 40 messages would add 125 a message; the window's own cost is a few commits a message plus
        // the polls of the wait before the second read
        assertTrue(commits >= 1.0 && commits < 60, "the window's delta, not the database's total: " + json.encode());
    }

    // --- fixtures

    static <T> T onVirtualThread(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Thread.ofVirtual().start(task).join();
        return task.get();
    }

    static String database() throws SQLException {
        String db = "ackcounters_bench_" + cases.incrementAndGet();
        try (Connection c = connect(pg.getDatabaseName()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + db + "\"");
        }
        return db;
    }

    static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }
}
