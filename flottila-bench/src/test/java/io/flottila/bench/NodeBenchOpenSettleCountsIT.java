package io.flottila.bench;

import io.flottila.runtime.db.Migrations;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-124: the run's own probe ({@code Harness.probe}) on a database whose settle cannot land — fewer recorded messages than
 * the target and items left in the work queue — returns the open record unsettled with the counts the database holds when
 * the wait returns: {@code messages} the distinct recorded messages (not their step rows), {@code work} the queue's items.
 * The bound is zero, so the wait returns after its first poll.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NodeBenchOpenSettleCountsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");

    @BeforeAll static void start() { pg.start(); }

    @AfterAll static void stop() { pg.stop(); }

    @Test
    void anUnsettledOpenSettleRecordsTheDistinctMessagesAndTheWorkItemsTheDatabaseHolds() throws Exception {
        String db = "countsbench";
        try (Connection c = connect(pg.getDatabaseName()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + db);
        }
        Migrations.migrate("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db, pg.getUsername(), pg.getPassword());
        try (Connection c = connect(db); Statement s = c.createStatement()) {
            // three messages, one of them with a second step row; two items in the work queue
            s.execute("insert into messages (id, step, flow, body_hash) values ('m1', 0, 'bench/bench', '\\x00'), ('m1', 1, 'bench/bench', '\\x00'),"
                    + " ('m2', 0, 'bench/bench', '\\x00'), ('m3', 0, 'bench/bench', '\\x00')");
            s.execute("insert into work (id, flow, step) values ('m2', 'bench/bench', 1), ('m3', 'bench/bench', 1)");
        }
        NodeBenchMain.Options o = NodeBenchMain.options(new String[] {"--scenario", "ack", "--rate", "50", "--host", pg.getHost(),
                "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db, "--user", pg.getUsername(), "--password", pg.getPassword()});
        // Future.await needs a virtual thread: the whole harness runs on one
        FutureTask<JsonObject> settle = new FutureTask<>(() -> {
            Vertx vertx = Vertx.vertx();
            Pool pool = NodeBenchMain.pool(vertx, o, 1);
            try {
                NodeBenchMain.Harness h = new NodeBenchMain.Harness(o, vertx, pool, null, null, 0, 0);
                return h.settleOpen(new JsonObject(), 10, 0, Duration.ZERO, h.probe(0), () -> 7);
            } finally {
                pool.close().await();
                vertx.close().await();
            }
        });
        Thread.ofVirtual().start(settle).join();
        JsonObject open = settle.get();

        assertEquals(Boolean.FALSE, open.getBoolean("settled"), open.encode());
        assertEquals(3L, open.getLong("messages"), "the distinct messages, not their 4 step rows: " + open.encode());
        assertEquals(2L, open.getLong("work"), "the work queue's items: " + open.encode());
        assertEquals(7L, open.getLong("receipts"), open.encode());
    }

    static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }
}
