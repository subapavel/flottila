package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15 self-test: {@code --scenario echo} runs the open loop straight at the raw echo — no node, no Postgres (the run is
 * pointed at a port nothing listens on) — in real time, short. Its pass is a count within 1 %, not a timing sleep.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class NodeBenchOpenEchoIT {

    /** Every field of the record's {@code open} object (CORE-15 packet, Scope 2). */
    static final List<String> OPEN_FIELDS = List.of("offeredRate", "scheduled", "sent", "achievedRate", "accepted", "refused",
            "refused503", "timeouts", "errors", "clientSaturated", "p50Ms", "p99Ms", "p999Ms", "maxMs", "receipts", "backlog",
            "backlogGrowth", "backlogGrowthPercent", "cpuMs", "cpuNotes", "valid", "invalidReasons");

    @TempDir Path dir;

    @Test
    void theEchoSelfTestAchievesTwoThousandPerSecondWithinOnePercentWithEveryFieldAndValid() throws Exception {
        Path out = dir.resolve("echo.json");
        int nothingListens;
        try (ServerSocket s = new ServerSocket(0)) {
            nothingListens = s.getLocalPort();
        }
        int code = NodeBenchMain.run(new String[] {"--host", "127.0.0.1", "--port", String.valueOf(nothingListens),
                "--scenario", "echo", "--rate", "2000", "--ramp", "PT1S", "--window", "PT5S", "--warmup", "0",
                "--name", "it-open-echo", "--out", out.toString(), "--commit", "test"});
        assertEquals(0, code, "the echo self-test exits 0");
        JsonObject json = new JsonObject(Files.readString(out));
        assertEquals("echo", json.getString("scenario"), json.encode());
        JsonObject open = json.getJsonObject("open");
        assertNotNull(open, "the record carries the open object: " + json.encode());
        assertFields(open);

        assertEquals(2000, open.getNumber("offeredRate").doubleValue(), 1e-9, open.encode());
        assertEquals(10_000, open.getNumber("scheduled").longValue(), "2000/s × 5 s: " + open.encode());
        double achieved = open.getNumber("achievedRate").doubleValue();
        assertTrue(Math.abs(achieved - 2000) <= 20, "achievedRate within 1 % of 2000: " + achieved + " " + open.encode());
        assertEquals(0, open.getNumber("clientSaturated").longValue(), open.encode());
        assertEquals(open.getNumber("sent").longValue(), open.getNumber("accepted").longValue(), "the echo answers every request: " + open.encode());
        assertEquals(0, open.getNumber("errors").longValue(), open.encode());
        assertEquals(0, open.getNumber("timeouts").longValue(), open.encode());
        assertEquals(true, open.getBoolean("valid"), open.encode());
        assertEquals(6, open.getJsonArray("backlog").size(), "the window's start, each second, its end (5 s): " + open.encode());
        double p50 = open.getNumber("p50Ms").doubleValue();
        double p99 = open.getNumber("p99Ms").doubleValue();
        double p999 = open.getNumber("p999Ms").doubleValue();
        double max = open.getNumber("maxMs").doubleValue();
        assertTrue(0 < p50 && p50 <= p99 && p99 <= p999 && p999 <= max, "ordered percentiles: " + open.encode());

        JsonObject cpu = open.getJsonObject("cpuMs");
        assertTrue(cpu.getNumber("bench").doubleValue() > 0, "the bench JVM's CPU over the window: " + open.encode());
        for (String unmeasured : List.of("node", "postgres")) {
            assertTrue(cpu.containsKey(unmeasured), unmeasured + " is in the record: " + open.encode());
            assertNull(cpu.getValue(unmeasured), "no node and no --pg-cpu-source: null, never 0: " + open.encode());
            assertTrue(!open.getJsonObject("cpuNotes").getString(unmeasured, "").isBlank(), unmeasured + " has a reason: " + open.encode());
        }
    }

    /** Every field of {@code open} is present, with its JSON type. */
    static void assertFields(JsonObject open) {
        for (String f : OPEN_FIELDS) assertTrue(open.containsKey(f), "open carries " + f + ": " + open.encode());
        assertTrue(open.getValue("refused") instanceof JsonObject, "refused is a map by status: " + open.encode());
        assertTrue(open.getValue("backlog") instanceof JsonArray, "backlog is a series: " + open.encode());
        assertTrue(open.getValue("cpuMs") instanceof JsonObject, open.encode());
        assertTrue(open.getValue("cpuNotes") instanceof JsonObject, open.encode());
        assertTrue(open.getValue("invalidReasons") instanceof JsonArray, open.encode());
        for (String process : List.of("bench", "node", "postgres")) {
            assertTrue(open.getJsonObject("cpuMs").containsKey(process), "cpuMs carries " + process + ": " + open.encode());
        }
    }
}
