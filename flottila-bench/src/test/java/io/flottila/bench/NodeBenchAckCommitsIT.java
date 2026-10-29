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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-08b: the ack record names the server's commit settings, the node's pool and what one message costs the database
 * ({@code commitsPerMessage} = Δ pg_stat_database.xact_commit / messages, {@code walSyncsPerMessage} = Δ pg_stat_wal.wal_sync /
 * messages). The container runs with settings that are none of Postgres' defaults nor Testcontainers' ({@code fsync=off}), so a
 * record that carries them read them from the server; {@code fsync} on with {@code wal_sync_method=fsync} is what makes
 * {@code pg_stat_wal.wal_sync} count at all (it stays 0 under fsync off or an open_* method). The twin on the default container
 * is {@code NodeBenchMainIT.anAckRunRecordsTheCommitSettingsAsTheServerShowsThem}.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NodeBenchAckCommitsIT {

    /** The server-wide settings of this class' container, as pg_settings shows them. */
    static final Map<String, String> SERVER = Map.of("fsync", "on", "wal_sync_method", "fsync", "full_page_writes", "off",
            "commit_delay", "7", "commit_siblings", "3", "synchronous_commit", "off");

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17").withCommand("postgres",
            "-c", "fsync=on", "-c", "wal_sync_method=fsync", "-c", "full_page_writes=off",
            "-c", "commit_delay=7", "-c", "commit_siblings=3", "-c", "synchronous_commit=off");
    static final AtomicInteger cases = new AtomicInteger();
    static final int MESSAGES = 40;

    @TempDir Path dir;

    @BeforeAll static void start() { pg.start(); }

    @AfterAll static void stop() { pg.stop(); }

    @Test
    void theAckRecordCarriesTheServersCommitSettingsReadFromPgSettingsAndNeverSetsCommitDelay() throws Exception {
        String db = database();
        assertEquals("7", text(db, "select setting from pg_settings where name = 'commit_delay'"), "the container's commit_delay before the run");
        JsonObject json = ack(db, 4, "settings");

        // the bench sets synchronous_commit on for the database (D-061): the record shows what the node's sessions commit with
        assertEquals("on", setting(json, "synchronous_commit"), "synchronous_commit as the node's sessions see it, not the server default off: " + json.encode());
        // commit_delay 7 µs survives the run: NodeBenchMain never sets it (the runner does, ALTER SYSTEM), at no level a session sees
        assertEquals("7", setting(json, "commit_delay"), "commit_delay as the server has it: " + json.encode());
        assertEquals("7", text(db, "select setting from pg_settings where name = 'commit_delay'"), "a new session after the run still has commit_delay 7");
        for (String name : List.of("commit_siblings", "wal_sync_method", "fsync", "full_page_writes")) {
            assertEquals(SERVER.get(name), setting(json, name), name + " as pg_settings shows it, not a default: " + json.encode());
        }
        assertEquals(16, json.getInteger("poolSize"), "the node's Postgres pool (FLOTTILA_PG_POOL_SIZE default; the bench does not set it): " + json.encode());
    }

    @Test
    void anAtLeastOnceAckCostsAtLeastOneCommitPerMessageAndItsWalSyncsAreCountedPerMessage() throws Exception {
        String db = database();
        JsonObject json = ack(db, 1, "commits");
        assertEquals(MESSAGES, count(db, "select count(distinct message_id) from bodies where step = 0"), "every 202 saved its ingress body");

        Double commits = json.getDouble("commitsPerMessage");
        assertNotNull(commits, "the ack record carries commitsPerMessage: " + json.encode());
        // database-wide Δ xact_commit (the worker, the recording and read-only polls run beside the ingress): only a lower bound
        // holds; P2-08a's one-transaction save shows as the before/after difference of the measured cells, not here
        assertTrue(commits >= 1.0, "each 202 is at least one durable commit: commitsPerMessage " + commits);

        Double walSyncs = json.getDouble("walSyncsPerMessage");
        assertNotNull(walSyncs, "the ack record carries walSyncsPerMessage: " + json.encode());
        assertTrue(walSyncs > 0, "synchronous_commit on, fsync on, wal_sync_method fsync: the window's commits synced the WAL: " + walSyncs);
        // per message, not the window's total: 40 durable ACKs at c=1 total at least 40 syncs
        assertTrue(walSyncs < MESSAGES, "walSyncsPerMessage is divided by the messages: " + walSyncs);
    }

    // --- fixtures

    /** One ack run of {@link #MESSAGES} at {@code concurrency} on {@code db}; exits 0, nothing failed; its record. */
    JsonObject ack(String db, int concurrency, String name) throws Exception {
        Path out = dir.resolve(name + ".json");
        String[] args = {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db, "--user", pg.getUsername(),
                "--password", pg.getPassword(), "--scenario", "ack", "--messages", String.valueOf(MESSAGES), "--warmup", "0",
                "--concurrency", String.valueOf(concurrency), "--name", "it-" + name, "--out", out.toString(), "--commit", "test"};
        assertEquals(0, NodeBenchMain.run(args), "the ack run exits 0");
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(0, json.getInteger("failed"), json.encode());
        assertEquals(MESSAGES, json.getInteger("messages"), json.encode());
        return json;
    }

    /** A setting in the record as pg_settings writes it (text: "on", "7"); a number the record keeps as a number reads the same. */
    static String setting(JsonObject json, String name) {
        return String.valueOf(json.getValue(name));
    }

    static String database() throws SQLException {
        String db = "ackcommits_bench_" + cases.incrementAndGet();
        try (Connection c = connect(pg.getDatabaseName()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + db + "\"");
        }
        return db;
    }

    static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }

    static String text(String db, String query) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            assertTrue(r.next(), query);
            return r.getString(1);
        }
    }

    static long count(String db, String query) throws SQLException {
        return Long.parseLong(text(db, query));
    }
}
