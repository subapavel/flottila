package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kill -9 ledger (P1-10b, GATE 9, ARCHITECTURE §11: sent = final-step rows, DLQ 0, work 0) on containers: Postgres and
 * LocalStack S3 for the package archive. {@link LedgerMain#run} starts node 1 as a child JVM (same classpath) with the
 * at-least-once bench package (its last step {@code to partner}, named {@code partner}, every step recorded), sends, kills node 1
 * with {@code destroyForcibly} (SIGKILL) after {@code --kill-after} ACKs, starts node 2 on an empty data directory — it restores
 * the package from the deployment record and S3, no PUT — re-sends every message whose 202 did not arrive until it is ACKed,
 * sends the rest and waits, bounded, for the work queue to be empty: GATE 9 reads N/N over all N messages. Correctness only: no
 * timing thresholds (D-036). Every case runs on a database and an S3 prefix of its own.
 *
 * <p>The record's fields: {@code messages}; {@code sent} (distinct messages sent at least once: {@code messages} when the run
 * ends); {@code resent} (POSTs beyond the first per message: a message is re-sent after every attempt without a 202);
 * {@code acked}, {@code ackedIds} (the id of each message's one 202, {@code x-message-id}); {@code finalRows} (ACKed ids with a
 * final-step row); {@code savedUnacked} (distinct ids with a final-step row that are not ACKed ids: an attempt node 1 saved
 * whose 202 the kill cut, its message re-sent under a new id — reported, never a failure); {@code missing} (ACKed ids without a
 * final-step row); {@code dlq}, {@code work}; {@code partnerCalls}; {@code duplicates} (partner calls beyond one per ACKed
 * message: {@code partnerCalls − finalRows}, so a saved-but-unACKed id counts as one); {@code killedAfter} (node 1's ACKs before
 * the kill), {@code node1ExitValue}, {@code killedAt} (the database's clock, read after node 1 died and before node 2 started),
 * {@code workAtKill} and {@code unfinishedAtKill} (work rows, and ACKed ids without a final-step row, at that moment);
 * {@code heldToKillMs} (wall-clock ms from the partner's first held call to the kill; -1 when it held none before the kill —
 * reported, never a failure); {@code machine}, {@code commit}, {@code date}.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class LedgerIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final LocalStackContainer localstack = new LocalStackContainer("localstack/localstack:4").withServices("s3");
    static final String BUCKET = "flottila-ledger-it";
    static final AtomicInteger cases = new AtomicInteger();
    /** A final-step row: the bench flow's last step, {@code to partner}, recorded as done. */
    static final String FINAL = "step_name = 'partner' and outcome = 'ok'";
    /** Linux reports a child killed by signal 9 as 128 + 9. */
    static final int SIGKILLED = 137;
    static S3Client s3;

    @TempDir Path dir;

    @BeforeAll
    static void start() {
        pg.start();
        localstack.start();
        s3 = S3Client.builder().endpointOverride(localstack.getEndpoint()).region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stop() {
        s3.close();
        localstack.stop();
        pg.stop();
    }

    @Test
    void aKilledNodeLosesNoAckedMessage() throws Exception {
        Case c = newCase();
        Path out = dir.resolve("ledger.json");
        int code = LedgerMain.run(args(c, 200, 100, out));
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(0, code, "the ledger holds: " + json.encode());
        // N/N over all N: every message sent was ACKed once, whatever the kill cut (the driver re-sends until the 202 arrives)
        assertEquals(200, json.getInteger("sent"), "sent counts messages, not attempts: " + json.encode());
        assertEquals(200, json.getInteger("acked"), "every message was ACKed, a cut 202 re-sent until it arrived: " + json.encode());

        // node 1 really died by SIGKILL, after the ACKs it was given, and node 2 took the rest
        assertEquals(SIGKILLED, json.getInteger("node1ExitValue"), "node 1 was killed by signal 9 (destroyForcibly), not stopped: " + json.encode());
        int killedAfter = json.getInteger("killedAfter");
        int acked = json.getInteger("acked");
        assertTrue(killedAfter >= 100, "node 1 was killed after at least --kill-after ACKs: " + json.encode());
        assertTrue(acked > killedAfter, "node 2 ACKed messages of the rest: " + json.encode());

        // not vacuous: at the kill node 1 had ACKed messages it had not finished, and node 2 finished them
        assertTrue(json.getInteger("workAtKill") > 0, "work was queued or in flight when node 1 died: " + json.encode());
        int unfinished = json.getInteger("unfinishedAtKill");
        assertTrue(unfinished > 0, "ACKed messages had no final-step row when node 1 died: " + json.encode());
        Instant killedAt = Instant.parse(json.getString("killedAt"));
        List<String> ackedIds = strings(json.getJsonArray("ackedIds"));
        long finishedByNode2 = count(c.db, "select count(distinct f.id) from messages f join bodies b on b.message_id = f.id and b.step = 0"
                + " where f." + FINAL + " and b.created_at < ?::timestamptz and f.created_at > ?::timestamptz and f.id = any(?)",
                killedAt.toString(), killedAt.toString(), ackedIds);
        assertTrue(finishedByNode2 >= 1, "an id saved and ACKed before the kill got its final-step row after it, from node 2: " + json.encode());
        assertTrue(finishedByNode2 <= unfinished, "only what was unfinished at the kill is finished after it: " + finishedByNode2 + " vs " + unfinished);
        // node 2 ran the package it restored: the deployment record is node 1's, written before the kill, not replaced by a PUT
        long deployedBeforeKill = count(c.db, "select count(*) from deployments where package = 'bench' and deployed_at < ?::timestamptz", killedAt.toString());
        assertEquals(1, deployedBeforeKill, "node 2 restored the bench package from its record, nothing re-deployed it");
        assertFalse(archives(c.prefix).isEmpty(), "the package archive is in S3 under the case prefix: " + c.prefix);

        // the ledger, checked in the database rather than in the record
        assertEquals(acked, ackedIds.size(), "one id per ACK: " + json.encode());
        assertEquals(acked, new HashSet<>(ackedIds).size(), "the ACKed ids are distinct");
        assertEquals(new JsonArray(), missing(c.db, ackedIds), "every ACKed id has a final-step row");
        assertEquals(new JsonArray(), json.getJsonArray("missing"), json.encode());
        assertEquals(0, count(c.db, "select count(*) from dlq"), "nothing was parked");
        assertEquals(0, count(c.db, "select count(*) from work"), "nothing is left queued or leased");
        long finalRows = count(c.db, "select count(distinct id) from messages where " + FINAL + " and id = any(?)", ackedIds);
        assertEquals(finalRows, json.getInteger("finalRows").longValue(), "finalRows counts the ACKed ids with a final-step row in the database");
        assertEquals(acked, finalRows, "sent = ACKed = final-step rows; a lease re-run's duplicate row shares its id: " + json.encode());
        assertEquals(0, json.getInteger("dlq"), json.encode());
        assertEquals(0, json.getInteger("work"), json.encode());

        // the kill's leftovers, reported: an attempt node 1 saved whose 202 was cut is a final-step id of its own, not ACKed
        int resent = json.getInteger("resent");
        int savedUnacked = json.getInteger("savedUnacked");
        assertEquals(count(c.db, "select count(distinct id) from messages where " + FINAL + " and not (id = any(?))", ackedIds),
                (long) savedUnacked, "savedUnacked = the final-step ids that are not ACKed ids: " + json.encode());
        assertEquals(count(c.db, "select count(distinct id) from messages where " + FINAL), finalRows + savedUnacked,
                "every final-step id is an ACKed one or a saved-but-unACKed one");
        assertTrue(savedUnacked >= 0 && resent >= savedUnacked, "each saved-but-unACKed id is an attempt without a 202, re-sent: " + json.encode());
        assertTrue(json.getInteger("duplicates") >= savedUnacked, "a saved-but-unACKed id's partner call is a duplicate of its message: " + json.encode());
        assertTrue(json.getInteger("heldToKillMs") >= -1, "heldToKillMs is ms from the first held partner call, or -1: " + json.encode());
    }

    @Test
    void theLedgerJsonNamesSentFinalDlqWorkAndDuplicates() throws Exception {
        Case c = newCase();
        Path out = dir.resolve("nested/ledger-small.json");
        assertEquals(0, LedgerMain.run(args(c, 40, 20, out)));
        assertTrue(Files.exists(out), "the run wrote its JSON to --out (its directory created): " + out);
        JsonObject json = new JsonObject(Files.readString(out));

        for (String field : List.of("messages", "sent", "acked", "finalRows", "dlq", "work", "duplicates", "partnerCalls", "killedAfter",
                "node1ExitValue", "workAtKill", "unfinishedAtKill", "resent", "savedUnacked", "heldToKillMs")) {
            assertNotNull(json.getValue(field), "the record names " + field + ": " + json.encode());
            assertDoesNotThrow(() -> json.getInteger(field), field + " is a number: " + json.encode());
        }
        assertNotNull(json.getJsonArray("missing"), "missing is a list of ids: " + json.encode());
        assertNotNull(json.getJsonArray("ackedIds"), "ackedIds is a list of ids: " + json.encode());
        assertEquals(40, json.getInteger("messages"));
        assertEquals(40, json.getInteger("sent"));
        assertEquals(40, json.getInteger("acked"), "every message ACKed: " + json.encode());
        assertTrue(json.getInteger("killedAfter") >= 20, json.encode());
        assertEquals(json.getInteger("acked"), json.getJsonArray("ackedIds").size(), json.encode());
        assertTrue(json.getInteger("resent") >= 0, json.encode());
        assertTrue(json.getInteger("savedUnacked") >= 0 && json.getInteger("savedUnacked") <= json.getInteger("resent"), json.encode());
        assertTrue(json.getInteger("heldToKillMs") >= -1, json.encode());

        // duplicates are partner calls beyond one per final-step id: reported, and the run still exits 0 whatever their number
        int duplicates = json.getInteger("duplicates");
        assertTrue(duplicates >= 0, json.encode());
        assertEquals(json.getInteger("partnerCalls") - json.getInteger("finalRows"), duplicates, "duplicates = partnerCalls - finalRows: " + json.encode());
        List<String> ackedIds = strings(json.getJsonArray("ackedIds"));
        assertEquals(count(c.db, "select count(distinct id) from messages where " + FINAL + " and id = any(?)", ackedIds),
                json.getInteger("finalRows").longValue(), "finalRows = the ACKed ids with a final-step row");
        assertEquals(count(c.db, "select count(distinct id) from messages where " + FINAL + " and not (id = any(?))", ackedIds),
                json.getInteger("savedUnacked").longValue(), "savedUnacked = the final-step ids that are not ACKed ids");
        assertEquals(count(c.db, "select count(*) from dlq"), json.getInteger("dlq").longValue());
        assertEquals(count(c.db, "select count(*) from work"), json.getInteger("work").longValue());

        assertEquals("test", json.getString("commit"));
        assertDoesNotThrow(() -> Instant.parse(json.getString("date")), "date is an ISO-8601 instant: " + json.getString("date"));
        assertDoesNotThrow(() -> Instant.parse(json.getString("killedAt")), "killedAt is an ISO-8601 instant: " + json.getString("killedAt"));
        JsonObject machine = json.getJsonObject("machine");
        assertNotNull(machine, "the record carries the machine: " + json.encode());
        assertEquals(BenchMain.machine().getString("label"), machine.getString("label"), "the machine label picks the baseline (D-037)");
        assertTrue(machine.getInteger("cpus") > 0);
    }

    // --- fixtures

    record Case(String db, String prefix) {}

    /** A database of the case's own (its name contains "bench": the run truncates it) and an S3 prefix of its own. */
    static Case newCase() throws SQLException {
        int n = cases.incrementAndGet();
        String db = "ledger_bench_" + n;
        try (Connection c = connect(pg.getDatabaseName()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + db + "\"");
        }
        return new Case(db, "ledger-it-" + n);
    }

    static String[] args(Case c, int messages, int killAfter, Path out) {
        return new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", c.db,
                "--user", pg.getUsername(), "--password", pg.getPassword(),
                "--messages", String.valueOf(messages), "--kill-after", String.valueOf(killAfter), "--out", out.toString(), "--commit", "test",
                "--s3-endpoint", localstack.getEndpoint().toString(), "--s3-region", localstack.getRegion(), "--s3-bucket", BUCKET,
                "--s3-prefix", c.prefix, "--s3-access-key", localstack.getAccessKey(), "--s3-secret-key", localstack.getSecretKey()};
    }

    static List<String> archives(String prefix) {
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix + "/archives/").build()).contents().stream()
                .map(S3Object::key).toList();
    }

    static List<String> strings(JsonArray array) {
        assertNotNull(array, "a list of ids");
        List<String> ids = new ArrayList<>();
        for (Object o : array) ids.add((String) o);
        return ids;
    }

    /** The ACKed ids without a final-step row, named for the failure message. */
    static JsonArray missing(String db, List<String> ids) throws SQLException {
        JsonArray missing = new JsonArray();
        try (Connection c = connect(db); PreparedStatement s = c.prepareStatement("select a.id from unnest(?::text[]) a(id) where not exists"
                + " (select 1 from messages m where m.id = a.id and m." + FINAL + ")")) {
            s.setArray(1, c.createArrayOf("text", ids.toArray()));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) missing.add(r.getString(1));
            }
        }
        return missing;
    }

    static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }

    /** The first column of {@code query}'s one row; a {@code List} parameter binds as a text array, anything else as text. */
    static long count(String db, String query, Object... params) throws SQLException {
        try (Connection c = connect(db); PreparedStatement s = c.prepareStatement(query)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof List<?> list) {
                    Array array = c.createArrayOf("text", list.toArray());
                    s.setArray(i + 1, array);
                } else {
                    s.setString(i + 1, String.valueOf(params[i]));
                }
            }
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next(), query);
                return r.getLong(1);
            }
        }
    }
}
