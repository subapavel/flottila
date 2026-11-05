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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-124: the CORE-GATE cell's shape, the ack scenario open loop with {@code --recording-level none}, against an in-process
 * node over a container's Postgres at a low rate: the node takes the flow, the settle lands (an at-least-once message's intake
 * row is written whatever the level) while no later step is recorded, and the record says the level, {@code settled: true}
 * and the counts read once landed.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NodeBenchOpenRecordingLevelIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");

    @TempDir Path dir;

    @BeforeAll static void start() { pg.start(); }

    @AfterAll static void stop() { pg.stop(); }

    @Test
    void anAckOpenLoopWithRecordingLevelNoneSettlesAndSaysSo() throws Exception {
        String db = "levelbench";
        try (var c = connect(pg.getDatabaseName()); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + db);
        }
        Path out = dir.resolve("open-ack-none.json");
        int code = NodeBenchMain.run(new String[] {"--host", pg.getHost(), "--port", String.valueOf(pg.getMappedPort(5432)), "--db", db,
                "--user", pg.getUsername(), "--password", pg.getPassword(), "--scenario", "ack", "--rate", "50", "--ramp", "PT0S",
                "--window", "PT2S", "--warmup", "0", "--concurrency", "4", "--recording-level", "none",
                "--name", "it-open-ack-none", "--out", out.toString(), "--commit", "test"});

        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals(0, code, "a settled open run without failed requests exits 0: " + json.encode());
        assertEquals("none", json.getString("recordingLevel"), json.encode());
        JsonObject open = json.getJsonObject("open");
        assertEquals(Boolean.TRUE, open.getBoolean("settled"), open.encode());
        assertEquals(open.getLong("accepted"), open.getLong("messages"), "every accepted message landed: " + open.encode());
        assertEquals(0L, open.getLong("work"), "the work queue is empty once landed: " + open.encode());
        assertEquals(open.getLong("accepted"), open.getLong("receipts"), open.encode());
        String steps = "select count(*) from messages where step > 0";
        try (var c = connect(db); var s = c.createStatement(); var r = s.executeQuery(steps)) {
            r.next();
            assertEquals(0, r.getLong(1), "the node recorded no step past the intake: " + steps);
        }
    }

    static java.sql.Connection connect(String db) throws java.sql.SQLException {
        return java.sql.DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db,
                pg.getUsername(), pg.getPassword());
    }
}
