package io.flottila.bench;

import io.vertx.core.json.JsonArray;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b (F-27 I1): an ack open loop at a low rate against an in-process node over a container's Postgres with
 * pg_stat_statements preloaded, {@code fsync} on with {@code wal_sync_method=fsync} (pg_stat_wal.wal_sync counts only then) and
 * {@code track_wal_io_timing} on. The record's {@code db} object carries one non-null entry per backlog sample for the commits
 * and the WAL syncs, summing at least to one commit per intake group of the accepted messages (a group holds at most 64, the
 * node's default, NodeConfig.IntakeGroup.defaults); the work queue's CLAIM and COMPLETE are among the top statements, and the
 * sampler's own reads are not. A database without the extension records the statements as null with a note, never as none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NodeBenchOpenDbSeriesIT {

    /** The most messages one intake group commits together (NodeConfig.IntakeGroup.defaults: 64). */
    static final int GROUP_MAX = 64;

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17").withCommand("postgres", "-c", "fsync=on",
            "-c", "wal_sync_method=fsync", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "track_wal_io_timing=on");
    static final AtomicInteger cases = new AtomicInteger();

    @TempDir Path dir;

    @BeforeAll static void start() { pg.start(); }

    @AfterAll static void stop() { pg.stop(); }

    @Test
    void anAckOpenLoopRecordsCommitAndWalSyncDeltasPerSampleAndTheClaimAndCompleteAmongTheTopStatements() throws Exception {
        String db = database(true);
        JsonObject open = run(db, "it-open-db");

        JsonObject series = open.getJsonObject("db");
        assertNotNull(series, "the open record carries the db object: " + open.encode());
        int samples = open.getJsonArray("backlog").size();
        assertEquals(4, samples, "the window's start, 1 s, 2 s and its end (3 s): " + open.encode());
        long accepted = open.getLong("accepted");
        assertTrue(accepted > 0, open.encode());
        double floor = accepted / (double) GROUP_MAX;

        long commits = sum(series, "xactCommit", samples);
        assertTrue(commits >= floor, "Δxact_commit over the window " + commits + " ≥ " + accepted + " accepted / " + GROUP_MAX + ": "
                + series.encode());
        long syncs = sum(series, "walSync", samples);
        assertTrue(syncs >= floor, "Δwal_sync over the window " + syncs + " ≥ " + accepted + " accepted / " + GROUP_MAX + ": "
                + series.encode());
        assertNotNull(series.getValue("walSyncTimeMs"), "track_wal_io_timing on: the sync time is measured: " + series.encode());

        JsonArray top = series.getJsonArray("statements");
        assertNotNull(top, "pg_stat_statements installed: the statements are measured: " + series.encode());
        assertTrue(top.size() <= 12, "at most the top 12: " + top.encode());
        List<String> queries = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) queries.add(top.getJsonObject(i).getString("query").toLowerCase(Locale.ROOT));
        assertTrue(queries.stream().anyMatch(q -> q.contains("update work w set locked_by")), "the work queue's CLAIM: " + queries);
        assertTrue(queries.stream().anyMatch(q -> q.startsWith("delete from work")), "the work queue's COMPLETE: " + queries);
        assertTrue(queries.stream().noneMatch(q -> q.contains("pg_stat_statements") || q.contains("pg_stat_wal")
                || q.contains("pg_stat_database") || q.contains("pg_stat_user_tables")), "the node's statements, not the sampler's: " + queries);
    }

    @Test
    void aDatabaseWithoutPgStatStatementsRecordsTheStatementsAsNullWithANote() throws Exception {
        String db = database(false);
        JsonObject open = run(db, "it-open-db-noext");

        JsonObject series = open.getJsonObject("db");
        assertNotNull(series, open.encode());
        assertTrue(series.containsKey("statements"), "the field is in the record: " + series.encode());
        assertNull(series.getValue("statements"), "unknown, never an empty list or 0: " + series.encode());
        JsonObject notes = series.getJsonObject("notes");
        assertNotNull(notes, series.encode());
        assertTrue(notes.getString("statements", "").contains("pg_stat_statements"), "the note names the extension: " + series.encode());
        assertTrue(sum(series, "xactCommit", open.getJsonArray("backlog").size()) > 0, "the rest is still measured: " + series.encode());
    }

    // --- fixtures

    /** The open ack run at 50/s for 3 s, no ramp, no warm-up, on {@code db}; asserts exit 0 and returns its open object. */
    JsonObject run(String db, String name) throws Exception {
        Path out = dir.resolve(name + ".json");
        int code = NodeBenchMain.run(new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db,
                "--user", pg.getUsername(), "--password", pg.getPassword(), "--scenario", "ack", "--rate", "50", "--ramp", "PT0S",
                "--window", "PT3S", "--warmup", "0", "--concurrency", "4", "--name", name, "--out", out.toString(), "--commit", "test"});
        assertEquals(0, code, "the open ack run exits 0");
        JsonObject open = new JsonObject(Files.readString(out)).getJsonObject("open");
        assertNotNull(open, "the record carries the open object");
        return open;
    }

    /** {@code field}'s series: {@code samples} entries, none null; their sum. */
    static long sum(JsonObject series, String field, int samples) {
        JsonArray a = series.getJsonArray(field);
        assertNotNull(a, field + " is measured: " + series.encode());
        assertEquals(samples, a.size(), field + " has one entry per backlog sample: " + series.encode());
        long sum = 0;
        for (int i = 0; i < a.size(); i++) {
            assertNotNull(a.getValue(i), field + "[" + i + "] is known: " + series.encode());
            sum += ((Number) a.getValue(i)).longValue();
        }
        return sum;
    }

    /** A fresh database for the case (its name contains "bench": the run truncates it), with the extension or without. */
    static String database(boolean statements) throws SQLException {
        String db = "dbseries_bench_" + cases.incrementAndGet();
        try (Connection c = connect(pg.getDatabaseName()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + db + "\"");
        }
        if (statements) {
            try (Connection c = connect(db); Statement s = c.createStatement()) {
                s.execute("CREATE EXTENSION pg_stat_statements");
            }
        }
        return db;
    }

    static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }

    @Test
    void theContainerHasTheExtensionPreloadedAndTheTimingOn() throws Exception {
        // the fixture's self-test: a red above is the bench's, not a container without the extension
        try (Connection c = connect(pg.getDatabaseName()); Statement s = c.createStatement();
             var r = s.executeQuery("select current_setting('shared_preload_libraries'), current_setting('track_wal_io_timing'),"
                     + " current_setting('fsync')")) {
            assertTrue(r.next());
            assertTrue(r.getString(1).contains("pg_stat_statements"), r.getString(1));
            assertEquals("on", r.getString(2));
            assertEquals("on", r.getString(3));
        }
    }
}
