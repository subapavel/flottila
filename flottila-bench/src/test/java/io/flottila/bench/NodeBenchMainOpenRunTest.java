package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15, the implementer's cases on the open run's edges: the architect's option readings (worker refuses {@code --rate},
 * echo needs it), the exit code of an open record (errors and timeouts fail it, refusals do not), the node's CPU reading from
 * its cpu answer, and the 99.9th percentile's nearest rank.
 */
class NodeBenchMainOpenRunTest {

    @Test
    void theWorkerScenarioRefusesARateAndTheEchoScenarioNeedsOneBothNamingRate() {
        NodeBenchMainOpenOptionsTest.refused("--rate", "--scenario", "worker", "--rate", "100");
        IllegalArgumentException echo = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> NodeBenchMain.options(new String[] {"--scenario", "echo"}));
        assertTrue(echo.getMessage().contains("--rate"), echo.getMessage());
    }

    @Test
    void anOpenRecordFailsOnItsErrorsAndTimeoutsButNotOnItsRefusals() {
        assertEquals(0, NodeBenchMain.exitCode(record(0, 0, 25)), "503s and other refusals are the measurement");
        assertEquals(3, NodeBenchMain.exitCode(record(1, 0, 0)), "an error fails the run");
        assertEquals(3, NodeBenchMain.exitCode(record(0, 1, 0)), "a timeout fails the run");
        assertEquals(0, NodeBenchMain.exitCode(new JsonObject().put("failed", 0).put("messages", 10)), "the closed loop's record as before");
        assertEquals(3, NodeBenchMain.exitCode(new JsonObject().put("failed", 2).put("messages", 10)), "the closed loop's record as before");
    }

    @Test
    void theNodesCpuIsItsFlowsAttributedStepCpuWhileSamplingIsOnElseUnreadableWithTheReason() {
        JsonObject on = new JsonObject().put("sampling", "on").put("items", new JsonArray()
                .add(new JsonObject().put("step", 0).put("cpuNanos", 5_000_000L)).add(new JsonObject().put("step", 1).put("cpuNanos", 7_000_000L)));
        CpuSource.Reading r = NodeBenchMain.Harness.nodeCpu(on);
        assertNull(r.failure(), String.valueOf(r));
        assertEquals(12_000_000L, r.nanos(), "the sum of the items' cpuNanos");

        for (String sampling : new String[] {"off", "failed"}) {
            CpuSource.Reading unread = NodeBenchMain.Harness.nodeCpu(new JsonObject().put("sampling", sampling).put("items", new JsonArray()));
            assertTrue(unread.failure() != null && unread.failure().contains(sampling), "names the sampler's state: " + unread);
            JsonObject open = new JsonObject();
            CpuSource.put(open, "node", unread, unread);
            CpuSourceTest.assertUnmeasured(open, "node");
        }
    }

    @Test
    void theNinetyNinePointNinthPercentileOfAThousandSamplesIsTheNineHundredNinetyNinth() {
        LatencyStats s = new LatencyStats(1000);
        LongStream.rangeClosed(1, 1000).forEach(ms -> s.record(ms * 1_000_000));
        assertEquals(999.0, s.percentileMillis(99.9), 1e-9, "rank ceil(99.9 % × 1000) = 999, not one above it");
        assertEquals(990.0, s.percentileMillis(99), 1e-9);
    }

    static JsonObject record(long errors, long timeouts, long refused503) {
        return new JsonObject().put("open", new JsonObject().put("sent", 100).put("errors", errors).put("timeouts", timeouts)
                .put("refused503", refused503).put("firstFailure", errors > 0 ? "java.io.IOException: connection reset" : null));
    }
}
