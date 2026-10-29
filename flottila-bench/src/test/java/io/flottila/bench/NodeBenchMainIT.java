package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node bench harness (P1-10a) end to end on a container: an in-process node over Postgres with the bench package, a local
 * echo as the partner, one JSON per scenario. Correctness only — small counts, no timing thresholds; numbers are recorded from
 * native runs only (D-036). Every case runs on its own database, so the rows a case counts are the rows its run wrote.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NodeBenchMainIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static final int MESSAGES = 40;

    /** The latency and throughput fields of BenchRun's record, which every distribution of the node bench carries too. */
    static final List<String> DISTRIBUTION = List.of("p50Ms", "p95Ms", "p99Ms", "maxMs", "meanMs", "throughputPerSecond");

    @TempDir Path dir;

    @BeforeAll static void start() { pg.start(); }

    @AfterAll static void stop() { pg.stop(); }

    @ParameterizedTest
    @ValueSource(strings = {"sync", "ack", "worker"})
    void eachScenarioWritesItsJsonWithTheNamedFields(String scenario) throws Exception {
        String db = database();
        Path out = dir.resolve(scenario + ".json");
        int code = NodeBenchMain.run(args(db, scenario, "keep", 4, out));
        assertEquals(0, code, "the " + scenario + " run exits 0");
        assertTrue(Files.exists(out), "the run wrote its JSON to --out: " + out);
        JsonObject json = new JsonObject(Files.readString(out));

        assertEquals("it-" + scenario, json.getString("name"));
        assertEquals(scenario, json.getString("scenario"), "the record names its scenario as the CLI does");
        assertEquals("keep", json.getString("recording"), "the record names its recording as the CLI does");
        assertEquals("passthrough", json.getString("step"), "the step defaults to passthrough");
        assertEquals(MESSAGES, json.getInteger("messages"));
        assertEquals(0, json.getInteger("warmup"));
        assertEquals(4, json.getInteger("concurrency"));
        assertEquals(0, json.getInteger("failed"), "no message failed: " + json.encode());
        assertEquals(0, json.getInteger("refused"), "the record counts the 503 refusals (FIX-67): " + json.encode());
        assertEquals(0, json.getInteger("errors"), "the record counts the other failures apart (FIX-67): " + json.encode());
        assertEquals(0, json.getInteger("warmupFailed"), "the record counts the warm-up's failures (FIX-67/FIX-63): " + json.encode());
        assertEquals(scenario.equals("sync") ? 64 * 4 : 4, json.getInteger("maxInFlight"), "the default flow limit: " + json.encode());
        assertEquals(true, json.getBoolean("syncCommit"), "observed on the server: " + json.encode());
        assertEquals("test", json.getString("commit"));
        assertNotNull(json.getString("date"), "the record carries its date");
        assertTrue(json.getString("postgres").startsWith("17."), "server_version of the container: " + json.getString("postgres"));
        JsonObject machine = json.getJsonObject("machine");
        assertNotNull(machine, "the record carries the machine: " + json.encode());
        assertEquals(BenchMain.machine().getString("label"), machine.getString("label"), "the machine label picks the baseline (D-037)");
        assertTrue(machine.getInteger("cpus") > 0);

        assertDistribution(json, scenario);
        assertTrue(json.getDouble("elapsedMs") > 0, "elapsedMs: " + json.encode());
        for (String rate : List.of("walBytesPerSecond", "bodiesBytesPerSecond", "bodiesGrowthPerHour")) {
            assertNotNull(json.getDouble(rate), scenario + " records " + rate + ": " + json.encode());
            assertTrue(json.getDouble(rate) >= 0, rate + " is a delta of a growing quantity: " + json.getDouble(rate));
        }
        if (scenario.equals("sync")) {
            assertNotNull(json.getJsonObject("raw"), "the sync record carries the raw baseline: " + json.encode());
            assertDistribution(json.getJsonObject("raw"), "sync raw");
            assertNotNull(json.getDouble("overheadP50Ms"), "the sync record carries overheadP50Ms: " + json.encode());
        }

        // what the run did, seen in the node's database rather than in the record
        assertEquals(MESSAGES, count(db, "select count(distinct id) from messages"),
                "every measured " + scenario + " message went through the node and was recorded");
        switch (scenario) {
            case "ack" -> assertEquals(MESSAGES, count(db, "select count(distinct message_id) from bodies where step = 0"),
                    "every 202 came after the durable save of the ingress body (D-061)");
            case "worker" -> assertEquals(0, count(db, "select count(*) from work"),
                    "every queued item was claimed and completed: no work row is left");
            default -> { }
        }
    }

    @Test
    void syncRecordsTheRawBaselineAndTheOverhead() throws Exception {
        String db = database();
        Path out = dir.resolve("sync.json");
        assertEquals(0, NodeBenchMain.run(args(db, "sync", "keep", 4, out)));
        JsonObject json = new JsonObject(Files.readString(out));
        JsonObject raw = json.getJsonObject("raw");
        assertNotNull(raw, "the sync record carries the raw baseline's distribution under \"raw\": " + json.encode());
        assertDistribution(raw, "raw");
        assertEquals(MESSAGES, raw.getInteger("messages"), "the raw baseline sent as many requests as the flow: " + raw.encode());
        assertEquals(0, raw.getInteger("failed"), "every raw request was answered by the echo: " + raw.encode());

        assertEquals(json.getDouble("p50Ms") - raw.getDouble("p50Ms"), json.getDouble("overheadP50Ms"), 1e-9,
                "overheadP50Ms = p50(sync) - p50(raw): " + json.encode());
        List<Double> flow = DISTRIBUTION.stream().map(json::getDouble).toList();
        List<Double> baseline = DISTRIBUTION.stream().map(raw::getDouble).toList();
        assertNotEquals(flow, baseline, "the raw distribution is its own measurement, not a copy of the flow's");

        assertEquals(MESSAGES, count(db, "select count(distinct id) from messages"),
                "the flow's requests went through the node, the raw baseline's went straight to the echo (else " + 2 * MESSAGES + ")");
    }

    @Test
    void aSyncRunWithAnExplicitMaxInFlightRecordsItAndTheRawBaselinesCounts() throws Exception {
        String db = database();
        Path out = dir.resolve("sync-limited.json");
        // 100 is not the default (64 x 2 = 128): the record shows the option, not the default
        assertEquals(0, NodeBenchMain.run(args(db, "sync", "metadata-only", 2, out, "--max-in-flight", "100")));
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(100, json.getInteger("maxInFlight"), "the record carries the run's --max-in-flight: " + json.encode());
        JsonObject raw = json.getJsonObject("raw");
        assertEquals(0, raw.getInteger("refused"), "the raw baseline counts refusals too: " + raw.encode());
        assertEquals(0, raw.getInteger("errors"), raw.encode());
        assertEquals(MESSAGES, count(db, "select count(distinct id) from messages"), "the run went through the node");
    }

    @Test
    void ackRunsWithSynchronousCommitOn() throws Exception {
        String db = database();
        // the database's default is off: a record that says on was observed on the node's sessions, and a node over off refuses to start (D-061)
        sql(pg.getDatabaseName(), "ALTER DATABASE \"" + db + "\" SET synchronous_commit = off");
        assertEquals("off", text(db, "show synchronous_commit"), "a new session on the case database commits asynchronously before the run");

        Path out = dir.resolve("ack.json");
        assertEquals(0, NodeBenchMain.run(args(db, "ack", "keep", 8, out)), "the ack scenario runs with synchronous_commit on whatever the database default");
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals("ack", json.getString("scenario"));
        assertEquals(8, json.getInteger("concurrency"));
        assertEquals(true, json.getBoolean("syncCommit"), "observed with show synchronous_commit, not echoed from the flag: " + json.encode());
        assertEquals(0, json.getInteger("failed"), json.encode());
        assertEquals(MESSAGES, count(db, "select count(distinct message_id) from bodies where step = 0"),
                "each 202 is a durable save including the bodies insert (D-061)");
    }

    /**
     * P2-08b, the twin of {@code NodeBenchAckCommitsIT}'s settings case on Testcontainers' defaults (fsync off): the record carries
     * the six commit settings as a new session of the run's database shows them in pg_settings, the node's pool size and the two
     * per-message counters.
     */
    @Test
    void anAckRunRecordsTheCommitSettingsAsTheServerShowsThem() throws Exception {
        String db = database();
        Path out = dir.resolve("ack-settings.json");
        assertEquals(0, NodeBenchMain.run(args(db, "ack", "keep", 4, out)));
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals("off", text(db, "select setting from pg_settings where name = 'fsync'"), "Testcontainers starts Postgres with fsync off");
        for (String name : List.of("synchronous_commit", "commit_delay", "commit_siblings", "wal_sync_method", "fsync", "full_page_writes")) {
            assertEquals(text(db, "select setting from pg_settings where name = '" + name + "'"), String.valueOf(json.getValue(name)),
                    name + " as pg_settings shows it on the run's database: " + json.encode());
        }
        assertEquals(16, json.getInteger("poolSize"), "the node's Postgres pool (FLOTTILA_PG_POOL_SIZE default): " + json.encode());
        assertNotNull(json.getDouble("commitsPerMessage"), "the ack record carries commitsPerMessage: " + json.encode());
        assertTrue(json.getDouble("commitsPerMessage") >= 1.0, "each 202 is at least one commit: " + json.encode());
        // fsync off: pg_stat_wal.wal_sync does not count, the field is there and zero
        assertEquals(0.0, json.getDouble("walSyncsPerMessage"), "fsync off syncs no WAL: " + json.encode());
    }

    @Test
    void recordingMetadataOnlyWritesNoBodies() throws Exception {
        String metadataOnly = database();
        Path out = dir.resolve("metadata-only.json");
        assertEquals(0, NodeBenchMain.run(args(metadataOnly, "sync", "metadata-only", 4, out)));
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals("metadata-only", json.getString("recording"));
        assertEquals(MESSAGES, count(metadataOnly, "select count(distinct id) from messages"), "metadata-only still records every message's steps");
        assertEquals(0, count(metadataOnly, "select count(*) from bodies"), "metadata-only keeps no body");
        assertEquals(0.0, json.getDouble("bodiesBytesPerSecond"), "the bodies table did not grow: " + json.encode());
        assertEquals(0.0, json.getDouble("bodiesGrowthPerHour"), json.encode());

        // the twin: the same scenario with keep writes bodies, so the zero above is the recording level, not a bench that never writes one
        String keep = database();
        Path kept = dir.resolve("keep.json");
        assertEquals(0, NodeBenchMain.run(args(keep, "sync", "keep", 4, kept)));
        assertEquals("keep", new JsonObject(Files.readString(kept)).getString("recording"));
        assertEquals(MESSAGES, count(keep, "select count(distinct id) from messages"));
        assertTrue(count(keep, "select count(distinct message_id) from bodies") > 0, "keep persists the recorded steps' bodies (D-064)");
    }

    @Test
    void walAndBodiesRatesArePositiveAfterARun() throws Exception {
        String db = database();
        Path out = dir.resolve("rates.json");
        assertEquals(0, NodeBenchMain.run(args(db, "ack", "keep", 4, out)));
        JsonObject json = new JsonObject(Files.readString(out));
        double wal = json.getDouble("walBytesPerSecond");
        double bodies = json.getDouble("bodiesBytesPerSecond");
        double perHour = json.getDouble("bodiesGrowthPerHour");
        assertTrue(wal > 0, "walBytesPerSecond: " + json.encode());
        assertTrue(bodies > 0, "bodiesBytesPerSecond: " + json.encode());
        assertEquals(bodies * 3600, perHour, Math.abs(bodies * 3600) * 1e-9, "bodiesGrowthPerHour extrapolates the per-second rate");

        // the rates are bytes per second over the run's duration (elapsedMs): the ingress bodies were inserted inside the run,
        // the table cannot have grown by more than its size after the run
        double seconds = json.getDouble("elapsedMs") / 1000.0;
        long ingressBytes = count(db, "select coalesce(sum(octet_length(bytes)), 0) from bodies where step = 0");
        long finalSize = count(db, "select pg_total_relation_size('bodies')");
        assertEquals(MESSAGES, count(db, "select count(*) from bodies where step = 0"));
        assertTrue(bodies * seconds >= ingressBytes,
                "bodies delta " + bodies * seconds + " B over " + seconds + " s holds at least the " + ingressBytes + " B of ingress bodies");
        assertTrue(bodies * seconds <= finalSize, "bodies delta " + bodies * seconds + " B is at most the table's size " + finalSize + " B");
        assertTrue(wal * seconds >= ingressBytes, "WAL delta " + wal * seconds + " B holds at least the " + ingressBytes + " B of ingress bodies");
    }

    @Test
    void workerWithTheXsltStepTransformsEveryItem() throws Exception {
        String db = database();
        Path out = dir.resolve("worker-xslt.json");
        assertEquals(0, NodeBenchMain.run(args(db, "worker", "keep", 4, out, "--step", "xslt")));
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals("worker", json.getString("scenario"));
        assertEquals("xslt", json.getString("step"), "the record names its step as the CLI does");
        assertEquals(0, json.getInteger("failed"), json.encode());
        assertEquals(0, count(db, "select count(*) from work"), "every queued item was claimed and completed");
        assertEquals(MESSAGES, count(db, "select count(distinct id) from messages"));
        long smallest = count(db, "select min(octet_length(bytes)) from bodies where step = 0");
        assertTrue(smallest >= 50_000 && smallest <= 200_000, "each item's input is the ~100 KB generated XML body: smallest " + smallest + " B");
        assertEquals(MESSAGES, count(db, TRANSFORMED), "for every item a recorded step body after the ingress differs from its input: the XSLT ran");

        // the twin: the passthrough worker leaves every recorded body equal to its input (the partner echoes), so the count above is the XSLT's
        String passthrough = database();
        Path twin = dir.resolve("worker-passthrough.json");
        assertEquals(0, NodeBenchMain.run(args(passthrough, "worker", "keep", 4, twin)));
        assertEquals("passthrough", new JsonObject(Files.readString(twin)).getString("step"), "the step defaults to passthrough");
        assertEquals(MESSAGES, count(passthrough, "select count(distinct message_id) from bodies where step = 0"));
        assertEquals(0, count(passthrough, TRANSFORMED), "passthrough changes no body");
    }

    /** Items with a body recorded after the ingress whose bytes differ from the ingress body. */
    static final String TRANSFORMED = "select count(distinct b0.message_id) from bodies b0 join bodies b"
            + " on b.message_id = b0.message_id and b.step > 0 and b.bytes <> b0.bytes where b0.step = 0";

    // --- fixtures

    static String[] args(String db, String scenario, String recording, int concurrency, Path out, String... extra) {
        List<String> a = new ArrayList<>(Arrays.asList("--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)),
                "--db", db, "--user", pg.getUsername(), "--password", pg.getPassword(),
                "--scenario", scenario, "--recording", recording,
                "--messages", String.valueOf(MESSAGES), "--warmup", "0", "--concurrency", String.valueOf(concurrency),
                "--name", "it-" + scenario, "--out", out.toString(), "--commit", "test"));
        a.addAll(Arrays.asList(extra));
        return a.toArray(String[]::new);
    }

    static void assertDistribution(JsonObject d, String what) {
        for (String f : DISTRIBUTION) assertNotNull(d.getDouble(f), what + " carries " + f + ": " + d.encode());
        assertTrue(d.getDouble("p50Ms") > 0, what + " p50Ms: " + d.encode());
        assertTrue(d.getDouble("p95Ms") >= d.getDouble("p50Ms"), what + ": p95 >= p50: " + d.encode());
        assertTrue(d.getDouble("p99Ms") >= d.getDouble("p95Ms"), what + ": p99 >= p95: " + d.encode());
        assertTrue(d.getDouble("maxMs") >= d.getDouble("p99Ms"), what + ": max >= p99: " + d.encode());
        assertTrue(d.getDouble("throughputPerSecond") > 0, what + " throughputPerSecond: " + d.encode());
    }

    /** A fresh database for the case; the harness migrates it through the node. */
    static String database() throws SQLException {
        String db = "nodebench_" + cases.incrementAndGet();
        sql(pg.getDatabaseName(), "CREATE DATABASE \"" + db + "\"");
        return db;
    }

    static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }

    static void sql(String db, String statement) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    static long count(String db, String query) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            assertTrue(r.next(), query);
            return r.getLong(1);
        }
    }

    static String text(String db, String query) throws SQLException {
        try (Connection c = connect(db); Statement s = c.createStatement(); ResultSet r = s.executeQuery(query)) {
            assertTrue(r.next(), query);
            return r.getString(1);
        }
    }
}
