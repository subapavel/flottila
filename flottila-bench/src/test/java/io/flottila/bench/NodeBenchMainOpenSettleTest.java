package io.flottila.bench;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-124: the open loop's settle (preview 2026-11-05: ack 5000/s, the settle did not land within PT10M and the run wrote no
 * record). An open run's settle that misses its deadline no longer throws the record away: the {@code open} object carries
 * {@code settled: false}, the {@code settleDeadline} and the {@code messages} / {@code work} counts and the partner's
 * {@code receipts} read at the deadline; the record is written and the run exits {@value #NOT_SETTLED}, apart from success (0)
 * and a failed request (3). A settle that lands says {@code settled: true}. The fake world is {@link NodeBenchMainSettleTest}'s:
 * every read is a function of how many pauses have passed, so a value read at the deadline differs from one read earlier.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodeBenchMainOpenSettleTest {

    static final Duration DRAIN = Duration.ofMinutes(10);
    /** The exit code of a record whose settle did not land (FIX-124). */
    static final int NOT_SETTLED = 4;

    static final long MESSAGES_BASE = 400_000;
    static final long WORK_BASE = 90_000;
    static final long RECEIPTS_BASE = 300_000;

    /** The fake world: pauses advance the clock by {@code step} nanos; the counts move with every pause. */
    static final class World {
        final long step;
        long now = 7_000_000_000_123L;
        int pauses;
        int landedAfterPauses = Integer.MAX_VALUE;
        long lastAsked = -1;

        World(long step) {
            this.step = step;
        }

        long messages() {
            return MESSAGES_BASE + pauses;
        }

        long work() {
            return WORK_BASE - pauses;
        }

        long receipts() {
            return RECEIPTS_BASE + pauses;
        }

        NodeBenchMain.Probe probe() {
            return new NodeBenchMain.Probe(recorded -> {
                lastAsked = recorded;
                return pauses >= landedAfterPauses;
            }, () -> 0, () -> now, () -> {
                pauses++;
                now += step;
            }, this::messages, this::work);
        }
    }

    static NodeBenchMain.Options ackOpen(Path out) {
        return NodeBenchMain.options(new String[] {"--scenario", "ack", "--rate", "5000", "--out", out.toString()});
    }

    static NodeBenchMain.Harness harness(NodeBenchMain.Options o) {
        return new NodeBenchMain.Harness(o, null, null, null, null, 0, 0);
    }

    /** What the open loop hands the settle: a window without failed requests. */
    static JsonObject window() {
        return new JsonObject().put("sent", 3_000_000L).put("acceptedTotal", 490_978L).put("errors", 0L).put("timeouts", 0L)
                .put("refused503", 0L).put("clientSaturated", 0L).put("valid", true);
    }

    @Test
    void anOpenSettleThatMissesItsDeadlineReturnsTheRecordMarkedUnsettledWithTheCountsReadAtTheDeadline(@TempDir Path dir) {
        // one second per pause: the PT10M bound is 600 pauses; a millis/nanos slip ends after 0 or 600 000
        World w = new World(TimeUnit.SECONDS.toNanos(1));

        JsonObject open = harness(ackOpen(dir.resolve("r.json"))).settleOpen(window(), 491_978, 0, DRAIN, w.probe(), w::receipts);

        assertTrue(w.pauses == 600 || w.pauses == 601, "waited the whole bound on the probe's clock: " + w.pauses + " pauses");
        assertEquals(491_978, w.lastAsked, "the landed query asks for the recorded target");
        assertEquals(Boolean.FALSE, open.getBoolean("settled"), "the record says the settle did not land: " + open.encode());
        assertEquals("PT10M", open.getString("settleDeadline"), "the record names the bound it waited: " + open.encode());
        assertEquals(MESSAGES_BASE + w.pauses, open.getLong("messages"), "the messages count read at the deadline, not before: " + open.encode());
        assertEquals(WORK_BASE - w.pauses, open.getLong("work"), "the work count read at the deadline, not before: " + open.encode());
        assertEquals(RECEIPTS_BASE + w.pauses, open.getLong("receipts"), "the partner's receipts read at the deadline: " + open.encode());
        assertEquals(490_978L, open.getLong("acceptedTotal"), "the window's own fields stay in the record: " + open.encode());
        assertEquals(3_000_000L, open.getLong("sent"), open.encode());
    }

    @Test
    void anOpenSettleThatLandsReturnsTheRecordMarkedSettledWithTheReceiptsReadOnceLanded(@TempDir Path dir) {
        World w = new World(TimeUnit.MILLISECONDS.toNanos(5));
        w.landedAfterPauses = 3;

        JsonObject open = harness(ackOpen(dir.resolve("r.json"))).settleOpen(window(), 57, 0, DRAIN, w.probe(), w::receipts);

        assertEquals(3, w.pauses, "polled until the window landed, then returned");
        assertEquals(Boolean.TRUE, open.getBoolean("settled"), "the record says the settle landed: " + open.encode());
        assertEquals(RECEIPTS_BASE + 3, open.getLong("receipts"), "the partner's receipts read once settled: " + open.encode());
        assertEquals(490_978L, open.getLong("acceptedTotal"), open.encode());
    }

    @Test
    void anUnsettledOpenRecordIsWrittenAndItsRunExitsWithTheNotSettledCode(@TempDir Path dir) throws Exception {
        World w = new World(TimeUnit.SECONDS.toNanos(1));
        NodeBenchMain.Options o = ackOpen(dir.resolve("out").resolve("open.json"));
        JsonObject open = harness(o).settleOpen(window(), 491_978, 0, DRAIN, w.probe(), w::receipts);

        int code = NodeBenchMain.write(o, new JsonObject().put("name", "open").put("open", open));

        assertEquals(NOT_SETTLED, code, "a record whose settle did not land fails the run with its own code");
        assertTrue(Files.exists(o.out()), "the record is written before the run fails");
        JsonObject written = new JsonObject(Files.readString(o.out())).getJsonObject("open");
        assertEquals(Boolean.FALSE, written.getBoolean("settled"), written.encode());
        assertEquals(MESSAGES_BASE + w.pauses, written.getLong("messages"), written.encode());
        assertEquals(WORK_BASE - w.pauses, written.getLong("work"), written.encode());
    }

    @Test
    void theNotSettledExitCodeIsFourApartFromSuccessAndAFailedRequest() {
        assertEquals(NOT_SETTLED, NodeBenchMain.EXIT_NOT_SETTLED, "named in the class: the run.sh summary and the owner read it");
        assertNotEquals(0, NodeBenchMain.EXIT_NOT_SETTLED);
        assertNotEquals(3, NodeBenchMain.EXIT_NOT_SETTLED, "a failed request is 3: an unsettled run is told apart from it");

        assertEquals(NOT_SETTLED, NodeBenchMain.exitCode(new JsonObject().put("open", window().put("settled", false))),
                "an unsettled record without failed requests still fails the run");
        assertEquals(0, NodeBenchMain.exitCode(new JsonObject().put("open", window().put("settled", true))),
                "a settled record without failed requests is a success");
        assertEquals(3, NodeBenchMain.exitCode(new JsonObject().put("open", window().put("settled", true).put("errors", 2L)
                .put("firstFailure", "java.io.IOException: connection reset"))), "a settled record with errors fails as before");
    }

    @Test
    void theClosedLoopSettleStillFailsAtItsBound() {
        // the open run's settle returns at its deadline; the closed-loop windows' settle keeps failing there (FIX-67)
        World w = new World(TimeUnit.SECONDS.toNanos(1));
        NodeBenchMain.Harness h = new NodeBenchMain.Harness(NodeBenchMain.options(new String[] {"--scenario", "ack"}), null, null, null,
                null, 0, 0);

        IllegalStateException late = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> h.settle(57, 0, DRAIN, w.probe()));

        assertEquals("57 recorded messages and an empty work queue did not land within PT10M", late.getMessage());
        assertFalse(w.pauses < 600, "waited the bound: " + w.pauses);
    }
}
