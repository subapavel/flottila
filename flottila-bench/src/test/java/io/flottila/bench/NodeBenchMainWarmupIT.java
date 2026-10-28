package io.flottila.bench;

import io.flottila.core.queue.WorkQueue;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.queue.PgWorkQueue;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node bench's own cases on a container (P1-10a), all on the database {@code warmupbench}: a run with a warmup counts only
 * its measured messages and a second run starts from empty tables; the worker record's distribution is the claim → complete
 * the triggers logged, beside the ingress' own window and the drain's tail; a sync window's rates hold its recording, which
 * lands after the replies; the claim trigger logs claims, never renewals; and a window that does not land in time fails.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NodeBenchMainWarmupIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final String DB = "warmupbench";

    @TempDir Path dir;

    @BeforeAll
    static void start() throws SQLException {
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute("create database " + DB);
        }
    }

    @AfterAll static void stop() { pg.stop(); }

    @Test
    void theWarmupRunsThroughTheNodeAndTheWorkerRecordIsTheLoggedClaimToComplete() throws Exception {
        Path out = dir.resolve("worker.json");
        assertEquals(0, NodeBenchMain.run(args("worker", 7, out)));
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(12, json.getInteger("messages"));
        assertEquals(7, json.getInteger("warmup"));
        assertEquals(0, json.getInteger("failed"), json.encode());
        assertEquals(19, count("select count(distinct id) from messages"), "the warmup's messages went through the node too");
        assertEquals(0, count("select count(*) from work"));
        assertEquals(2, count("select count(*) from pg_trigger where tgname in ('bench_claim', 'bench_complete')"),
                "the worker run installed its triggers");

        // the distribution is each measured item's last claim → its complete as the triggers logged them, not the ingress'
        assertEquals(12, count("select count(*) from bench_work_log where event = 'delete'"), "the log holds the measured window alone");
        List<Double> logged = doubles("select max(ms), avg(ms) from (select extract(epoch from d.at - (select max(c.at) from bench_work_log c"
                + " where c.seq = d.seq and c.event = 'update')) * 1000 as ms from bench_work_log d where d.event = 'delete') t");
        assertEquals(logged.get(0), json.getDouble("maxMs"), 1e-6, json.encode());
        assertEquals(logged.get(1), json.getDouble("meanMs"), 1e-6, json.encode());

        // the ingress' own window and the drain's tail: the record's window is at least both
        JsonObject ingress = json.getJsonObject("ingress");
        assertNotNull(ingress, json.encode());
        assertEquals(12, ingress.getInteger("messages"));
        assertEquals(0, ingress.getInteger("failed"));
        assertTrue(ingress.getDouble("p50Ms") > 0, ingress.encode());
        double tail = json.getDouble("drainTailMs");
        assertTrue(tail > 0, "the queue is empty only after the last 202: " + json.encode());
        assertTrue(json.getDouble("elapsedMs") >= ingress.getDouble("elapsedMs") + tail - 1e-6,
                "the window runs from the first request until the queue is empty: " + json.encode());

        // the next run on the same database: its tables start empty, and an ack run drops the worker's triggers
        Path ack = dir.resolve("ack.json");
        assertEquals(0, NodeBenchMain.run(args("ack", 0, ack)));
        assertEquals(12, count("select count(distinct id) from messages"), "the previous run's rows were truncated");
        assertEquals(0, count("select count(*) from work"), "the ack run waited for its items before it stopped the node");
        assertEquals(0, count("select count(*) from pg_trigger where tgname in ('bench_claim', 'bench_complete')"),
                "a run that is not the worker leaves no trigger on work");
    }

    @Test
    void aSyncWindowsBodiesRateHoldsTheRecordingThatLandsAfterTheReplies() throws Exception {
        Path out = dir.resolve("sync-xslt.json");
        assertEquals(0, NodeBenchMain.run(args("sync", 0, out, "--step", "xslt")));
        JsonObject json = new JsonObject(Files.readString(out));
        double delta = json.getDouble("bodiesBytesPerSecond") * json.getDouble("elapsedMs") / 1000.0;
        long recorded = count("select coalesce(sum(octet_length(bytes)), 0) from bodies");
        assertTrue(recorded >= 12 * NodeBenchMain.XSLT_BODY_BYTES, "every message's ~100 KB body was kept: " + recorded);
        assertTrue(delta >= recorded, "the bodies delta " + delta + " B holds the " + recorded + " B the window's messages recorded: " + json.encode());
    }

    @Test
    void theClaimTriggerLogsAClaimAndAComplete() throws Exception {
        Migrations.migrate(jdbcUrl(), pg.getUsername(), pg.getPassword());
        Vertx vertx = Vertx.vertx();
        try {
            Pool pool = pool(vertx);
            pool.query(NodeBenchMain.PREPARE + NodeBenchMain.WORK_LOG_TRIGGERS).execute().await();
            WorkQueue queue = new PgWorkQueue(pool);
            queue.enqueue("m-1", "bench/bench", 1, null).await();
            WorkQueue.Item item = queue.claim("w", "bench/bench", 1, Duration.ofSeconds(30)).await().getFirst();
            assertTrue(queue.renew(item.seq(), item.attempts(), Duration.ofSeconds(30)).await(), "fixture: the lease is renewed");
            queue.complete(item.seq()).await();
            assertEquals(1, count("select count(*) from bench_work_log where event = 'update' and seq = " + item.seq()),
                    "the claim is logged, the renewal is not");
            assertEquals(1, count("select count(*) from bench_work_log where event = 'delete' and seq = " + item.seq()));
        } finally {
            vertx.close().await();
        }
    }

    @Test
    void aWindowThatDoesNotLandInTimeFailsTheRun() throws Exception {
        Migrations.migrate(jdbcUrl(), pg.getUsername(), pg.getPassword());
        sql("delete from messages");
        sql("insert into work(id, flow, step) values ('stuck', 'bench/bench', 1)");
        Vertx vertx = Vertx.vertx();
        try {
            NodeBenchMain.Harness h = new NodeBenchMain.Harness(NodeBenchMain.options(new String[] {"--scenario", "worker"}), vertx, pool(vertx), null,
                    null, 0, 0);
            IllegalStateException stuck = assertThrows(IllegalStateException.class, () -> h.settle(0, Duration.ofMillis(20)));
            assertEquals("0 recorded messages and an empty work queue did not land within PT0.02S", stuck.getMessage());
            sql("delete from work");
            assertThrows(IllegalStateException.class, () -> h.settle(1, Duration.ofMillis(20)), "the queue is empty, no message is recorded");
            h.settle(0, Duration.ZERO);   // what landed is not waited for, whatever the bound
        } finally {
            vertx.close().await();
        }
    }

    // --- fixtures

    static String[] args(String scenario, int warmup, Path out, String... extra) {
        List<String> a = new java.util.ArrayList<>(List.of("--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", DB,
                "--user", pg.getUsername(), "--password", pg.getPassword(), "--scenario", scenario, "--messages", "12",
                "--warmup", String.valueOf(warmup), "--concurrency", "3", "--name", "warmup-" + scenario, "--out", out.toString()));
        a.addAll(List.of(extra));
        return a.toArray(String[]::new);
    }

    static String jdbcUrl() {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + DB;
    }

    static Pool pool(Vertx vertx) {
        return PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(DB)
                .setUser(pg.getUsername()).setPassword(pg.getPassword())).using(vertx).build();
    }

    static void sql(String statement) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    static long count(String query) throws SQLException {
        return doubles(query).getFirst().longValue();
    }

    /** The first row's columns as doubles. */
    static List<Double> doubles(String query) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            assertTrue(r.next(), query);
            List<Double> row = new java.util.ArrayList<>();
            for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) row.add(r.getDouble(i));
            return row;
        }
    }
}
