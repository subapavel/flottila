package io.flottila.bench;

import io.vertx.core.json.JsonObject;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15: the ack scenario open loop against an in-process node over a container's Postgres, at a low rate (correctness, no
 * timing threshold): every request sent is accepted (202), the partner's receipts reach the accepted count once the run settled,
 * and the record carries every open field. Without a ramp and a warm-up, the partner receives only the window's messages.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NodeBenchOpenAckIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();

    @TempDir Path dir;

    @BeforeAll static void start() { pg.start(); }

    @AfterAll static void stop() { pg.stop(); }

    @Test
    void anAckOpenLoopAcceptsEverySentRequestAndThePartnerReceivesThemAll() throws Exception {
        String db = database();
        Path out = dir.resolve("open-ack.json");
        int code = NodeBenchMain.run(new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db,
                "--user", pg.getUsername(), "--password", pg.getPassword(), "--scenario", "ack", "--rate", "50", "--ramp", "PT0S",
                "--window", "PT2S", "--warmup", "0", "--concurrency", "4", "--cpu-sampling", "PT0.004S",
                "--name", "it-open-ack", "--out", out.toString(), "--commit", "test"});
        assertEquals(0, code, "the open ack run exits 0");
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals("ack", json.getString("scenario"), json.encode());
        JsonObject open = json.getJsonObject("open");
        assertNotNull(open, "the record carries the open object: " + json.encode());
        NodeBenchOpenEchoIT.assertFields(open);

        assertEquals(100, open.getNumber("scheduled").longValue(), "50/s × 2 s: " + open.encode());
        assertEquals(100, open.getNumber("sent").longValue(), open.encode());
        assertEquals(open.getNumber("sent").longValue(), open.getNumber("accepted").longValue(), "every request sent got its 202: " + open.encode());
        assertEquals(0, open.getNumber("refused503").longValue(), open.encode());
        assertEquals(0, open.getNumber("errors").longValue(), open.encode());
        assertEquals(0, open.getNumber("timeouts").longValue(), open.encode());
        assertEquals(0, open.getNumber("clientSaturated").longValue(), open.encode());
        assertEquals(open.getNumber("accepted").longValue(), open.getNumber("receipts").longValue(),
                "the partner received every accepted message once the run settled: " + open.encode());
        assertEquals(3, open.getJsonArray("backlog").size(), "the window's start, 1 s, its end (2 s): " + open.encode());

        JsonObject cpu = open.getJsonObject("cpuMs");
        assertTrue(cpu.getNumber("bench").doubleValue() > 0, open.encode());
        assertNotNull(cpu.getValue("node"), "sampling on: the node's CPU from its cpu endpoint: " + open.encode());
        assertTrue(cpu.getNumber("node").doubleValue() >= 0, open.encode());
        assertEquals(null, cpu.getValue("postgres"), "no --pg-cpu-source: null, never 0: " + open.encode());
        assertTrue(!open.getJsonObject("cpuNotes").getString("postgres", "").isBlank(), open.encode());

        assertEquals(100, count(db, "select count(distinct id) from messages"), "the node took and recorded every message of the run");
    }

    /** A fresh database for the case (its name contains "bench": the run truncates it). */
    static String database() throws SQLException {
        String db = "openbench_" + cases.incrementAndGet();
        try (Connection c = connect(pg.getDatabaseName()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + db + "\"");
        }
        return db;
    }

    static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }

    static long count(String db, String query) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            assertTrue(r.next(), query);
            return r.getLong(1);
        }
    }
}
