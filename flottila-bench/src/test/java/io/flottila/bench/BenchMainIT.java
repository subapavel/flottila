package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The harness end to end on a container (numbers are not recorded from here — only native runs count, CLAUDE.md). */
class BenchMainIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");

    @BeforeAll static void start() { pg.start(); }

    @AfterAll static void stop() { pg.stop(); }

    @Test
    void runsMigratesWritesEveryMessageAndReportsItsJson() throws Exception {
        Path out = Files.createTempDirectory("bench").resolve("it.json");
        int code = BenchMain.run(new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)),
                "--db", pg.getDatabaseName(), "--user", pg.getUsername(), "--password", pg.getPassword(),
                "--messages", "500", "--warmup", "50", "--concurrency", "8", "--batch-size", "50", "--window-ms", "5",
                "--commit-concurrency", "2", "--sync-commit", "off", "--name", "it", "--out", out.toString(), "--commit", "test"});
        assertEquals(0, code);
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(500, json.getInteger("saved"));
        assertEquals(0, json.getInteger("failed"));
        assertEquals(500L, json.getLong("rowsInMessages"));
        assertEquals("6", json.getString("schemaVersion"), "V1 m1 schema + V2 snapshot columns (P1-04) + V3 DLQ by reference (P1-05) + V4 work attempts (P1-08b) + V5 admin paging (P1-09a) + V6 bodies (P1-09h)");
        assertEquals(false, json.getBoolean("syncCommit"), "observed with show synchronous_commit on a pooled connection, not echoed from the flag");
        assertTrue(json.getString("postgres").startsWith("17."), "server_version of the container: " + json.getString("postgres"));
        assertEquals("test", json.getString("commit"));
        assertTrue(json.getDouble("p99Ms") >= json.getDouble("p50Ms"));
        assertTrue(json.getDouble("throughputPerSecond") > 0);
        assertTrue(json.getJsonObject("machine").getInteger("cpus") > 0);
    }

    @Test
    void aSecondRunOnTheSameDatabaseStartsFromEmptyTables() throws Exception {
        Path out = Files.createTempDirectory("bench").resolve("again.json");
        int code = BenchMain.run(new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)),
                "--db", pg.getDatabaseName(), "--user", pg.getUsername(), "--password", pg.getPassword(),
                "--messages", "100", "--warmup", "0", "--concurrency", "4", "--name", "again", "--out", out.toString()});
        assertEquals(0, code);
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(100L, json.getLong("rowsInMessages"), "truncated before the run, no leftovers");
        assertEquals(true, json.getBoolean("syncCommit"), "default is synchronous_commit=on, observed on the server");
    }
}
