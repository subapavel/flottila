package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b (F-27 I1): the open record's {@code db} object ({@link DbSampler}) over a fake source, never a database. Each
 * {@code sample()} reads the source once: a cumulative reading as a JSON object — pg_stat_database {@code xactCommit},
 * pg_stat_wal {@code walSync} / {@code walSyncTimeMs} / {@code walFpi} / {@code walBytes}, pg_stat_user_tables of {@code work}
 * ({@code workDeadTup}, {@code workLiveTup}, {@code workHotUpd}, {@code workUpd}, {@code workLastAutovacuum},
 * {@code workLastAutoanalyze}), pg_stat_statements' {@code statements} ({@code queryid}, {@code query}, {@code calls},
 * {@code totalExecTimeMs}, {@code sharedBlksHit}, {@code sharedBlksRead}); a field the source could not read is null and its
 * reason is in {@code notes.<field>}. {@code series()} gives one entry per sample: a delta since the sample before for the
 * counters, the absolute value for the tuple counts; the autovacuum / autoanalyze times when they change; the window's top 12
 * statements by execution time. The first entry of a delta series (the window's start) is not pinned here: only its length.
 */
class DbSamplerTest {

    /** A cumulative reading of the counters, without statements or vacuum times. */
    static JsonObject reading(long xactCommit, long walSync, Double walSyncTimeMs, long walFpi, long walBytes, long workDeadTup,
            long workLiveTup, long workHotUpd, long workUpd) {
        return new JsonObject().put("xactCommit", xactCommit).put("walSync", walSync).put("walSyncTimeMs", walSyncTimeMs)
                .put("walFpi", walFpi).put("walBytes", walBytes).put("workDeadTup", workDeadTup).put("workLiveTup", workLiveTup)
                .put("workHotUpd", workHotUpd).put("workUpd", workUpd).putNull("workLastAutovacuum").putNull("workLastAutoanalyze")
                .put("statements", new JsonArray()).put("notes", new JsonObject());
    }

    /** One pg_stat_statements row, cumulative. */
    static JsonObject statement(long queryid, String query, long calls, double totalExecTimeMs, long hit, long read) {
        return new JsonObject().put("queryid", queryid).put("query", query).put("calls", calls).put("totalExecTimeMs", totalExecTimeMs)
                .put("sharedBlksHit", hit).put("sharedBlksRead", read);
    }

    /** A sampler over {@code readings} in order; samples each of them once and returns its series. */
    static JsonObject sampled(List<JsonObject> readings) {
        Iterator<JsonObject> it = readings.iterator();
        Supplier<JsonObject> source = it::next;
        DbSampler sampler = new DbSampler(source);
        for (int k = 0; k < readings.size(); k++) sampler.sample();
        assertFalse(it.hasNext(), "one read per sample");
        return sampler.series();
    }

    static List<Long> longs(JsonArray a) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) out.add(a.getValue(i) == null ? null : ((Number) a.getValue(i)).longValue());
        return out;
    }

    static List<Double> doubles(JsonArray a) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) out.add(a.getValue(i) == null ? null : ((Number) a.getValue(i)).doubleValue());
        return out;
    }

    /** {@code field} is a series of {@code samples} entries whose entries after the first are {@code deltas}. */
    static void assertDeltas(JsonObject db, String field, int samples, List<Long> deltas) {
        JsonArray series = db.getJsonArray(field);
        assertNotNull(series, field + " is a series: " + db.encode());
        assertEquals(samples, series.size(), field + " has one entry per sample: " + db.encode());
        assertEquals(deltas, longs(series).subList(1, samples), field + " entries are the deltas since the sample before: " + db.encode());
    }

    /** {@code field} is in {@code db} as JSON null (not 0, not absent), and {@code notes.<field>} carries the source's reason. */
    static void assertUnknown(JsonObject db, String field, String reason) {
        assertTrue(db.containsKey(field), field + " is in the record: " + db.encode());
        assertNull(db.getValue(field), field + " unknown is null, never 0: " + db.encode());
        JsonObject notes = db.getJsonObject("notes");
        assertNotNull(notes, "an unknown field has a note: " + db.encode());
        assertTrue(notes.getString(field, "").contains(reason), field + "'s note gives the reason: " + db.encode());
    }

    @Test
    void eachCounterIsADeltaPerSampleAndEachTupleCountItsAbsoluteValue() {
        // four samples: the window's start, 1 s, 2 s and its end; every field moves by its own amounts, so a swap shows
        JsonObject db = sampled(List.of(
                reading(1000, 50, 10.5, 7, 1_000_000, 0, 10, 3, 100),
                reading(1500, 80, 12.0, 7, 1_400_000, 40, 20, 5, 300),
                reading(2700, 81, 20.25, 19, 5_000_000, 4000, 30, 9, 900),
                reading(2710, 200, 20.5, 40, 5_000_123, 15, 5, 10, 901)));

        assertDeltas(db, "xactCommit", 4, List.of(500L, 1200L, 10L));
        assertDeltas(db, "walSync", 4, List.of(30L, 1L, 119L));
        assertDeltas(db, "walFpi", 4, List.of(0L, 12L, 21L));
        assertDeltas(db, "walBytes", 4, List.of(400_000L, 3_600_000L, 123L));
        assertDeltas(db, "workHotUpd", 4, List.of(2L, 4L, 1L));
        assertDeltas(db, "workUpd", 4, List.of(200L, 600L, 1L));
        JsonArray syncTime = db.getJsonArray("walSyncTimeMs");
        assertNotNull(syncTime, db.encode());
        assertEquals(4, syncTime.size(), db.encode());
        assertEquals(List.of(1.5, 8.25, 0.25), doubles(syncTime).subList(1, 4), "wal_sync_time deltas in ms: " + db.encode());

        assertEquals(List.of(0L, 40L, 4000L, 15L), longs(db.getJsonArray("workDeadTup")), "n_dead_tup as read, not a delta: " + db.encode());
        assertEquals(List.of(10L, 20L, 30L, 5L), longs(db.getJsonArray("workLiveTup")), "n_live_tup as read, not a delta: " + db.encode());
    }

    @Test
    void theWorkTablesAutovacuumAndAutoanalyzeTimesAreRecordedAtTheSampleWhereTheyChange() {
        String vacuum1 = "2026-11-05T10:00:02.5Z";
        String vacuum2 = "2026-11-05T10:00:04Z";
        String analyze = "2026-11-05T10:00:00.9Z";
        List<String> vacuums = java.util.Arrays.asList(null, null, vacuum1, vacuum1, vacuum2);
        List<String> analyzes = java.util.Arrays.asList(null, analyze, analyze, analyze, analyze);
        List<JsonObject> readings = new ArrayList<>();
        for (int k = 0; k < 5; k++) {
            readings.add(reading(k, k, (double) k, k, k, k, k, k, k).put("workLastAutovacuum", vacuums.get(k))
                    .put("workLastAutoanalyze", analyzes.get(k)));
        }

        JsonObject db = sampled(readings);

        assertEquals(new JsonArray().add(new JsonObject().put("sample", 2).put("at", vacuum1)).add(new JsonObject().put("sample", 4).put("at", vacuum2)),
                db.getJsonArray("workAutovacuum"), "each new last_autovacuum once, at the sample that saw it: " + db.encode());
        assertEquals(new JsonArray().add(new JsonObject().put("sample", 1).put("at", analyze)), db.getJsonArray("workAutoanalyze"),
                "an unchanged time is not repeated: " + db.encode());
    }

    @Test
    void walSyncTimeWithTimingOffAndStatementsWithoutTheExtensionAreNullWithTheSourcesNoteNeverZero() {
        JsonObject notes = new JsonObject().put("walSyncTimeMs", "track_wal_io_timing is off")
                .put("statements", "pg_stat_statements is not installed in flottila_bench");
        List<JsonObject> readings = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            readings.add(reading(100 + 10L * k, 5 + k, null, 1, 1000 + k, 0, 1, 0, k).putNull("statements").put("notes", notes.copy()));
        }

        JsonObject db = sampled(readings);

        assertUnknown(db, "walSyncTimeMs", "track_wal_io_timing is off");
        assertUnknown(db, "statements", "pg_stat_statements is not installed");
        assertDeltas(db, "xactCommit", 3, List.of(10L, 10L));   // the rest of the record is still measured
        assertDeltas(db, "walSync", 3, List.of(1L, 1L));
        assertFalse(db.getJsonObject("notes").containsKey("xactCommit"), "a measured field has no note: " + db.encode());
    }

    @Test
    void theStatementsAreTheTwelveWithTheMostExecutionTimeInTheWindowEachWithItsWindowDeltas() {
        // 14 statements; "s-old" has by far the most time since the server started but 1 ms in the window, "s-new" first runs in
        // the window; the rest gain 1300, 1200, ... ms in it
        List<JsonObject> first = new ArrayList<>();
        List<JsonObject> last = new ArrayList<>();
        first.add(statement(1, "s-old", 1_000_000, 100_000.0, 5_000_000, 1000));
        last.add(statement(1, "s-old", 1_000_001, 100_001.0, 5_000_010, 1000));
        last.add(statement(2, "s-new", 40, 650.0, 300, 20));
        for (int q = 3; q <= 14; q++) {
            double gain = 100.0 * (16 - q);   // q3 1300 ms ... q14 200 ms
            first.add(statement(q, "s-" + q, 10L * q, 5.0 * q, 100L * q, q));
            last.add(statement(q, "s-" + q, 10L * q + 1000 + q, 5.0 * q + gain, 100L * q + 7 * q, q + 3));
        }
        JsonObject middle = reading(2, 2, 2.0, 2, 2, 2, 2, 2, 2).put("statements", new JsonArray(new ArrayList<>(first)));
        JsonObject db = sampled(List.of(
                reading(1, 1, 1.0, 1, 1, 1, 1, 1, 1).put("statements", new JsonArray(new ArrayList<>(first))),
                middle,
                reading(3, 3, 3.0, 3, 3, 3, 3, 3, 3).put("statements", new JsonArray(new ArrayList<>(last)))));

        JsonArray top = db.getJsonArray("statements");
        assertNotNull(top, db.encode());
        List<String> queries = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) queries.add(top.getJsonObject(i).getString("query"));
        // by window time: s-3 1300, s-4 1200, s-5 1100, s-6 1000, s-7 900, s-8 800, s-9 700, s-new 650, s-10 600, s-11 500,
        // s-12 400, s-13 300 — then s-14 (200) and s-old (1) are left out
        assertEquals(List.of("s-3", "s-4", "s-5", "s-6", "s-7", "s-8", "s-9", "s-new", "s-10", "s-11", "s-12", "s-13"), queries,
                "the top 12 by execution time in the window, most first: " + top.encode());

        JsonObject s3 = top.getJsonObject(0);
        assertEquals(1003L, s3.getLong("calls"), "calls in the window: " + s3.encode());
        assertEquals(1300.0, s3.getDouble("totalExecTimeMs"), 1e-9, "execution time in the window: " + s3.encode());
        assertEquals(24L, s3.getLong("sharedBlks"), "shared blocks hit (21) + read (3) in the window: " + s3.encode());
        assertEquals(3L, s3.getLong("queryid"), s3.encode());
        JsonObject fresh = top.getJsonObject(7);
        assertEquals(40L, fresh.getLong("calls"), "a statement first seen in the window counts from zero: " + fresh.encode());
        assertEquals(650.0, fresh.getDouble("totalExecTimeMs"), 1e-9, fresh.encode());
        assertEquals(320L, fresh.getLong("sharedBlks"), fresh.encode());
    }
}
