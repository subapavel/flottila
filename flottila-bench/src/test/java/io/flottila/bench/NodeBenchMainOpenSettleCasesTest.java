package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-124, the implementer's cases on the open settle (the architect's decisions): an unsettled record with failed requests
 * still exits {@link NodeBenchMain#EXIT_NOT_SETTLED} (unsettled wins), an echo record (no settle, no {@code settled}) keeps its
 * codes, the record names the target it waited for, and sync with {@code --recording-level none} is refused. The fake world
 * is {@link NodeBenchMainOpenSettleTest}'s.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodeBenchMainOpenSettleCasesTest {

    @Test
    void anUnsettledRecordWithFailedRequestsExitsWithTheNotSettledCode() {
        JsonObject open = NodeBenchMainOpenSettleTest.window().put("settled", false).put("errors", 2L)
                .put("firstFailure", "java.io.IOException: connection reset");

        assertEquals(NodeBenchMain.EXIT_NOT_SETTLED, NodeBenchMain.exitCode(new JsonObject().put("open", open)),
                "the settle that did not land wins over the window's errors");
    }

    @Test
    void anOpenRecordWithoutASettleKeepsItsCodes() {
        // the echo self-test settles nothing: its record has no settled field
        assertEquals(0, NodeBenchMain.exitCode(new JsonObject().put("open", NodeBenchMainOpenSettleTest.window())));
        assertEquals(3, NodeBenchMain.exitCode(new JsonObject().put("open", NodeBenchMainOpenSettleTest.window().put("timeouts", 1L)
                .put("firstFailure", "java.util.concurrent.TimeoutException"))));
    }

    @Test
    void theUnsettledExitNamesTheTargetTheDeadlineAndTheCountsOnStderr(@TempDir Path dir) {
        NodeBenchMainOpenSettleTest.World w = new NodeBenchMainOpenSettleTest.World(TimeUnit.SECONDS.toNanos(1));
        JsonObject open = NodeBenchMainOpenSettleTest.harness(NodeBenchMainOpenSettleTest.ackOpen(dir.resolve("r.json")))
                .settleOpen(NodeBenchMainOpenSettleTest.window(), 491_978, 0, NodeBenchMainOpenSettleTest.DRAIN, w.probe(), w::receipts);
        assertEquals(491_978L, open.getLong("settleTarget"), "the record names the target it waited for: " + open.encode());

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream was = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            NodeBenchMain.exitCode(new JsonObject().put("open", open));
        } finally {
            System.setErr(was);
        }
        String said = err.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("491978") && said.contains("PT10M"), said);
        assertTrue(said.contains("messages " + open.getLong("messages")) && said.contains("work " + open.getLong("work")), said);
    }

    @Test
    void aSettleThatLandsRecordsTheCountsReadOnceLanded(@TempDir Path dir) {
        NodeBenchMainOpenSettleTest.World w = new NodeBenchMainOpenSettleTest.World(TimeUnit.MILLISECONDS.toNanos(5));
        w.landedAfterPauses = 2;

        JsonObject open = NodeBenchMainOpenSettleTest.harness(NodeBenchMainOpenSettleTest.ackOpen(dir.resolve("r.json")))
                .settleOpen(NodeBenchMainOpenSettleTest.window(), 57, 0, NodeBenchMainOpenSettleTest.DRAIN, w.probe(), w::receipts);

        assertEquals(NodeBenchMainOpenSettleTest.MESSAGES_BASE + 2, open.getLong("messages"), open.encode());
        assertEquals(NodeBenchMainOpenSettleTest.WORK_BASE - 2, open.getLong("work"), open.encode());
        assertEquals(57L, open.getLong("settleTarget"), open.encode());
        assertEquals("PT10M", open.getString("settleDeadline"), open.encode());
    }

    @Test
    void syncWithRecordingLevelNoneIsRefusedNamingBothOptions() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> NodeBenchMain.options(new String[] {"--scenario", "sync", "--recording-level", "none"}));

        String m = refused.getMessage();
        assertTrue(m.contains("--scenario sync") && m.contains("--recording-level none"), m);
        assertEquals(NodeBenchMain.RecordingLevel.NONE,
                NodeBenchMain.options(new String[] {"--scenario", "echo", "--rate", "10", "--recording-level", "none"}).recordingLevel(),
                "only sync refuses none");
    }
}
