package io.flottila.bench;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Row;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The open record's {@code db} object (CORE-15b, F-27 I1): the database read at each backlog sample from a source of
 * cumulative readings ({@link PgSource} in a run). A reading is a JSON object — {@code xactCommit} (pg_stat_database of the run's
 * database), {@code walSync}, {@code walSyncTimeMs}, {@code walFpi}, {@code walBytes} (pg_stat_wal), {@code workDeadTup},
 * {@code workLiveTup}, {@code workHotUpd}, {@code workUpd}, {@code workLastAutovacuum}, {@code workLastAutoanalyze}
 * (pg_stat_user_tables of {@code work}), {@code statements} (pg_stat_statements: {@code queryid}, {@code query}, {@code calls},
 * {@code totalExecTimeMs}, {@code sharedBlksHit}, {@code sharedBlksRead}) — with the reason for each field it could not read in
 * {@code notes}. The series: the counters as deltas per sample ({@link Series#deltas}), the tuple counts as read, the
 * autovacuum / autoanalyze times at the sample where each new one was seen ({@code workAutovacuum}, {@code workAutoanalyze}),
 * and the window's {@value #TOP} statements with the most execution time between the first and the last sample, each with its
 * window deltas (one not called in the window left out). An unknown field is null with its note, never 0.
 *
 * @since 0.1
 */
final class DbSampler implements OpenLoop.Sampler {

    /** The statements the series keeps: those with the most execution time in the window. */
    static final int TOP = 12;
    /** The counters recorded as a delta per sample. */
    static final List<String> COUNTERS = List.of("xactCommit", "walSync", "walFpi", "walBytes", "workHotUpd", "workUpd");
    /** The tuple counts recorded as read. */
    static final List<String> TUPLES = List.of("workDeadTup", "workLiveTup");
    /** The times recorded where they change: the reading's field, the series' field. */
    static final Map<String, String> TIMES = new LinkedHashMap<>(Map.of("workLastAutovacuum", "workAutovacuum", "workLastAutoanalyze",
            "workAutoanalyze"));
    /** Every field of a reading. */
    static final List<String> FIELDS = List.of("xactCommit", "walSync", "walSyncTimeMs", "walFpi", "walBytes", "workDeadTup", "workLiveTup",
            "workHotUpd", "workUpd", "workLastAutovacuum", "workLastAutoanalyze", "statements");
    /**
     * The wal_sync_method values whose syncs the cluster's WAL sync count counts (with fsync on; pg_stat_wal.wal_sync in
     * PostgreSQL 14 to 17, pg_stat_io's WAL fsyncs from 18).
     */
    static final Set<String> COUNTED_SYNC_METHODS = Set.of("fdatasync", "fsync", "fsync_writethrough");
    /**
     * The cluster's WAL syncs as one SQL expression for PostgreSQL 16 to 18: pg_stat_wal.wal_sync up to 17; 18 dropped the
     * column for pg_stat_io's {@code fsyncs} of object {@code wal}, context {@code normal} (the commit's sync, as wal_sync
     * counted it; {@code init} is a new segment's). {@code to_jsonb} reads the column by name, so the statement parses on
     * both; on 17 pg_stat_io has no {@code wal} rows.
     */
    static final String WAL_SYNCS = "coalesce((select (to_jsonb(s) ->> 'wal_sync')::bigint from pg_stat_wal s),"
            + " (select sum(fsyncs)::bigint from pg_stat_io where object = 'wal' and context = 'normal'))";
    /** The time of {@link #WAL_SYNCS} in ms: pg_stat_wal.wal_sync_time up to 17, pg_stat_io's WAL fsync_time from 18. */
    static final String WAL_SYNC_TIME = "coalesce((select (to_jsonb(s) ->> 'wal_sync_time')::float8 from pg_stat_wal s),"
            + " (select sum(fsync_time)::float8 from pg_stat_io where object = 'wal' and context = 'normal'))";

    private final Supplier<JsonObject> source;
    private final List<JsonObject> readings = new ArrayList<>();

    /** Reads {@code source} once per sample. */
    DbSampler(Supplier<JsonObject> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Reads the source once. Only the first and the latest reading keep their statements (the window's two ends): the one
     * before the latest drops them, so a long window holds two statement lists, not one per second.
     */
    @Override public synchronized void sample() {
        readings.add(source.get());
        if (readings.size() > 2) readings.get(readings.size() - 2).remove("statements");
    }

    @Override public synchronized JsonObject series() {
        JsonObject out = new JsonObject();
        JsonObject notes = new JsonObject();
        for (String field : COUNTERS) Series.put(out, notes, field, Series.deltas(longs(field)), reasons(field));
        List<Double> syncTime = readings.stream().map(r -> r.getDouble("walSyncTimeMs")).toList();
        Series.put(out, notes, "walSyncTimeMs", Series.decimalDeltas(syncTime), reasons("walSyncTimeMs"));
        for (String field : TUPLES) Series.put(out, notes, field, longs(field), reasons(field));
        TIMES.forEach((field, series) -> changes(out, notes, field, series));
        statements(out, notes);
        return out.put("notes", notes);
    }

    /** {@code field} of each reading, null where unknown. */
    private List<Long> longs(String field) {
        return readings.stream().map(r -> r.getLong(field)).toList();
    }

    /** The note on {@code field} of each reading, null where it has none. */
    private List<String> reasons(String field) {
        return readings.stream().map(r -> r.getJsonObject("notes", new JsonObject()).getString(field)).toList();
    }

    /**
     * {@code series}: each new value of the time {@code field} once, with the sample that first saw it; a time the window's
     * start already saw is not new, a reading that could not read it (a note) is skipped. Null with the note when no reading
     * could read it.
     */
    private void changes(JsonObject out, JsonObject notes, String field, String series) {
        JsonArray changes = new JsonArray();
        boolean known = false;
        String last = null;
        for (int k = 0; k < readings.size(); k++) {
            JsonObject r = readings.get(k);
            if (r.getJsonObject("notes", new JsonObject()).containsKey(field)) continue;
            String at = r.getString(field);
            if (known && at != null && !at.equals(last)) changes.add(new JsonObject().put("sample", k).put("at", at));
            if (at != null) last = at;
            known = true;
        }
        if (known) {
            out.put(series, changes);
        } else {
            out.putNull(series);
            notes.put(series, reasons(field).stream().filter(Objects::nonNull).findFirst().orElse(Series.NOT_READ));
        }
    }

    /**
     * The window's top statements by execution time: each statement of the last reading minus its values in the first (a
     * statement first seen in the window counts from zero; one not called in the window is left out), most time first. Null
     * with the note when either reading has none.
     */
    private void statements(JsonObject out, JsonObject notes) {
        JsonObject first = readings.isEmpty() ? null : readings.getFirst();
        JsonObject last = readings.isEmpty() ? null : readings.getLast();
        if (first == null || first.getJsonArray("statements") == null || last.getJsonArray("statements") == null) {
            out.putNull("statements");
            notes.put("statements", reasons("statements").stream().filter(Objects::nonNull).findFirst().orElse(Series.NOT_READ));
            return;
        }
        Map<Long, JsonObject> before = new HashMap<>();
        for (Object s : first.getJsonArray("statements")) before.put(((JsonObject) s).getLong("queryid"), (JsonObject) s);
        List<JsonObject> window = new ArrayList<>();
        for (Object o : last.getJsonArray("statements")) {
            JsonObject now = (JsonObject) o;
            JsonObject was = before.getOrDefault(now.getLong("queryid"), new JsonObject());
            if (now.getLong("calls") - was.getLong("calls", 0L) <= 0) continue;
            window.add(new JsonObject().put("queryid", now.getLong("queryid")).put("query", now.getString("query"))
                    .put("calls", now.getLong("calls") - was.getLong("calls", 0L))
                    .put("totalExecTimeMs", now.getDouble("totalExecTimeMs") - was.getDouble("totalExecTimeMs", 0.0))
                    .put("sharedBlks", now.getLong("sharedBlksHit") + now.getLong("sharedBlksRead") - was.getLong("sharedBlksHit", 0L)
                            - was.getLong("sharedBlksRead", 0L)));
        }
        window.sort(Comparator.comparingDouble((JsonObject s) -> s.getDouble("totalExecTimeMs")).reversed());
        out.put("statements", new JsonArray(new ArrayList<>(window.subList(0, Math.min(TOP, window.size())))));
    }

    /**
     * One reading from the columns of {@link PgSource}'s statement ({@code row}, by column name): the WAL sync figures
     * only where it counts them (fsync on and a counted wal_sync_method; the time also needs track_wal_io_timing on), the
     * {@code work} figures only where pg_stat_user_tables has its row, the statements only when {@code statementsNote} is null
     * (else it is their note).
     */
    static JsonObject reading(Map<String, Object> row, String statementsNote) {
        JsonObject r = new JsonObject();
        JsonObject notes = new JsonObject();
        r.put("xactCommit", row.get("xact_commit"));
        String fsync = (String) row.get("fsync");
        String method = (String) row.get("wal_sync_method");
        String uncounted = "on".equals(fsync) && COUNTED_SYNC_METHODS.contains(method) ? null : "pg_stat_wal.wal_sync counts only with fsync"
                + " on and wal_sync_method " + COUNTED_SYNC_METHODS + " (fsync " + fsync + ", wal_sync_method " + method + ")";
        String untimed = uncounted != null ? uncounted : "on".equals(row.get("track_wal_io_timing")) ? null : "track_wal_io_timing is off";
        put(r, notes, "walSync", row.get("wal_sync"), uncounted);
        put(r, notes, "walSyncTimeMs", row.get("wal_sync_time"), untimed);
        r.put("walFpi", row.get("wal_fpi")).put("walBytes", row.get("wal_bytes"));
        String noWork = row.get("work_relid") != null ? null : "pg_stat_user_tables has no row for work in schema " + row.get("schema");
        put(r, notes, "workDeadTup", row.get("n_dead_tup"), noWork);
        put(r, notes, "workLiveTup", row.get("n_live_tup"), noWork);
        put(r, notes, "workHotUpd", row.get("n_tup_hot_upd"), noWork);
        put(r, notes, "workUpd", row.get("n_tup_upd"), noWork);
        put(r, notes, "workLastAutovacuum", instant(row.get("last_autovacuum")), noWork);
        put(r, notes, "workLastAutoanalyze", instant(row.get("last_autoanalyze")), noWork);
        Object statements = row.get("statements");   // json_agg over no row is null: none ran
        put(r, notes, "statements", statements == null ? new JsonArray() : new JsonArray((String) statements), statementsNote);
        return r.put("notes", notes);
    }

    /** {@code value} as {@code field}, or null with {@code reason} in {@code notes} when the reason is given. */
    private static void put(JsonObject r, JsonObject notes, String field, Object value, String reason) {
        if (reason == null) {
            r.put(field, value);
        } else {
            r.putNull(field);
            notes.put(field, reason);
        }
    }

    /** A timestamptz as an ISO-8601 instant, null as null. */
    private static String instant(Object at) {
        return at == null ? null : ((OffsetDateTime) at).toInstant().toString();
    }

    /** A reading of which nothing could be read: every field null, each with {@code reason}. */
    static JsonObject failed(String reason) {
        JsonObject r = new JsonObject();
        JsonObject notes = new JsonObject();
        for (String field : FIELDS) put(r, notes, field, null, reason);
        return r.put("notes", notes);
    }

    /**
     * The readings of a run's database on a connection of the sampler's own — never the node's pool, nor the bench's — with
     * {@code statement_timeout} {@value #STATEMENT_TIMEOUT} (a read never waits longer) and application_name
     * {@value #APPLICATION_NAME}. One statement per reading, one commit: the database's xact_commit counts it, pg_stat_statements
     * does not (its own statements, which name the pg_stat_ views, are left out of the statements, as is every statement of
     * another database). pg_stat_statements is probed once when the source opens; unreadable (not created in the database, not
     * preloaded), the statements are null with the probe's error. A read that fails is a reading of nulls with its error.
     */
    static final class PgSource implements Supplier<JsonObject>, AutoCloseable {

        static final String STATEMENT_TIMEOUT = "10s";
        static final String APPLICATION_NAME = "flottila-bench-sampler";

        /** The node's statements in the run's database, summed per queryid, as JSON text (null when there are none). */
        static final String STATEMENTS = "(select json_agg(json_build_object('queryid', queryid, 'query', query, 'calls', calls,"
                + " 'totalExecTimeMs', total_exec_time, 'sharedBlksHit', shared_blks_hit, 'sharedBlksRead', shared_blks_read))::text"
                + " from (select queryid, min(query) as query, sum(calls) as calls, sum(total_exec_time) as total_exec_time,"
                + " sum(shared_blks_hit) as shared_blks_hit, sum(shared_blks_read) as shared_blks_read from pg_stat_statements"
                + " where dbid = (select oid from pg_database where datname = current_database()) and queryid is not null"
                + " and query not like '%pg\\_stat\\_%' group by queryid) s)";

        /** One reading: the columns {@link #reading} takes, with {@code statements} the statements' JSON text or null. */
        static String reading(boolean statements) {
            return "select (select xact_commit from pg_stat_database where datname = current_database()) as xact_commit,"
                    + " " + WAL_SYNCS + " as wal_sync, " + WAL_SYNC_TIME + " as wal_sync_time,"
                    + " w.wal_fpi, w.wal_bytes::bigint as wal_bytes, current_setting('fsync') as fsync,"
                    + " current_setting('wal_sync_method') as wal_sync_method, current_setting('track_wal_io_timing') as track_wal_io_timing,"
                    + " current_schema() as schema, t.relid as work_relid, t.n_dead_tup, t.n_live_tup, t.n_tup_hot_upd, t.n_tup_upd,"
                    + " t.last_autovacuum, t.last_autoanalyze, " + (statements ? STATEMENTS : "null::text") + " as statements"
                    + " from pg_stat_wal w left join pg_stat_user_tables t on t.schemaname = current_schema() and t.relname = 'work'";
        }

        private final PgConnection connection;
        private final String sql;
        private final String statementsNote;

        private PgSource(PgConnection connection, String sql, String statementsNote) {
            this.connection = connection;
            this.sql = sql;
            this.statementsNote = statementsNote;
        }

        /** Opens the source's own connection with {@code options}' target and credentials; blocks (a virtual thread's call). */
        static PgSource open(Vertx vertx, PgConnectOptions options) {
            PgConnectOptions own = new PgConnectOptions(options).addProperty("statement_timeout", STATEMENT_TIMEOUT)
                    .addProperty("application_name", APPLICATION_NAME);
            PgConnection connection = PgConnection.connect(vertx, own).await();
            String note = null;
            try {
                connection.query("select 1 from pg_stat_statements limit 0").execute().await();
            } catch (Exception unreadable) {   // await rethrows the server's error sneakily
                note = "pg_stat_statements is not readable in " + options.getDatabase() + ": " + unreadable.getMessage();
            }
            return new PgSource(connection, reading(note == null), note);
        }

        @Override public JsonObject get() {
            Row row;
            try {
                row = connection.query(sql).execute().await().iterator().next();
            } catch (Exception failed) {   // await rethrows the cause sneakily
                return failed("the read failed: " + failed);
            }
            Map<String, Object> columns = new HashMap<>();
            for (int i = 0; i < row.size(); i++) columns.put(row.getColumnName(i), row.getValue(i));
            return DbSampler.reading(columns, statementsNote);
        }

        @Override public void close() {
            connection.close().await();
        }
    }
}
