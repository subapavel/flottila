package io.flottila.bench;

import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b: the open loop's per-second series are taken with its backlog sampler — at the window's start, each second after it
 * and at its end — through {@link OpenLoop.Sampler}s handed to the loop by name; each sampler's {@code series()} goes into the
 * record under its name, so a series has one entry per backlog sample. Fake clock and timer ({@link OpenLoopTest}'s world: the
 * clock moves in 0.5 ms steps, which land exactly on each whole-millisecond sample instant), never a sleep.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OpenLoopSamplerTest {

    /** A sampler that notes when (ms after the loop's start) each sample was taken, and how many it took. */
    static final class Noting implements OpenLoop.Sampler {
        final FakeClock clock;
        final List<Double> at = new ArrayList<>();

        Noting(FakeClock clock) {
            this.clock = clock;
        }

        @Override public void sample() {
            at.add(Duration.between(OpenLoopTest.START, clock.instant()).toNanos() / 1_000_000.0);
        }

        @Override public JsonObject series() {
            return new JsonObject().put("samples", at.size());
        }
    }

    static OpenLoop loop(FakeClock clock, FakeTimer timer, int rate, Duration ramp, Duration window, Map<String, OpenLoop.Sampler> samplers) {
        return new OpenLoop(new OpenLoop.Schedule(rate, ramp, window), OpenLoopTest.UNBOUNDED_FOR_THE_CASE, 202, clock, timer,
                i -> Future.succeededFuture(202), () -> 0, samplers);
    }

    /** Advances in 0.5 ms steps to {@code ms} after the start (the clock starts at the loop's start). */
    static void stepTo(FakeClock clock, double ms) {
        long target = Math.round(ms * 1000);
        long now = Duration.between(OpenLoopTest.START, clock.instant()).toNanos() / 1000;
        while (now < target) {
            long by = Math.min(500, target - now);
            clock.advance(Duration.ofNanos(by * 1000));
            now += by;
        }
    }

    static JsonObject done(OpenLoop loop) {
        Future<JsonObject> done = loop.done();
        assertTrue(done.isComplete(), "the run is done once the window ended and every request was answered");
        assertTrue(done.succeeded(), () -> "the run's record: " + done.cause());
        return done.result();
    }

    @Test
    void everySamplerIsSampledWithTheBacklogAtTheWindowsStartEachSecondAndItsEndNeverInTheRamp() {
        FakeClock clock = FakeClock.at(OpenLoopTest.START);
        FakeTimer timer = new FakeTimer(clock);
        Noting db = new Noting(clock);
        Noting io = new Noting(clock);
        Map<String, OpenLoop.Sampler> samplers = new LinkedHashMap<>();
        samplers.put("db", db);
        samplers.put("io", io);
        // ramp 0.5 s, window 1.5 s: samples at the window's start (500 ms), 1 s into it (1500 ms) and at its end (2000 ms)
        OpenLoop loop = loop(clock, timer, 10, Duration.ofMillis(500), Duration.ofMillis(1500), samplers);
        loop.start();

        stepTo(clock, 499.5);
        assertEquals(List.of(), db.at, "nothing is sampled during the ramp");
        stepTo(clock, 2600);

        assertEquals(List.of(500.0, 1500.0, 2000.0), db.at, "sampled at the window's start, each second after it and at its end");
        assertEquals(db.at, io.at, "every sampler is sampled at each backlog sample");
        JsonObject open = done(loop);
        JsonArray backlog = open.getJsonArray("backlog");
        assertEquals(3, backlog.size(), open.encode());
        assertEquals(new JsonObject().put("samples", 3), open.getJsonObject("db"), "the record carries each sampler's series by name: "
                + open.encode());
        assertEquals(new JsonObject().put("samples", 3), open.getJsonObject("io"), open.encode());
    }

    @Test
    void aDatabaseSeriesInTheRecordHasOneDeltaPerBacklogSample() {
        FakeClock clock = FakeClock.at(OpenLoopTest.START);
        FakeTimer timer = new FakeTimer(clock);
        AtomicInteger reads = new AtomicInteger();
        // the database's xact_commit at each read: 1000 at the window's start, then 1300, 2300, 2350 at its end (2.5 s)
        Iterator<Long> commits = List.of(1000L, 1300L, 2300L, 2350L).iterator();
        DbSampler db = new DbSampler(() -> {
            reads.incrementAndGet();
            return DbSamplerTest.reading(commits.next(), 1, 1.0, 1, 1, 1, 1, 1, 1);
        });
        OpenLoop loop = loop(clock, timer, 10, Duration.ZERO, Duration.ofMillis(2500), Map.of("db", db));
        loop.start();
        stepTo(clock, 3000);

        JsonObject open = done(loop);
        assertEquals(4, reads.get(), "one database read per backlog sample (0, 1, 2 s and the end at 2.5 s): " + open.encode());
        JsonObject series = open.getJsonObject("db");
        assertNotNull(series, "the record carries the db object: " + open.encode());
        JsonArray xact = series.getJsonArray("xactCommit");
        assertEquals(open.getJsonArray("backlog").size(), xact.size(), "one entry per backlog sample: " + open.encode());
        assertEquals(List.of(300L, 1000L, 50L), DbSamplerTest.longs(xact).subList(1, 4), "each the delta since the sample before: "
                + series.encode());
    }
}
