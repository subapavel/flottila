package io.flottila.bench;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.flottila.bench.OpenLoopScheduleTest.assertNear;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15, the implementer's cases beside {@link OpenLoopTest} (same fake world): the architect's readings — the backlog counts
 * every accepted request, ramp included, while its growth % is over the window's accepted; a ramp request's saturation is not
 * the window's — plus a fractional schedule, a run without one accepted answer, and the window's start and end signals the run
 * reads the CPU on. Rate 10/s with a 1 s ramp dues requests 0..4 at 0, 447, 632, 775, 894 ms and 5..14 at 1000..1900 ms.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OpenLoopAccountingTest {

    @Test
    void theBacklogCountsTheRampsAcceptedRequestsButItsGrowthPercentIsOverTheWindowsAccepted() {
        OpenLoopTest.World w = new OpenLoopTest.World();   // every answer held until the case gives it
        w.start(10, Duration.ofSeconds(1), Duration.ofSeconds(1), OpenLoopTest.UNBOUNDED_FOR_THE_CASE, 202);
        w.stepTo(950);
        w.answerAllHeld(202);                // the ramp's 0..4 accepted
        w.receipts.set(1);
        w.stepTo(1950);                      // the window's start sample at 1000 ms: 5 − 1
        w.answerAllHeld(202);                // the window's 5..14 accepted → 15 in all
        w.receipts.set(3);
        w.stepTo(2500);                      // the window's end sample at 2000 ms: 15 − 3

        JsonObject open = w.done();
        assertEquals(List.of(4L, 12L), open.getJsonArray("backlog").stream().map(v -> ((Number) v).longValue()).toList(), open.encode());
        assertEquals(8, open.getNumber("backlogGrowth").longValue(), open.encode());
        assertEquals(10, open.getNumber("accepted").longValue(), "the window's own: " + open.encode());
        assertEquals(15, open.getNumber("acceptedTotal").longValue(), "ramp and window, what the backlog counts: " + open.encode());
        assertEquals(80.0, open.getNumber("backlogGrowthPercent").doubleValue(), 1e-9, "8 of the window's 10 accepted: " + open.encode());
    }

    @Test
    void aRampRequestThatFindsTheBoundFullIsNotTheWindowsSaturation() {
        OpenLoopTest.World w = new OpenLoopTest.World();
        w.start(10, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 202);   // request 0 holds the one slot through the ramp
        w.stepTo(950);
        assertEquals(List.of(0L), w.sent, "1..4 found the bound full");
        w.answerAllHeld(202);
        w.stepTo(1950);
        assertEquals(List.of(0L, 5L), w.sent, "5 took the freed slot; 6..14 found it full");
        w.answerAllHeld(202);
        w.stepTo(2500);

        JsonObject open = w.done();
        assertEquals(9, open.getNumber("clientSaturated").longValue(), "6..14, not the ramp's 1..4: " + open.encode());
        assertEquals(1, open.getNumber("sent").longValue(), open.encode());
        assertEquals(false, open.getBoolean("valid"), open.encode());
    }

    @Test
    void aRunWithoutOneAcceptedAnswerHasNullLatenciesAndGrowthPercentNeverZero() {
        OpenLoopTest.World w = new OpenLoopTest.World();
        w.answer = i -> Future.succeededFuture(503);
        w.start(10, Duration.ZERO, Duration.ofSeconds(1), OpenLoopTest.UNBOUNDED_FOR_THE_CASE, 202);
        w.stepTo(1500);

        JsonObject open = w.done();
        assertEquals(10, open.getNumber("refused503").longValue(), open.encode());
        assertEquals(0, open.getNumber("accepted").longValue(), open.encode());
        for (String field : List.of("p50Ms", "p99Ms", "p999Ms", "maxMs", "backlogGrowthPercent")) {
            assertTrue(open.containsKey(field), field + " is in the record: " + open.encode());
            assertNull(open.getValue(field), field + " is unknown without an accepted answer: " + open.encode());
        }
        assertEquals(true, open.getBoolean("valid"), "the client offered the rate; the refusals are what the run measured: " + open.encode());
    }

    @Test
    void aWindowWhoseRequestsAreNotAWholeRateTimesWindowIsValidWhenItSendsEveryOne() {
        OpenLoopTest.World w = new OpenLoopTest.World();
        w.answer = i -> Future.succeededFuture(202);
        w.start(3, Duration.ZERO, Duration.ofMillis(1500), OpenLoopTest.UNBOUNDED_FOR_THE_CASE, 202);   // 4.5 → i < 4.5: 0..4
        w.stepTo(2000);

        JsonObject open = w.done();
        assertEquals(5, open.getNumber("scheduled").longValue(), "due at 0, 333, 667, 1000, 1333 ms: " + open.encode());
        assertEquals(5, open.getNumber("sent").longValue(), open.encode());
        assertEquals(5 / 1.5, open.getNumber("achievedRate").doubleValue(), 1e-9, open.encode());
        assertEquals(true, open.getBoolean("valid"), "every scheduled request went out on time: " + open.encode());
        assertEquals(3, open.getJsonArray("backlog").size(), "the window's start, 1 s, its end at 1.5 s: " + open.encode());
    }

    @Test
    void theFirstFailedWindowRequestIsKeptAndALaterOneDoesNotReplaceIt() {
        OpenLoopTest.World w = new OpenLoopTest.World();
        w.answer = i -> switch ((int) i) {
            case 3 -> Future.failedFuture(new java.io.IOException("connection reset by request 3"));
            case 7 -> Future.failedFuture(new java.io.IOException("connection refused at request 7"));
            default -> Future.succeededFuture(202);
        };
        w.start(10, Duration.ZERO, Duration.ofSeconds(1), OpenLoopTest.UNBOUNDED_FOR_THE_CASE, 202);
        w.stepTo(1500);

        JsonObject open = w.done();
        assertEquals(2, open.getNumber("errors").longValue(), open.encode());
        assertTrue(open.getString("firstFailure").contains("request 3"), "the first error's text: " + open.encode());
    }

    @Test
    void aFractionalRampsLastRequestIsTheLastOneBeforeTheRampsEnd() {
        OpenLoop.Schedule s = new OpenLoop.Schedule(3, Duration.ofSeconds(1), Duration.ofSeconds(1));   // rate × ramp / 2 = 1.5
        assertEquals(2, s.rampRequests(), "i < 1.5: 0 and 1");
        assertEquals(3, s.windowRequests(), "1.5 ≤ i < 4.5: 2, 3, 4");
        assertNear(816_496_581, s.dueNanos(1), "sqrt(2 · 1 s · 1 / 3) = 0.8164966 s");
        assertNear(1_166_666_667, s.dueNanos(2), "1 s + (2 − 1.5) / 3 s");
        assertFalse(s.inWindow(1));
        assertTrue(s.inWindow(2));
    }

    @Test
    void theWindowsStartAndEndAreSignalledWhenTheyPassAndTheRecordWaitsForTheLastAnswer() {
        OpenLoopTest.World w = new OpenLoopTest.World();
        w.start(10, Duration.ofSeconds(1), Duration.ofSeconds(1), OpenLoopTest.UNBOUNDED_FOR_THE_CASE, 202);
        w.stepTo(999.5);
        assertFalse(w.loop.windowStart().isComplete(), "the ramp is not the window");
        w.stepTo(1000.5);
        assertTrue(w.loop.windowStart().isComplete(), "the window started at 1 s");
        w.stepTo(1999.5);
        assertFalse(w.loop.windowEnd().isComplete());
        w.stepTo(2000.5);
        assertTrue(w.loop.windowEnd().isComplete(), "the window ended at 2 s, its answers still held");
        assertFalse(w.loop.done().isComplete(), "15 answers outstanding");
        w.answerAllHeld(202);
        assertEquals(10, w.done().getNumber("accepted").longValue());
    }
}
