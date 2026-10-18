package io.flottila.bench;

import io.flottila.core.batch.BatchSink;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The measurement core: N items over C virtual threads through the batcher into a sink; every item counted once. */
class BenchRunTest {

    @Test
    void everyItemIsSavedOnceAndTheReportCarriesTheConfiguration() {
        AtomicInteger items = new AtomicInteger();
        AtomicInteger batches = new AtomicInteger();
        BatchSink<String> sink = batch -> { items.addAndGet(batch.size()); batches.incrementAndGet(); return Future.succeededFuture(); };
        FakeTimer timer = new FakeTimer(FakeClock.at(Instant.EPOCH));
        BenchRun.Config cfg = new BenchRun.Config("unit", 1_000, 8, new BatchingWriter.Options(100, Duration.ofMillis(10), 4, 6_400));
        BenchRun.Report r = BenchRun.run(cfg, sink, timer, i -> "item-" + i);
        assertEquals(1_000, items.get());
        assertEquals(1_000, r.saved());
        assertEquals(0, r.failed());
        assertNull(r.firstFailure(), "no failure, no cause");
        assertTrue(batches.get() >= 10 && batches.get() <= 1_000, "between one commit per item and full batches: " + batches.get());
        assertTrue(r.elapsedNanos() > 0);
        assertTrue(r.throughputPerSecond() > 0);
        assertTrue(r.p50Millis() <= r.p95Millis() && r.p95Millis() <= r.p99Millis() && r.p99Millis() <= r.maxMillis());
        JsonObject json = r.toJson();
        assertEquals("unit", json.getString("name"));
        assertEquals(1_000, json.getInteger("messages"));
        assertEquals(8, json.getInteger("concurrency"));
        assertEquals(100, json.getJsonObject("batch").getInteger("size"));
        assertEquals(10, json.getJsonObject("batch").getInteger("windowMs"));
        assertEquals(4, json.getJsonObject("batch").getInteger("commitConcurrency"));
        for (String k : List.of("p50Ms", "p95Ms", "p99Ms", "maxMs", "meanMs", "throughputPerSecond", "elapsedMs")) assertTrue(json.containsKey(k), k);
    }

    @Test
    void aFailingSinkIsCountedNotHidden() {
        AtomicLong n = new AtomicLong();
        BatchSink<String> flaky = batch -> n.incrementAndGet() % 2 == 0 ? Future.failedFuture(new RuntimeException("db")) : Future.succeededFuture();
        FakeTimer timer = new FakeTimer(FakeClock.at(Instant.EPOCH));
        BenchRun.Config cfg = new BenchRun.Config("flaky", 50, 1, new BatchingWriter.Options(1, Duration.ZERO, 1, 64));
        BenchRun.Report r = BenchRun.run(cfg, flaky, timer, i -> "i" + i);
        assertEquals(25, r.failed());
        assertEquals("java.lang.RuntimeException: db", r.firstFailure(), "the first failed save's cause is kept for the operator");
        assertEquals("java.lang.RuntimeException: db", r.toJson().getString("firstFailure"));
        assertEquals(25, r.saved());
        assertEquals(50, r.toJson().getInteger("failed") + r.toJson().getInteger("saved"));
    }

    @Test
    void configRejectsNonPositiveCounts() {
        BatchingWriter.Options o = BatchingWriter.Options.defaults();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new BenchRun.Config("x", 0, 1, o));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new BenchRun.Config("x", 1, 0, o));
    }
}
