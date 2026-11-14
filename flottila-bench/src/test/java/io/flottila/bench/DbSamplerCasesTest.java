package io.flottila.bench;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CORE-15b, the implementer's cases on {@link DbSampler}: the window's start is every delta series' baseline (decision 1: its
 * first entry is 0), a sample that could not be read leaves its entries null with the reason while the rest stays measured,
 * the statements need both ends of the window and a call in it, a vacuum time the window's start already saw is not a change; and the reading
 * {@link DbSampler.PgSource}'s statement gives, column by column ({@link DbSampler#reading}).
 */
class DbSamplerCasesTest {

    @Test
    void everyDeltaSeriesStartsAtZeroTheWindowsStartBeingItsBaseline() {
        JsonObject db = DbSamplerTest.sampled(List.of(DbSamplerTest.reading(1000, 50, 10.5, 7, 1_000_000, 0, 10, 3, 100),
                DbSamplerTest.reading(1500, 80, 12.0, 9, 1_400_000, 40, 20, 5, 300)));

        for (String field : List.of("xactCommit", "walSync", "walFpi", "walBytes", "workHotUpd", "workUpd")) {
            assertEquals(0L, db.getJsonArray(field).getLong(0), field + " starts at 0, not at the server's cumulative count: " + db.encode());
        }
        assertEquals(0.0, db.getJsonArray("walSyncTimeMs").getDouble(0), db.encode());
        assertEquals(0L, db.getJsonArray("workDeadTup").getLong(0), "a tuple count is as read, its first one too: " + db.encode());
        assertEquals(10L, db.getJsonArray("workLiveTup").getLong(0), db.encode());
    }

    @Test
    void aSampleThatCouldNotBeReadIsNullInItsEntriesWithTheReasonAndTheRestStaysMeasured() {
        JsonObject db = DbSamplerTest.sampled(List.of(DbSamplerTest.reading(100, 1, 1.0, 1, 1, 7, 7, 1, 1),
                DbSampler.failed("the read failed: connection reset"), DbSamplerTest.reading(130, 4, 2.5, 1, 1, 9, 8, 1, 1),
                DbSamplerTest.reading(170, 9, 3.0, 1, 1, 2, 9, 1, 1)));

        assertEquals(Arrays.asList(0L, null, null, 40L), DbSamplerTest.longs(db.getJsonArray("xactCommit")),
                "no delta into or out of the unread sample: " + db.encode());
        assertEquals(Arrays.asList(0.0, null, null, 0.5), DbSamplerTest.doubles(db.getJsonArray("walSyncTimeMs")), db.encode());
        assertEquals(Arrays.asList(7L, null, 9L, 2L), DbSamplerTest.longs(db.getJsonArray("workDeadTup")), db.encode());
        assertEquals("the read failed: connection reset", db.getJsonObject("notes").getString("xactCommit"), db.encode());
        assertEquals("the read failed: connection reset", db.getJsonObject("notes").getString("workDeadTup"), db.encode());
        assertEquals(new JsonArray(), db.getJsonArray("statements"), "the window's two ends were read: " + db.encode());
        assertEquals(new JsonArray(), db.getJsonArray("workAutovacuum"), "the unread sample is no change: " + db.encode());
    }

    @Test
    void aValueUnknownWithoutAReasonIsNotedAsNotRead() {
        JsonObject db = DbSamplerTest.sampled(List.of(DbSamplerTest.reading(1, 1, 1.0, 1, 1, 1, 1, 1, 1).putNull("walFpi"),
                DbSamplerTest.reading(2, 2, 2.0, 2, 2, 2, 2, 2, 2).putNull("walFpi")));

        DbSamplerTest.assertUnknown(db, "walFpi", Series.NOT_READ);
    }

    @Test
    void aSamplerThatTookNoSampleHasEveryFieldUnknown() {
        JsonObject db = new DbSampler(() -> {
            throw new AssertionError("not sampled");
        }).series();

        for (String field : List.of("xactCommit", "walSync", "walSyncTimeMs", "workDeadTup", "workAutovacuum", "workAutoanalyze", "statements")) {
            DbSamplerTest.assertUnknown(db, field, Series.NOT_READ);
        }
    }

