package io.flottila.runtime.journal;

import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.function.Executable;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-127 (F-27 I3, bench/RESULTS.md "CORE-15b preview": 1.7 s and 203 k shared buffers per sweep call at r2000): the segment
 * sweeper's pinned check costs |work| + |candidates|, not their product. On {@value #WORK_ROWS} live {@code work} rows of node
 * {@value #NODE} spread in segment order over {@value #WORK_SEGMENTS} of its {@value #CANDIDATES} sealed candidate segments (the
 * others pinned by kept refs, or pinned by nothing), analyzed, every statement one {@link PgSegmentRows#sweep} prepares on a pool
 * built from {@link Node#connectOptions} — executed under {@code plan_cache_mode} force_generic_plan (a cached prepared statement's
 * plan) and force_custom_plan, read from {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) EXECUTE} in a rolled-back transaction —
 * reads at most 2 × the heap pages of the tables the check consults + {@value #BUFFERS_PER_CANDIDATE} × candidates shared
 * buffers, and repeats no full or bitmap scan of {@code work}, {@code body_refs} or {@code dlq} per candidate (or per kept ref).
 *
 * <p>The statements under test are the ones the node's code prepares: a fresh one-connection pool, one sweep of a segment of
 * another node ({@value #PREP_NODE}, unpinned: the pass locks and deletes it, so every statement of a pass runs), then each of
 * the connection's protocol-level prepared statements ({@code pg_prepared_statements}, transaction control excluded) is executed
 * by its name — the node's SQL text and parameter types, no copy of the SQL here. Assumption: a sweep statement's parameters are
 * typed text (the node), bigint[] (the candidates: all {@value #CANDIDATES}, the worst case of the rows a lock passes on) and
 * timestamptz (the instant a kept ref must be created after); any other type fails the case by name.
 *
 * <p>The bound: a hashed check reads each consulted table's heap once — {@code work}, {@code body_refs}, {@code dlq} and
 * {@code journal_segments}; twice that leaves room for an index alternative (an index on {@code work(body_node, body_segment)}
 * where not lost, as V12 anticipated, reads its own pages beside the heap's). Per candidate, {@value #BUFFERS_PER_CANDIDATE}
 * buffers cover its primary-key probe of {@code journal_segments} (root, leaf, heap), its row lock or delete (heap and index) and
 * one index probe of {@code body_refs} or {@code work} (root, leaf, heap). Today's plan probes {@code work} by a sequential scan
 * per candidate: a pinned candidate reads the heap up to its segment's first row (half the table on average, the rows lying in
 * segment order as they arrive), an unpinned one the whole table — tens of thousands of buffers, against a bound of a few
 * thousand; the case checks that the bound separates the two before asserting it. A scan "per candidate" is a Seq Scan or
 * Bitmap Heap Scan whose actual loops exceed one (a parallel-aware scan's loops count its workers and are excluded); a
 * per-candidate index probe is the index design the packet allows, its cost held by the buffer bound.
 *
 * <p>The witness that the measured statements check what a sweep must: after the plans, a committed sweep of all candidates
 * removes exactly the unpinned ones — no candidate pinned by a live work row or a kept ref is ever unlinked (D-105), and a lost
 * work row, a lost or archived ref, an old ref no DLQ row holds and another node's rows pin nothing.
 */
class PgSegmentRowsSweepPlanIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final String NODE = "n1";
    static final String OTHER_NODE = "n2";
    static final String PREP_NODE = "prep";
    static final int CANDIDATES = 200;
    static final int WORK_SEGMENTS = 160;              // segments 1..160: live work rows
    static final int ROWS_PER_SEGMENT = 312;
    static final int WORK_ROWS = WORK_SEGMENTS * ROWS_PER_SEGMENT;   // 49 920
    static final int ARCHIVED_REFS_PER_SEGMENT = 25;  // on every candidate: archived, they pin nothing
    static final int UNRELATED_DLQ_ROWS = 1_000;
    static final long BUFFERS_PER_CANDIDATE = 10;
    static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant KEPT_SINCE = Instant.parse("2026-03-01T00:00:00Z");
    static final Instant FRESH = Instant.parse("2026-06-01T00:00:00Z");
    /** Segments 161..170 pinned by a fresh kept ref, 171..180 by an old one a DLQ row holds, 181..200 by nothing. */
    static final Set<Long> UNPINNED = LongStream.rangeClosed(181, CANDIDATES).boxed().collect(Collectors.toCollection(TreeSet::new));
    static final Set<Long> ALL = LongStream.rangeClosed(1, CANDIDATES).boxed().collect(Collectors.toCollection(TreeSet::new));

    static Vertx vertx;
    static Pool plain;     // the test's own session: fills the tables, reads their sizes
    Pool nodePool;         // the node's: Node.connectOptions, one connection

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        plain = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() { plain.close().await(); vertx.close().await(); pg.stop(); }

    @BeforeEach
    void fill() {
        sql("truncate work, body_refs, dlq, journal_segments");
        sql("insert into journal_segments(node, segment, copies) select '" + NODE + "', g, array['" + NODE + "'] from generate_series(1, " + CANDIDATES + ") g");
        sql("insert into journal_segments(node, segment, copies) select '" + OTHER_NODE + "', g, array['" + OTHER_NODE + "'] from generate_series(1, " + CANDIDATES + ") g");
        sql("insert into journal_segments(node, segment, copies) values ('" + PREP_NODE + "', 1, array['" + PREP_NODE + "'])");
        // live work rows in segment order, as they arrive: segment k's rows follow segment k-1's in the heap
        sql("insert into work(id, flow, step, body_node, body_segment, body_position, body_length)"
                + " select 'm-' || g, 't', 1, '" + NODE + "', 1 + g / " + ROWS_PER_SEGMENT + ", (g % " + ROWS_PER_SEGMENT + ") * 200, 132"
                + " from generate_series(0, " + (WORK_ROWS - 1) + ") g");
        // on the unpinned segments: lost work rows of this node, live ones of another node
        sql("insert into work(id, flow, step, body_node, body_segment, body_position, body_length, lost_at)"
                + " select 'lost-' || s || '-' || i, 't', 1, '" + NODE + "', s, i * 200, 132, " + ts(OLD)
                + " from generate_series(181, " + CANDIDATES + ") s, generate_series(1, 5) i");
        sql("insert into work(id, flow, step, body_node, body_segment, body_position, body_length)"
                + " select 'other-' || s || '-' || i, 't', 1, '" + OTHER_NODE + "', s, i * 200, 132"
                + " from generate_series(181, " + CANDIDATES + ") s, generate_series(1, 5) i");
        // archived refs on every candidate (pin nothing)
        sql("insert into body_refs(message_id, step, part, node, segment, position, length, archived_at, created_at)"
                + " select 'a-' || s || '-' || i, 1, 'body', '" + NODE + "', s, i * 200, 132, " + ts(FRESH) + ", " + ts(FRESH)
                + " from generate_series(1, " + CANDIDATES + ") s, generate_series(1, " + ARCHIVED_REFS_PER_SEGMENT + ") i");
        // 161..170: a kept ref created after KEPT_SINCE pins
        sql("insert into body_refs(message_id, step, part, node, segment, position, length, created_at)"
                + " select 'f-' || s, 1, 'body', '" + NODE + "', s, 0, 132, " + ts(FRESH) + " from generate_series(161, 170) s");
        // 171..180: an old kept ref a DLQ row holds pins
        sql("insert into body_refs(message_id, step, part, node, segment, position, length, created_at)"
                + " select 'k-' || s, 1, 'body', '" + NODE + "', s, 0, 132, " + ts(OLD) + " from generate_series(171, 180) s");
        sql("insert into dlq(id, flow, stage, step, error, body_key)"
                + " select 'd-' || s, 't', 's', 1, 'e', 'k-' || s || '/1/body' from generate_series(171, 180) s");
        // 181..200: an old ref no DLQ row holds, a lost fresh ref, another node's fresh ref — none pins
        sql("insert into body_refs(message_id, step, part, node, segment, position, length, created_at)"
                + " select 'o-' || s, 1, 'body', '" + NODE + "', s, 0, 132, " + ts(OLD) + " from generate_series(181, " + CANDIDATES + ") s");
        sql("insert into body_refs(message_id, step, part, node, segment, position, length, created_at, lost_at)"
                + " select 'l-' || s, 1, 'body', '" + NODE + "', s, 200, 132, " + ts(FRESH) + ", " + ts(FRESH)
                + " from generate_series(181, " + CANDIDATES + ") s");
        sql("insert into body_refs(message_id, step, part, node, segment, position, length, created_at)"
                + " select 'n-' || s, 1, 'body', '" + OTHER_NODE + "', s, 0, 132, " + ts(FRESH) + " from generate_series(181, " + CANDIDATES + ") s");
        sql("insert into dlq(id, flow, stage, step, error, body_key)"
                + " select 'x-' || g, 't', 's', 1, 'e', 'x-' || g || '/1/body' from generate_series(1, " + UNRELATED_DLQ_ROWS + ") g");
        sql("analyze work");
        sql("analyze body_refs");
        sql("analyze dlq");
        sql("analyze journal_segments");
        NodeConfig.Pg config = new NodeConfig.Pg(pg.getHost(), pg.getMappedPort(5432), pg.getDatabaseName(), pg.getUsername(), pg.getPassword(), 1);
        nodePool = PgBuilder.pool().connectingTo(Node.connectOptions(config)).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
    }

    @AfterEach
    void closeNodePool() { nodePool.close().await(); }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"force_generic_plan", "force_custom_plan"})
    void theNodesPreparedSweepOverTwoHundredCandidatesAndFiftyThousandWorkRowsReadsBuffersLinearInRowsAndScansNoTablePerCandidate(String planCacheMode) {
        PgSegmentRows rows = new PgSegmentRows(nodePool);
        assertEquals(Set.of(1L), rows.sweep(PREP_NODE, Set.of(1L), KEPT_SINCE).await(),
                "the preparing pass is on another node's unpinned segment: it locks and deletes it, so every statement of a pass ran");

        long workPages = pages("work");
        long consultedPages = workPages + pages("body_refs") + pages("dlq") + pages("journal_segments");
        long bound = 2 * consultedPages + BUFFERS_PER_CANDIDATE * CANDIDATES;
        assertTrue(bound < (long) CANDIDATES * workPages / 4,
                "witness: the bound " + bound + " separates a hashed check from a per-candidate scan of work (" + workPages + " pages)");

        List<Executable> checks = new ArrayList<>();
        SqlConnection conn = nodePool.getConnection().await();
        try {
            List<Row> prepared = new ArrayList<>();
            for (Row r : conn.query("select name, statement, parameter_types::text[] from pg_prepared_statements where not from_sql"
                    + " and statement !~* '^\\s*(begin|commit|rollback)'").execute().await()) prepared.add(r);
            assertFalse(prepared.isEmpty(), "sweep() prepared its statements on the node's connection");
            for (Row statement : prepared) {
                String name = statement.getString(0);
                String text = statement.getString(1).strip().replaceAll("\\s+", " ");
                String label = text.substring(0, Math.min(70, text.length())) + "...";
                JsonObject explain = explain(conn, planCacheMode, name, statement.getArrayOfStrings(2));
                List<JsonObject> nodes = new ArrayList<>();
                collect(explain.getJsonObject("Plan"), nodes);
                long read = nodes.stream().mapToLong(PgSegmentRowsSweepPlanIT::buffers).max().orElseThrow();
                List<String> perCandidate = nodes.stream()
                        .filter(n -> Set.of("Seq Scan", "Bitmap Heap Scan").contains(n.getString("Node Type")))
                        .filter(n -> Set.of("work", "body_refs", "dlq").contains(n.getString("Relation Name")))
                        .filter(n -> !n.getBoolean("Parallel Aware", false))
                        .filter(n -> n.getLong("Actual Loops", 0L) > 1)
                        .map(n -> n.getString("Node Type") + " on " + n.getString("Relation Name") + " x " + n.getLong("Actual Loops")
                                + " loops (" + buffers(n) + " buffers)")
                        .toList();
                checks.add(() -> assertEquals(List.of(), perCandidate, label + ": no table scanned per candidate"));
                checks.add(() -> assertTrue(read <= bound, label + ": shared buffers " + read + " > 2 x " + consultedPages + " pages + "
                        + BUFFERS_PER_CANDIDATE + " x " + CANDIDATES + " candidates = " + bound));
            }
        } finally {
            conn.close().await();
        }
        Set<Long> swept = new TreeSet<>(rows.sweep(NODE, ALL, KEPT_SINCE).await());   // after the plans: it commits
        checks.add(() -> assertEquals(UNPINNED, swept, "witness: a committed pass over every candidate removes exactly the unpinned ones"));
        assertAll(planCacheMode, checks);
    }

    /** One prepared statement executed by name under EXPLAIN in a transaction rolled back after it (a lock or delete is undone). */
    private static JsonObject explain(SqlConnection conn, String planCacheMode, String name, String[] types) {
        List<String> args = new ArrayList<>();
        for (String type : types) {
            switch (type) {
                case "text" -> args.add("'" + NODE + "'::text");
                case "bigint[]" -> args.add("'{" + ALL.stream().map(String::valueOf).collect(Collectors.joining(",")) + "}'::bigint[]");
                case "timestamp with time zone" -> args.add(ts(KEPT_SINCE));
                default -> fail("a sweep statement parameter of type " + type + ": the case knows text, bigint[] and timestamptz");
            }
        }
        conn.query("begin").execute().await();
        try {
            conn.query("set local plan_cache_mode = " + planCacheMode).execute().await();
            Row row = conn.query("explain (analyze, buffers, format json) execute \"" + name.replace("\"", "\"\"") + "\"("
                    + String.join(", ", args) + ")").execute().await().iterator().next();
            return row.getJsonArray(0).getJsonObject(0);
        } finally {
            conn.query("rollback").execute().await();
        }
    }

    /** Heap pages of a table after analyze. */
    private static long pages(String table) {
        return plain.query("select relpages from pg_class where oid = '" + table + "'::regclass").execute().await().iterator().next().getLong(0);
    }

    private static void sql(String statement) {
        plain.query(statement).execute().await();
    }

    private static String ts(Instant instant) {
        return "'" + instant + "'::timestamptz";
    }

    private static void collect(JsonObject node, List<JsonObject> into) {
        into.add(node);
        JsonArray children = node.getJsonArray("Plans");
        if (children != null) for (int i = 0; i < children.size(); i++) collect(children.getJsonObject(i), into);
    }

    /**
     * A node's shared buffers, hit or read; each node's count includes its children's and the subplans its quals ran, so the
     * largest over the tree is the statement's.
     */
    private static long buffers(JsonObject node) {
        return node.getLong("Shared Hit Blocks", 0L) + node.getLong("Shared Read Blocks", 0L);
    }
}
