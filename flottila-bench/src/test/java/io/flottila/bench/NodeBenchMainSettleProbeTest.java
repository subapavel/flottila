package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b (F-27): the open settle's probe no longer counts {@code distinct id} over every message each 5 ms. Its landed check
 * ({@link NodeBenchMain#landed(java.util.function.BooleanSupplier, java.util.function.LongSupplier)}) asks first whether
 * {@code work} is empty (cheap) and counts the recorded messages only then, once per check; one check per pause, and the pause
 * is one second ({@link NodeBenchMain#SETTLE_PAUSE}). The world is {@link NodeBenchMainOpenSettleTest}'s: every read is a
 * function of how many pauses have passed; each call is logged in order.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodeBenchMainSettleProbeTest {

    static final Duration DRAIN = Duration.ofMinutes(10);

    /** Work empty from pause {@code workEmptyAfter} on; {@code target} messages recorded from pause {@code recordedAfter} on. */
    static final class World {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        long now = 7_000_000_000_123L;
        int pauses;
        int workEmptyAfter = Integer.MAX_VALUE;
        int recordedAfter = Integer.MAX_VALUE;
        final long target;

        World(long target) {
            this.target = target;
        }

        NodeBenchMain.Probe probe() {
            LongPredicate landed = NodeBenchMain.landed(() -> {
                calls.add("work");
                return pauses >= workEmptyAfter;
            }, () -> {
                calls.add("messages");
                return pauses >= recordedAfter ? target : target - 1;
            });
            return new NodeBenchMain.Probe(landed, () -> 0, () -> now, () -> {
                calls.add("pause");
                pauses++;
                now += NodeBenchMain.SETTLE_PAUSE.toNanos();
            }, () -> target, () -> 0);
        }
    }

    static NodeBenchMain.Harness harness(Path dir) {
        return new NodeBenchMain.Harness(NodeBenchMain.options(new String[] {"--scenario", "ack", "--rate", "2000", "--out",
                dir.resolve("r.json").toString()}), null, null, null, null, 0, 0);
    }

    @Test
    void eachProbeChecksWorkFirstAndCountsTheMessagesOnlyOnceWorkIsEmptyOneProbePerPause(@TempDir Path dir) {
        World w = new World(100);
        w.workEmptyAfter = 2;
        w.recordedAfter = 4;

        JsonObject open = harness(dir).settleOpen(NodeBenchMainOpenSettleTest.window(), 100, 0, DRAIN, w.probe(), () -> 0);

        assertEquals(Boolean.TRUE, open.getBoolean("settled"), open.encode());
        assertEquals(List.of("work", "pause", "work", "pause", "work", "messages", "pause", "work", "messages", "pause", "work", "messages"),
                w.calls, "work first; the messages counted once per probe, only once work is empty; one probe per pause");
    }

    @Test
    void aWorkQueueThatNeverEmptiesIsNeverFollowedByAMessageCountAndThePauseIsOneSecond(@TempDir Path dir) {
        assertEquals(Duration.ofSeconds(1), NodeBenchMain.SETTLE_PAUSE, "one second between probes, not 5 ms");
        World w = new World(100);
        w.recordedAfter = 0;   // every message is recorded at once: only the work queue holds the settle back

        JsonObject open = harness(dir).settleOpen(NodeBenchMainOpenSettleTest.window(), 100, 0, DRAIN, w.probe(), () -> 0);

        assertEquals(Boolean.FALSE, open.getBoolean("settled"), open.encode());
        assertTrue(w.pauses == 600 || w.pauses == 601, "PT10M of one-second pauses: " + w.pauses);
        assertEquals(0, Collections.frequency(w.calls, "messages"), "no message count while work is not empty");
        assertEquals(w.pauses + 1, Collections.frequency(w.calls, "work"), "one work check per probe, one probe per pause and a first");
    }

    @Test
    void aProbeLandsWhenWorkIsEmptyAndTheRecordedCountReachesTheTarget() {
        World w = new World(100);
        w.workEmptyAfter = 0;
        w.recordedAfter = 0;
        NodeBenchMain.Probe probe = w.probe();

        assertTrue(probe.landed().test(100), "work empty and 100 recorded: landed");
        assertEquals(false, probe.landed().test(101), "work empty and 100 of 101 recorded: not landed");
        w.workEmptyAfter = 1;
        assertEquals(false, probe.landed().test(100), "100 recorded but work not empty: not landed");
    }
}
