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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GATE 9 "a 50 MB body through a stream step without heap growth" (P1-10c, ARCHITECTURE §13 Phase 1) on a Postgres container.
 * {@link StreamHeapMain#run} starts the node as a child JVM (this JVM's classpath) at {@code --xmx}, deploys a flow whose
 * steps are the bench's stream step ({@code stream-copy}, named {@code copy}: it reads {@code open()} and writes a new body)
 * and {@code to} the echo partner, sends one {@code --mb} MiB HTTP body through it and stops the node, its recording landed,
 * before it returns. The child samples its heap after a forced GC (MemoryMXBean) before and after the body passed.
 *
 * <p>The record: {@code mb}, {@code xmx}, {@code maxHeapBytes} (the child's MemoryMXBean heap max), {@code heapBeforeBytes},
 * {@code heapAfterBytes}, {@code heapGrowthBytes} (after − before), {@code sentSha256}, {@code partnerSha256} (hex),
 * {@code oom}, {@code elapsedMs}, {@code machine}, {@code commit}, {@code date}.
 *
 * <p>Not vacuous: the heap max the child reports is at most the 64 MiB asked for (a sample of this JVM's heap would not be),
 * two copies of the body do not fit into it, the flow's {@code copy} step recorded the whole body (its size and its content
 * hash in the database are the sent body's), and the partner's sha256 is the sent one. Correctness only: no timing
 * thresholds (D-036).
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class StreamHeapIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static final long MIB = 1024L * 1024;

    @TempDir Path dir;

    @BeforeAll
    static void start() {
        pg.start();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @Test
    void fiftyMegabytesPassAStreamStepAtXmx64mWithoutHeapGrowth() throws Exception {
        String db = newDatabase();
        Path out = dir.resolve("nested/stream-heap.json");
        int code = StreamHeapMain.run(new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db,
                "--user", pg.getUsername(), "--password", pg.getPassword(), "--mb", "50", "--xmx", "64m", "--out", out.toString(),
                "--commit", "test"});
        assertTrue(Files.exists(out), "the run wrote its JSON to --out (its directory created): " + out);
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(0, code, "the body passed intact without OOM or heap growth: " + json.encode());

        // the record
        assertEquals(50, json.getInteger("mb"), json.encode());
        assertEquals("64m", json.getString("xmx"), json.encode());
        assertFalse(json.getBoolean("oom"), "no OutOfMemoryError in the node: " + json.encode());
        String sent = json.getString("sentSha256");
        assertNotNull(sent, json.encode());
        assertTrue(sent.matches("[0-9a-f]{64}"), "sentSha256 is a hex SHA-256: " + sent);
        assertEquals(sent, json.getString("partnerSha256"), "the partner received the bytes sent: " + json.encode());
        long body = 50 * MIB;

        // the heap: sampled in a child JVM whose -Xmx is really 64m, and too small for two copies of the body
        long max = json.getLong("maxHeapBytes");
        assertTrue(max <= 64 * MIB, "the heap max is the child's -Xmx64m, not this JVM's: " + max);
        assertTrue(max > 48 * MIB, "the heap max is the child's -Xmx64m, not a smaller one: " + max);
        assertTrue(2 * body > max, "the heap cannot hold the body twice (in and copy): " + max);
        long before = json.getLong("heapBeforeBytes");
        long after = json.getLong("heapAfterBytes");
        long growth = json.getLong("heapGrowthBytes");
        assertTrue(before > 0 && before <= max, "heapBeforeBytes is a heap sample of the child: " + json.encode());
        assertTrue(after > 0 && after <= max, "heapAfterBytes is a heap sample of the child: " + json.encode());
        assertEquals(after - before, growth, "heapGrowthBytes = heapAfterBytes - heapBeforeBytes: " + json.encode());
        assertTrue(growth < 8 * MIB, "the heap after a forced GC grew less than 8 MiB: " + json.encode());

        // the body went through the stream step: its snapshot carries the whole body and the sent content's hash
        List<Copy> copies = copies(db);
        assertEquals(1, copies.stream().filter(c -> c.size() == body).count(), "one message's copy step recorded the 50 MiB body: " + copies);
        Copy copy = copies.stream().filter(c -> c.size() == body).findFirst().orElseThrow();
        assertEquals("ok", copy.outcome(), "the copy step succeeded: " + copy);
        assertEquals("sha256-64", copy.algorithm(), "the node hashes with sha256-64: " + copy);
        assertEquals(sent.substring(0, 16), copy.hashHex(), "the copy step's body is the sent content (sha256's first eight bytes): " + copy);

        // the child is gone when the run returns
        List<String> alive = ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .map(p -> p.pid() + " " + p.info().commandLine().orElse("?")).filter(p -> p.contains("java")).toList();
        assertEquals(List.of(), alive, "no child JVM outlives the run");

        assertTrue(json.getDouble("elapsedMs") > 0, json.encode());
        assertEquals("test", json.getString("commit"));
        assertDoesNotThrow(() -> Instant.parse(json.getString("date")), "date is an ISO-8601 instant: " + json.getString("date"));
        JsonObject machine = json.getJsonObject("machine");
        assertNotNull(machine, "the record carries the machine: " + json.encode());
        assertEquals(BenchMain.machine().getString("label"), machine.getString("label"), "the machine label picks the baseline (D-037)");
    }

    // --- fixtures

    /** A snapshot row of the {@code copy} step. */
    record Copy(String id, int step, String outcome, long size, String hashHex, String algorithm) {}

    static List<Copy> copies(String db) throws SQLException {
        List<Copy> copies = new ArrayList<>();
        try (Connection c = connect(db); PreparedStatement s = c.prepareStatement("select m.id, m.step, m.outcome, m.body_size,"
                + " encode(m.body_hash, 'hex'), p.algorithm from messages m join payloads p on p.hash = m.body_hash where m.step_name = 'copy'");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                copies.add(new Copy(r.getString(1), r.getInt(2), r.getString(3), r.getLong(4), r.getString(5), r.getString(6)));
            }
        }
        return copies;
    }

    /** A database of the case's own; its name contains "bench" (the run truncates it). */
    static String newDatabase() throws SQLException {
        String db = "stream_heap_bench_" + cases.incrementAndGet();
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
