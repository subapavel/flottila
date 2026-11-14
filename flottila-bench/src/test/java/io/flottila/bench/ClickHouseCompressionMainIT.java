package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CORE-2 harness end to end against the pinned ClickHouse image: a small matrix through {@link ClickHouseCompressionMain#run},
 * judged by its JSON record; failures as ClickHouse reports them (users without the INSERT / OPTIMIZE or the table SELECT
 * privilege); {@link ClickHouseHttp}'s error answer; the argument checks. The numbers it produces are not recorded from here.
 */
class ClickHouseCompressionMainIT {

    static final String PASSWORD = "it-secret";

    @SuppressWarnings("resource")
    static final GenericContainer<?> ch = new GenericContainer<>("clickhouse/clickhouse-server:25.8.33.6")
            .withEnv("CLICKHOUSE_PASSWORD", PASSWORD)
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    @TempDir Path dir;

    @BeforeAll
    static void start() throws Exception {
        ch.start();
        ClickHouseHttp admin = new ClickHouseHttp(url(), "default", PASSWORD);
        admin.query("CREATE USER noinsert IDENTIFIED WITH plaintext_password BY 'p1'");
        admin.query("GRANT SELECT, CREATE DATABASE, CREATE TABLE, DROP TABLE, SYSTEM FLUSH LOGS ON *.* TO noinsert");
        admin.query("CREATE USER noread IDENTIFIED WITH plaintext_password BY 'p2'");
        admin.query("GRANT INSERT, OPTIMIZE, CREATE DATABASE, CREATE TABLE, DROP TABLE, SYSTEM FLUSH LOGS ON *.* TO noread");
        admin.query("GRANT SELECT ON system.* TO noread");
    }

    @AfterAll
    static void stop() {
        ch.stop();
    }

    static String url() {
        return "http://" + ch.getHost() + ":" + ch.getMappedPort(8123);
    }

    private JsonObject run(int expectedExit, String user, String password, String... args) throws Exception {
        Path out = dir.resolve("ch-" + user + ".json");
        List<String> all = new ArrayList<>(List.of("--url", url(), "--user", user, "--password", password, "--out", out.toString(),
                "--commit", "test", "--image", "it"));
        all.addAll(List.of(args));
        assertEquals(expectedExit, ClickHouseCompressionMain.run(all.toArray(String[]::new)));
        return new JsonObject(Files.readString(out));
    }

    @Test
    void aSmallMatrixStoresEveryPayloadCompressesItAndReadsEachBackUnchanged() throws Exception {
        JsonObject json = run(0, "default", PASSWORD, "--sizes", "8,1024", "--counts", "30,12", "--mixes", "mixed,similar",
                "--formats", "xml,json", "--levels", "1", "--runs", "2", "--reads", "20", "--warmup-reads", "3", "--batch-bytes", "3145728");
        assertEquals("25.8.33.6", json.getJsonObject("server").getString("version"));
        JsonArray cells = json.getJsonArray("cells");
        assertEquals(8, cells.size(), "2 sizes x 2 mixes x 2 formats x 1 level");
        for (Object o : cells) {
            JsonObject cell = (JsonObject) o;
            assertEquals(2, cell.getInteger("runsMade"), "a run under --max-run-minutes is repeated: " + cell.encode());
            int count = cell.getInteger("count");
            for (Object r : cell.getJsonArray("runs")) {
                JsonObject run = (JsonObject) r;
                String what = cell.getLong("sizeKib") + " KiB " + cell.getString("mix") + " " + cell.getString("format") + ": " + run.encode();
                assertEquals(count, run.getInteger("rows"), what);
                assertEquals(count, run.getLong("partRows"), what);
                assertTrue(run.getLong("rawBytes") >= count * (cell.getLong("sizeKib") << 10), what);
                assertEquals(0, run.getInteger("insertFailures"), what);
                assertNull(run.getString("optimizeFailure"), what);
                assertEquals(1, run.getLong("activeParts"), what);
                assertTrue(run.getDouble("partRatio") > 1, what);
                assertEquals(20, run.getInteger("reads"), what);
                assertEquals(0, run.getInteger("readMismatches"), what);
                assertEquals(20, run.getLong("readsLogged"), "the timed reads, warm-up excluded, in system.query_log: " + what);
                assertTrue(run.getDouble("readP99Ms") >= run.getDouble("readP50Ms"), what);
                assertTrue(run.getLong("serverMemorySamples") >= 1, what);
                assertTrue(run.getLong("merges") >= 1, "at least the OPTIMIZE FINAL merge in system.part_log: " + what);
                assertTrue(run.getLong("mergePeakMemoryBytes") > 0, what);
                assertTrue(run.getLong("mergeFanInMax") >= 1, what);
                assertEquals(run.getLong("inserts"), run.getLong("insertsLogged"), "every insert in system.query_log: " + what);
                assertTrue(run.getLong("insertPeakMemoryBytes") > 0, what);
                assertEquals(1, run.getLong("optimizeLogged"), what);
            }
        }
        JsonObject big = cells.getJsonObject(4).getJsonArray("runs").getJsonObject(0);
        assertEquals(1, big.getLong("wideParts"), "12 MiB of 1 MiB payloads end in a Wide part: " + big.encode());
        assertTrue(big.getInteger("inserts") > 1, "1 MiB rows in 3 MiB batches take several inserts: " + big.encode());
        assertTrue(big.getDouble("payloadRatio") > 1, big.encode());
        assertTrue(big.getLong("mergeFanInMax") >= 2, "several insert parts merged into one: " + big.encode());
        assertTrue(big.getLong("mergePeakFanIn") >= 1, big.encode());
    }

    @Test
    void failedInsertsAndAFailedOptimizeAreRecordedAsTheCellsResultAndNothingIsRead() throws Exception {
        JsonObject json = run(0, "noinsert", "p1", "--sizes", "8", "--counts", "3", "--mixes", "similar", "--formats", "xml",
                "--levels", "3", "--runs", "2", "--max-run-minutes", "0", "--reads", "5", "--batch-bytes", "16384");
        JsonObject cell = json.getJsonArray("cells").getJsonObject(0);
        assertEquals(1, cell.getInteger("runsMade"), "a first run at or over --max-run-minutes is not repeated");
        JsonObject run = cell.getJsonArray("runs").getJsonObject(0);
        assertEquals(3, run.getInteger("inserts"), "one 8 KiB row per 16 KiB batch: " + run.encode());
        assertEquals(3, run.getInteger("insertFailures"), run.encode());
        assertTrue(run.getString("insertFailure").contains("ACCESS_DENIED"), run.getString("insertFailure"));
        assertTrue(run.getString("optimizeFailure").contains("ACCESS_DENIED"), run.getString("optimizeFailure"));
        assertEquals(0, run.getInteger("rows"));
        assertEquals(0, run.getInteger("reads"));
        assertEquals(0.0, run.getDouble("partRatio"), "no part, no ratio");
        assertTrue(run.getLong("queryExceptions") >= 4, "the refused inserts and OPTIMIZE in system.query_log: " + run.encode());
    }

    @Test
    void aReadThatFailsIsCountedAndTheRunExitsThree() throws Exception {
        JsonObject json = run(3, "noread", "p2", "--sizes", "8", "--counts", "2", "--mixes", "mixed", "--formats", "json",
                "--levels", "9", "--runs", "1", "--reads", "3", "--warmup-reads", "1");
        JsonObject run = json.getJsonArray("cells").getJsonObject(0).getJsonArray("runs").getJsonObject(0);
        assertEquals(2, run.getInteger("rows"), run.encode());
        assertEquals(4, run.getInteger("readMismatches"), "1 warm-up and 3 timed reads, all refused: " + run.encode());
        assertEquals(0, run.getInteger("reads"), "a failed read is not timed");
    }

    @Test
    void aFailingStatementSurfacesWithTheServersMessage() {
        ClickHouseHttp http = new ClickHouseHttp(url() + "/", "default", PASSWORD);
        ClickHouseHttp.Failed unknown = assertThrows(ClickHouseHttp.Failed.class, () -> http.query("SELECT no_such_column"));
        assertTrue(unknown.getMessage().startsWith("HTTP "), unknown.getMessage());
        assertTrue(unknown.getMessage().contains("UNKNOWN_IDENTIFIER"), unknown.getMessage());
        ClickHouseHttp.Failed denied = assertThrows(ClickHouseHttp.Failed.class,
                () -> new ClickHouseHttp(url(), "default", "wrong").query("SELECT 1"));
        assertTrue(denied.getMessage().startsWith("HTTP 401: "), denied.getMessage());
        assertTrue(denied.getMessage().contains("Authentication failed"), denied.getMessage());
    }

    @Test
    void argumentsOutsideTheMatrixAreRefusedBeforeAnyStatement() {
        String[][] bad = {
            {"--sizes", "0", "--counts", "1"},
            {"--sizes", "65536", "--counts", "1"},                      // 64 MiB leaves no room in a 64 MiB batch
            {"--sizes", "8", "--counts", "1", "--batch-bytes", "16383"}, // 8 KiB + slack is 16 384
            {"--sizes", "8,16", "--counts", "1"},
            {"--sizes", "8", "--counts", "0"},
            {"--sizes", "8", "--counts", "1", "--levels", "0"},
            {"--sizes", "8", "--counts", "1", "--levels", "23"},
            {"--sizes", "8", "--counts", "1", "--runs", "0"},
        };
        for (String[] args : bad) {
            List<String> all = new ArrayList<>(List.of("--url", "http://127.0.0.1:9", "--out", dir.resolve("never.json").toString()));
            all.addAll(List.of(args));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ClickHouseCompressionMain.run(all.toArray(String[]::new)),
                    String.join(" ", args));
            assertNotNull(e.getMessage());
            assertTrue(!Files.exists(dir.resolve("never.json")));
        }
    }
}
