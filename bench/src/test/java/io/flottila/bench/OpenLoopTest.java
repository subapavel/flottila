package io.flottila.bench;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15: the open-loop scheduler ({@link OpenLoop}) on a fake clock and timer, never a sleep. The target is a function of the
 * request's index that answers with a status (a Vert.x future the case completes on its own thread, or never); the partner's
 * receipts are a counter the case sets. The clock moves in 0.5 ms steps unless a case jumps on purpose (a timer that fires
 * late); every checkpoint sits at least 0.5 ms away from a due time, so a scheduler that sends on a 1 ms tick passes as one
 * that sends at each request's own instant. Expected values are worked out by hand from the schedule
 * ({@link OpenLoopScheduleTest}): rate 10/s without a ramp dues request i at i × 100 ms.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OpenLoopTest {

    /** Starts at .850 s: a nanos clock built from seconds and nanos is wrong unless an interval crosses a second. */
    static final Instant START = Instant.parse("2026-11-05T10:00:00.850Z");
    static final long UNBOUNDED_FOR_THE_CASE = 1_000_000;

    /** One scheduler in a fake world. */
    static final class World {
        final FakeClock clock = FakeClock.at(START);
        final FakeTimer timer = new FakeTimer(clock);
        final List<Long> sent = new ArrayList<>();
        final Map<Long, Promise<Integer>> held = new TreeMap<>();
        final AtomicLong receipts = new AtomicLong();
        /** How the target answers request i; by default it holds the answer until the case gives it. */
        LongFunction<Future<Integer>> answer = this::hold;
        long nowMicros;
        OpenLoop loop;

        World start(int rate, Duration ramp, Duration window, long maxOutstanding, int acceptedStatus) {
            loop = new OpenLoop(new OpenLoop.Schedule(rate, ramp, window), maxOutstanding, acceptedStatus, clock, timer, i -> {
                sent.add(i);
                return answer.apply(i);
            }, receipts::get);
            loop.start();
            return this;
        }

        Future<Integer> hold(long i) {
            Promise<Integer> p = Promise.promise();
            held.put(i, p);
            return p.future();
        }

        /** Advances in 0.5 ms steps to {@code ms} after the start. */
        void stepTo(double ms) {
            long target = Math.round(ms * 1000);
            while (nowMicros < target) {
                long by = Math.min(500, target - nowMicros);
                clock.advance(Duration.ofNanos(by * 1000));
                nowMicros += by;
            }
        }

        /** One advance to {@code ms} after the start: whatever fell due in between fires late, at {@code ms}. */
        void jumpTo(double ms) {
            long target = Math.round(ms * 1000);
            clock.advance(Duration.ofNanos((target - nowMicros) * 1000));
            nowMicros = target;
        }

        void answer(long i, int status) {
            held.remove(i).complete(status);
        }

        void answerAllHeld(int status) {
            for (long i : List.copyOf(held.keySet())) answer(i, status);
        }

        JsonObject done() {
            Future<JsonObject> done = loop.done();
            assertTrue(done.isComplete(), "the run is done once the window ended and every request sent has its answer; held "
                    + held.keySet());
            assertTrue(done.succeeded(), () -> "the run's record: " + done.cause());
            return done.result();
        }
    }

    @Test
    void requestsAreSentWhenDueRampingLinearlyThenAtTheConstantRateAndNothingAfterTheWindow() {
        World w = new World();
        w.answer = i -> Future.succeededFuture(202);
        w.start(1000, Duration.ofSeconds(1), Duration.ofSeconds(1), UNBOUNDED_FOR_THE_CASE, 202);

        // ms after the start → requests sent by then: t_i = sqrt(i/500) s in the ramp, 1 s + (i − 500) ms after it
        double[][] checkpoints = {{105, 6}, {499.5, 125}, {999.5, 500}, {1000.5, 501}, {1001.5, 502}, {1499.5, 1000}, {1999.5, 1500},
                {2000.5, 1500}, {3000.5, 1500}};
        for (double[] c : checkpoints) {
            w.stepTo(c[0]);
            assertEquals((long) c[1], w.sent.size(), "requests sent by " + c[0] + " ms");
        }
        assertEquals(LongStream.range(0, 1500).boxed().toList(), w.sent, "each scheduled request sent once, in order");

        JsonObject open = w.done();
        assertEquals(1000, open.getNumber("offeredRate").doubleValue(), 1e-9);
        assertEquals(1000, open.getNumber("scheduled").longValue(), "rate × window in the window: " + open.encode());
        assertEquals(1000, open.getNumber("sent").longValue(), open.encode());
        assertEquals(1000, open.getNumber("accepted").longValue(), open.encode());
        assertEquals(1000, open.getNumber("achievedRate").doubleValue(), 1e-9, "1000 sent in the 1 s window: " + open.encode());
        assertEquals(0, open.getNumber("clientSaturated").longValue(), open.encode());
        assertEquals(true, open.getBoolean("valid"), open.encode());
    }

    @Test
    void aStalledTargetNeverDelaysALaterSend() {
        World w = new World();   // the target never answers
        w.start(100, Duration.ZERO, Duration.ofSeconds(1), UNBOUNDED_FOR_THE_CASE, 202);
        for (int k = 0; k < 100; k++) {
            w.stepTo(k * 10 + 5);
            assertEquals(k + 1, w.sent.size(), "at " + (k * 10 + 5) + " ms every request due by then was sent, none answered");
        }
        assertEquals(LongStream.range(0, 100).boxed().toList(), w.sent);
        assertEquals(100, w.held.size(), "all 100 in flight at once: the loop never waited for an answer");
    }

    @Test
    void latencyRunsFromTheScheduledSendTimeSoALateSendCountsItsDelay() {
        World w = new World();
        w.answer = i -> i == 0 ? Future.succeededFuture(202) : w.hold(i);
        w.start(2, Duration.ZERO, Duration.ofSeconds(1), UNBOUNDED_FOR_THE_CASE, 202);   // due at 0 and 500 ms

        w.stepTo(499.5);
        assertEquals(List.of(0L), w.sent, "only the first request is due before 500 ms");
        w.jumpTo(530);                       // the send due at 500 ms goes out 30 ms late
        assertEquals(List.of(0L, 1L), w.sent, "the late send went out when the clock reached it");
        w.stepTo(535);                       // the target serves it in 5 ms
        w.answer(1, 202);
        w.stepTo(1500);

        JsonObject open = w.done();
        assertEquals(35.0, open.getNumber("maxMs").doubleValue(), 0.05,
                "30 ms late + 5 ms service, from the scheduled time (no coordinated omission): " + open.encode());
        assertEquals(35.0, open.getNumber("p999Ms").doubleValue(), 0.05, open.encode());
        assertEquals(35.0, open.getNumber("p99Ms").doubleValue(), 0.05, open.encode());
        assertTrue(open.getNumber("p50Ms").doubleValue() <= 0.55, "the first request answered at once, sent within a step: " + open.encode());
        assertEquals(2, open.getNumber("accepted").longValue(), open.encode());
    }

    @Test
    void aRequestDueWhileTheBoundIsFullIsCountedSaturatedNeverSentLaterAndTheRunIsInvalid() {
        World w = new World();
        w.start(10, Duration.ZERO, Duration.ofSeconds(1), 3, 202);   // due every 100 ms, at most 3 in flight

        w.stepTo(450);
        assertEquals(List.of(0L, 1L, 2L), w.sent, "3 and 4 (due at 300 and 400 ms) found the bound full");
        w.answer(0, 202);                    // a slot frees at 450 ms
        w.stepTo(1100);
        assertEquals(List.of(0L, 1L, 2L, 5L), w.sent, "the freed slot takes the next request due (500 ms); 3 and 4 are never sent");
        w.answerAllHeld(202);

        JsonObject open = w.done();
        assertEquals(10, open.getNumber("scheduled").longValue(), open.encode());
        assertEquals(4, open.getNumber("sent").longValue(), open.encode());
        assertEquals(6, open.getNumber("clientSaturated").longValue(), "3, 4, 6, 7, 8, 9: " + open.encode());
        assertEquals(4, open.getNumber("accepted").longValue(), open.encode());
        assertEquals(false, open.getBoolean("valid"), open.encode());
        assertTrue(reasons(open).contains("clientSaturated"), "the run says why it is invalid: " + open.encode());
    }

    @Test
    void oneSaturatedRequestInvalidatesARunWhoseAchievedRateIsWithinOnePercent() {
        World w = new World();
        w.answer = i -> i == 10 ? w.hold(i) : Future.succeededFuture(202);
        w.start(1000, Duration.ZERO, Duration.ofSeconds(1), 1, 202);   // one in flight; request 10 is held 1.5 ms
        w.stepTo(11.5);
        w.answer(10, 202);
        w.stepTo(1500);

        JsonObject open = w.done();
        assertEquals(1, open.getNumber("clientSaturated").longValue(), "request 11 (due at 11 ms) found 10 in flight: " + open.encode());
        assertEquals(999, open.getNumber("sent").longValue(), open.encode());
        assertEquals(999, open.getNumber("achievedRate").doubleValue(), 1e-9, "0.1 % short of the offered rate: " + open.encode());
        assertEquals(false, open.getBoolean("valid"), "saturation alone invalidates the run: " + open.encode());
        assertTrue(reasons(open).contains("clientSaturated"), open.encode());
        assertFalse(reasons(open).contains("achievedRate"), "the achieved rate is within 1 %: " + open.encode());
    }

    @Test
    void anAchievedRateMoreThanOnePercentOffInvalidatesTheRunAndOneWithinDoesNot() {
        // sends that fire after the window's end (a timer late by 30 ms) are not sent in the window
        World late = new World();
        late.answer = i -> Future.succeededFuture(202);
        late.start(1000, Duration.ZERO, Duration.ofSeconds(1), UNBOUNDED_FOR_THE_CASE, 202);
        late.stepTo(980.5);
        late.jumpTo(1010.5);
        late.stepTo(1500);
        JsonObject off = late.done();
        assertEquals(981, off.getNumber("achievedRate").doubleValue(), 1e-9, "0..980 sent in the window: " + off.encode());
        assertEquals(0, off.getNumber("clientSaturated").longValue(), off.encode());
        assertEquals(false, off.getBoolean("valid"), "1.9 % short: " + off.encode());
        assertTrue(reasons(off).contains("achievedRate"), "the run says why it is invalid: " + off.encode());

        World near = new World();
        near.answer = i -> Future.succeededFuture(202);
        near.start(1000, Duration.ZERO, Duration.ofSeconds(1), UNBOUNDED_FOR_THE_CASE, 202);
        near.stepTo(995.5);
        near.jumpTo(1005.5);
        near.stepTo(1500);
        JsonObject within = near.done();
        assertEquals(996, within.getNumber("achievedRate").doubleValue(), 1e-9, "0..995 sent in the window: " + within.encode());
        assertEquals(true, within.getBoolean("valid"), "0.4 % short is within 1 %: " + within.encode());
        assertEquals(0, within.getJsonArray("invalidReasons").size(), "a valid run lists no reason: " + within.encode());
    }

    @Test
    void onlyRequestsScheduledInsideTheWindowCountInTheWindowFigures() {
        World w = new World();
        // ramp: rate 10 over 1 s → 5 requests (due 0, 447, 632, 775, 894 ms); window: 10 requests, 1.0 .. 1.9 s
        w.answer = i -> switch ((int) i) {
            case 0, 1 -> Future.succeededFuture(503);
            case 2 -> Future.succeededFuture(500);
            case 3 -> Future.failedFuture(new IOException("connection reset"));
            case 4 -> w.hold(i);
            default -> Future.succeededFuture(202);
        };
        w.start(10, Duration.ofSeconds(1), Duration.ofSeconds(1), UNBOUNDED_FOR_THE_CASE, 202);
        w.stepTo(1800);
        w.answer(4, 202);                    // a ramp request accepted inside the window, ~906 ms after it was due
        w.stepTo(2500);
        assertEquals(15, w.sent.size(), "the ramp's 5 and the window's 10 were sent");

        JsonObject open = w.done();
        assertEquals(10, open.getNumber("scheduled").longValue(), open.encode());
        assertEquals(10, open.getNumber("sent").longValue(), open.encode());
        assertEquals(10, open.getNumber("accepted").longValue(), "the ramp's late 202 is not the window's: " + open.encode());
        assertEquals(0, open.getNumber("refused503").longValue(), open.encode());
        assertEquals(Set.of(), open.getJsonObject("refused").fieldNames(), open.encode());
        assertEquals(0, open.getNumber("errors").longValue(), open.encode());
        assertEquals(0, open.getNumber("timeouts").longValue(), open.encode());
        assertTrue(open.getNumber("maxMs").doubleValue() <= 0.55, "only the window's latencies: " + open.encode());
        assertEquals(10, open.getNumber("achievedRate").doubleValue(), 1e-9, open.encode());
        assertEquals(true, open.getBoolean("valid"), open.encode());
    }

    @Test
    void eachAnswerIsCountedByItsKindAcceptedRefusedByStatus503ApartTimeoutsAndErrors() {
        World w = new World();
        w.answer = i -> switch ((int) i) {
            case 0, 1, 2, 3 -> Future.succeededFuture(202);
            case 4, 5 -> Future.succeededFuture(503);
            case 6 -> Future.succeededFuture(429);
            case 7 -> Future.succeededFuture(500);
            case 8 -> Future.failedFuture(new TimeoutException("no answer within the client's timeout"));
            default -> Future.failedFuture(new IOException("connection reset"));
        };
        w.start(10, Duration.ZERO, Duration.ofSeconds(1), UNBOUNDED_FOR_THE_CASE, 202);
        w.stepTo(1500);

        JsonObject open = w.done();
        assertEquals(10, open.getNumber("sent").longValue(), open.encode());
        assertEquals(4, open.getNumber("accepted").longValue(), open.encode());
        assertEquals(2, open.getNumber("refused503").longValue(), "503, the flow's admission, apart: " + open.encode());
        JsonObject refused = open.getJsonObject("refused");
        assertEquals(Set.of("429", "500"), refused.fieldNames(), "every other status by its code: " + open.encode());
        assertEquals(1, refused.getNumber("429").longValue());
        assertEquals(1, refused.getNumber("500").longValue());
        assertEquals(1, open.getNumber("timeouts").longValue(), open.encode());
        assertEquals(1, open.getNumber("errors").longValue(), "a failed request that is not a timeout: " + open.encode());
    }

    @Test
    void theBacklogIsAcceptedMinusReceiptsSampledAtTheWindowsStartEachSecondAndItsEnd() {
        World w = new World();
        w.start(10, Duration.ZERO, Duration.ofSeconds(3), UNBOUNDED_FOR_THE_CASE, 202);
        // answers and receipts move only between the seconds, so every sample (0, 1, 2, 3 s) reads settled values
        w.stepTo(550);
        w.answerAllHeld(202);                // requests 0..5 accepted
        w.receipts.set(2);
        w.stepTo(1550);
        w.answerAllHeld(202);                // 6..15 → 16 accepted
        w.receipts.set(9);
        w.stepTo(2550);
        w.answerAllHeld(202);                // 16..25 → 26 accepted
        w.receipts.set(15);
        w.stepTo(3550);
        w.answerAllHeld(202);                // 26..29 after the window's end → 30 accepted
        w.receipts.set(30);
        w.stepTo(4000);

        JsonObject open = w.done();
        JsonArray backlog = open.getJsonArray("backlog");
        assertEquals(List.of(0L, 4L, 7L, 11L), backlog.stream().map(v -> ((Number) v).longValue()).toList(),
                "0 − 0 at the start, 6 − 2, 16 − 9, 26 − 15 at the end: " + open.encode());
        assertEquals(11, open.getNumber("backlogGrowth").longValue(), "the end's sample minus the start's: " + open.encode());
        assertEquals(30, open.getNumber("accepted").longValue(), open.encode());
        assertEquals(36.67, open.getNumber("backlogGrowthPercent").doubleValue(), 0.01, "11 of the 30 accepted: " + open.encode());
    }

    /** The record's {@code invalidReasons}, joined. */
    static String reasons(JsonObject open) {
        JsonArray r = open.getJsonArray("invalidReasons");
        assertTrue(r != null, "the record lists why it is invalid (empty when valid): " + open.encode());
        return String.join(" | ", r.stream().map(String::valueOf).toList());
    }
}