    @Test
    void theStatementsAreUnknownWhenEitherEndOfTheWindowHasNoneWithThatEndsNote() {
        JsonObject known = DbSamplerTest.reading(1, 1, 1.0, 1, 1, 1, 1, 1, 1)
                .put("statements", new JsonArray().add(DbSamplerTest.statement(1, "s", 1, 1.0, 1, 1)));
        JsonObject startUnread = DbSampler.failed("the read failed: at the start");
        JsonObject endUnread = DbSampler.failed("the read failed: at the end");

        DbSamplerTest.assertUnknown(DbSamplerTest.sampled(List.of(startUnread, known.copy())), "statements", "at the start");
        DbSamplerTest.assertUnknown(DbSamplerTest.sampled(List.of(known.copy(), endUnread)), "statements", "at the end");
    }

    @Test
    void aVacuumTimeTheWindowsStartAlreadySawIsNotAChangeAndAnUnreadTimeIsSkipped() {
        String before = "2026-11-05T09:59:00Z";
        String during = "2026-11-05T10:00:03Z";
        JsonObject unread = DbSamplerTest.reading(2, 2, 2.0, 2, 2, 2, 2, 2, 2).putNull("workLastAutovacuum").put("notes",
                new JsonObject().put("workLastAutovacuum", "pg_stat_user_tables has no row for work in schema public"));
        JsonObject db = DbSamplerTest.sampled(List.of(
                DbSamplerTest.reading(1, 1, 1.0, 1, 1, 1, 1, 1, 1).put("workLastAutovacuum", before),
                unread,
                DbSamplerTest.reading(3, 3, 3.0, 3, 3, 3, 3, 3, 3).put("workLastAutovacuum", before),
                DbSamplerTest.reading(4, 4, 4.0, 4, 4, 4, 4, 4, 4).put("workLastAutovacuum", during)));

        assertEquals(new JsonArray().add(new JsonObject().put("sample", 3).put("at", during)), db.getJsonArray("workAutovacuum"),
                "the vacuum before the window is not one of it; the unread sample is not a vacuum to null and back: " + db.encode());
        assertFalse(db.getJsonObject("notes").containsKey("workAutovacuum"), db.encode());
    }

    @Test
    void aTimeNoReadingCouldReadIsUnknownWithItsReason() {
        String reason = "pg_stat_user_tables has no row for work in schema public";
        List<JsonObject> readings = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            readings.add(DbSamplerTest.reading(k, k, (double) k, k, k, k, k, k, k).put("notes", new JsonObject().put("workLastAutoanalyze", reason)));
        }

        DbSamplerTest.assertUnknown(DbSamplerTest.sampled(readings), "workAutoanalyze", reason);
    }

    @Test
    void aStatementNotCalledInTheWindowIsLeftOut() {
        JsonObject first = DbSamplerTest.reading(1, 1, 1.0, 1, 1, 1, 1, 1, 1).put("statements", new JsonArray()
                .add(DbSamplerTest.statement(1, "idle", 50, 900.0, 10, 1)).add(DbSamplerTest.statement(2, "busy", 5, 1.0, 1, 1)));
        JsonObject last = DbSamplerTest.reading(2, 2, 2.0, 2, 2, 2, 2, 2, 2).put("statements", new JsonArray()
                .add(DbSamplerTest.statement(1, "idle", 50, 900.0, 10, 1)).add(DbSamplerTest.statement(2, "busy", 6, 1.0, 1, 1)));

        JsonArray top = DbSamplerTest.sampled(List.of(first, last)).getJsonArray("statements");

        assertEquals(1, top.size(), "only the statement called in the window: " + top.encode());
        assertEquals("busy", top.getJsonObject(0).getString("query"), "one call, no measurable time, still called: " + top.encode());
    }

    // --- DbSampler.reading: the statement's columns

    /** The columns of a reading on a server that counts and times its WAL syncs, with a work table and no statement run. */
    static Map<String, Object> row() {
        Map<String, Object> row = new HashMap<>();
        row.put("xact_commit", 500L);
        row.put("wal_sync", 40L);
        row.put("wal_sync_time", 12.5);
        row.put("wal_fpi", 3L);
        row.put("wal_bytes", 9000L);
        row.put("fsync", "on");
        row.put("wal_sync_method", "fdatasync");
        row.put("track_wal_io_timing", "on");
        row.put("schema", "public");
        row.put("work_relid", 16384L);
        row.put("n_dead_tup", 11L);
        row.put("n_live_tup", 22L);
        row.put("n_tup_hot_upd", 33L);
        row.put("n_tup_upd", 44L);
        row.put("last_autovacuum", OffsetDateTime.parse("2026-11-05T11:00:02.5+01:00"));
        row.put("last_autoanalyze", null);
        row.put("statements", null);
        return row;
    }

    @Test
    void aReadingTakesEachColumnAndTheVacuumTimeAsAnInstant() {
        Map<String, Object> row = row();
        row.put("statements", "[{\"queryid\":7,\"query\":\"delete from work where seq = $1\",\"calls\":3,\"totalExecTimeMs\":1.5,"
                + "\"sharedBlksHit\":4,\"sharedBlksRead\":1}]");

        JsonObject r = DbSampler.reading(row, null);

        assertEquals(new JsonObject().put("xactCommit", 500L).put("walSync", 40L).put("walSyncTimeMs", 12.5).put("walFpi", 3L).put("walBytes", 9000L)
                .put("workDeadTup", 11L).put("workLiveTup", 22L).put("workHotUpd", 33L).put("workUpd", 44L)
                .put("workLastAutovacuum", "2026-11-05T10:00:02.500Z").putNull("workLastAutoanalyze")
                .put("statements", new JsonArray().add(DbSamplerTest.statement(7, "delete from work where seq = $1", 3, 1.5, 4, 1)))
                .put("notes", new JsonObject()), r);
    }

    @Test
    void noStatementRunIsAnEmptyListAndAnUnreadableExtensionIsNullWithItsNote() {
        assertEquals(new JsonArray(), DbSampler.reading(row(), null).getJsonArray("statements"), "none ran: none, not unknown");

        JsonObject r = DbSampler.reading(row(), "pg_stat_statements is not readable in flottila_bench: relation does not exist");
        assertNull(r.getValue("statements"), r.encode());
        assertEquals("pg_stat_statements is not readable in flottila_bench: relation does not exist", r.getJsonObject("notes").getString("statements"));
    }

    @Test
    void walSyncsAreUnknownWithFsyncOffOrAnUncountedSyncMethodAndTheirTimeAlsoWithTimingOff() {
        Map<String, Object> off = row();
        off.put("fsync", "off");
        JsonObject r = DbSampler.reading(off, null);
        assertNull(r.getValue("walSync"), r.encode());
        assertNull(r.getValue("walSyncTimeMs"), r.encode());
        assertEquals(r.getJsonObject("notes").getString("walSync"), r.getJsonObject("notes").getString("walSyncTimeMs"), r.encode());
        assertEquals(true, r.getJsonObject("notes").getString("walSync").contains("fsync off"), r.encode());

        Map<String, Object> open = row();
        open.put("wal_sync_method", "open_datasync");
        r = DbSampler.reading(open, null);
        assertNull(r.getValue("walSync"), "fsync on, but open_datasync's syncs are not counted: " + r.encode());
        assertEquals(true, r.getJsonObject("notes").getString("walSync").contains("wal_sync_method open_datasync"), r.encode());

        Map<String, Object> untimed = row();
        untimed.put("track_wal_io_timing", "off");
        r = DbSampler.reading(untimed, null);
        assertEquals(40L, r.getLong("walSync"), "the syncs are still counted: " + r.encode());
        assertNull(r.getValue("walSyncTimeMs"), r.encode());
        assertEquals("track_wal_io_timing is off", r.getJsonObject("notes").getString("walSyncTimeMs"), r.encode());
        assertFalse(r.getJsonObject("notes").containsKey("walSync"), r.encode());
    }

    @Test
    void aSchemaWithoutAWorkTableMakesEveryWorkFieldUnknownNamingIt() {
        Map<String, Object> row = row();
        for (String column : List.of("work_relid", "n_dead_tup", "n_live_tup", "n_tup_hot_upd", "n_tup_upd", "last_autovacuum")) row.put(column, null);
        row.put("schema", "bench_s");

        JsonObject r = DbSampler.reading(row, null);

        for (String field : List.of("workDeadTup", "workLiveTup", "workHotUpd", "workUpd", "workLastAutovacuum", "workLastAutoanalyze")) {
            assertEquals(true, r.containsKey(field) && r.getValue(field) == null, field + ": " + r.encode());
            assertEquals("pg_stat_user_tables has no row for work in schema bench_s", r.getJsonObject("notes").getString(field), r.encode());
        }
        assertEquals(500L, r.getLong("xactCommit"), r.encode());
    }

    @Test
    void aFailedReadIsEveryFieldNullWithTheFailure() {
        JsonObject r = DbSampler.failed("the read failed: boom");

        assertEquals(DbSampler.FIELDS.size() + 1, r.size(), "every field and the notes: " + r.encode());
        for (String field : DbSampler.FIELDS) {
            assertEquals(true, r.containsKey(field) && r.getValue(field) == null, field + ": " + r.encode());
            assertEquals("the read failed: boom", r.getJsonObject("notes").getString(field), r.encode());
        }
    }
}
