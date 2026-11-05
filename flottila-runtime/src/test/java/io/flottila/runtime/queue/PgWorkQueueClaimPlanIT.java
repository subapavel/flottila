package io.flottila.runtime.queue;

import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-130 (F-27 addendum, bench/RESULTS.md "CORE-15b preview"): the worker's CLAIM costs its room, not the queue's depth. On a
 * {@code work} table of 50 000 claimable rows of one flow (behind 200 leased and 200 held rows at its head, analyzed), the claim the
 * node's queue prepares on a pool built from {@link Node#connectOptions} — executed under {@code plan_cache_mode} force_generic_plan
 * (what a cached prepared statement gets) and force_custom_plan — scans no {@code work} table, compiles no JIT function and reads
 * at most {@value #BUFFERS_PER_ROW} × room + {@value #BUFFERS_FIXED} shared buffers. Read from
 * {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) EXECUTE} inside a rolled-back transaction.
 *
 * <p>The statement under test is the one {@link PgWorkQueue#claim} prepares: a fresh one-connection pool, one claim on a flow with
 * no rows (it claims nothing), then the connection's only protocol-level prepared statement ({@code pg_prepared_statements}) is
 * executed by its name — the node's SQL text and the parameter types the client prepared it with, no copy of the SQL here.
 * Assumptions: {@code claim} prepares exactly one statement, whose parameters stay $1 worker, $2 flow, $3 room, $4 lease seconds,
 * $5 node.
 *
 * <p>The bound: a claimed row costs about 15 buffers in today's custom plan on postgres:17 (16 per row at room 10 and at room 64):
 * its entry in the seq-ordered index and its heap page, the re-fetch by primary key (root, leaf, heap), the new row version (heap
 * and free-space map) and an entry in each of the two indexes. 20 per row leaves headroom; the 50 fixed buffers cover the index
 * descents and the 400 leased and held rows the claim steps over at the head (under 10 heap pages). A scan of the 50 400 rows
 * reads every heap page of the table (over 600), above the bound at room {@value #ROOM} (250): the bound fails with the depth.
 * A Seq Scan counts when it ran (its loops above 0): a scan the plan never executes reads nothing of the queue.
 */
class PgWorkQueueClaimPlanIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final int DEPTH = 50_000;
    static final int LEASED = 200;
    static final int HELD = 200;
    static final int ROOM = 10;
    static final long BUFFERS_PER_ROW = 20;
    static final long BUFFERS_FIXED = 50;
    static final String NODE = "n1";
    static final Duration LEASE = Duration.ofSeconds(30);

    static Vertx vertx;
    static Pool plain;     // the test's own session: fills the table, witnesses the server's JIT
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

    /** 200 leased and 200 held rows at the head, then 50 000 claimable ones, every body in node n1's journal; then analyzed. */
    @BeforeEach
    void fill() {
        plain.query("truncate work").execute().await();
        plain.query("insert into work(id, flow, step, body_node, body_segment, body_position, body_length, locked_by, locked_until, attempts)"
                + " select 'l-' || g, 't', 1, '" + NODE + "', 1, g * 200, 132, 'other', now() + interval '1 hour', 1"
                + " from generate_series(1, " + LEASED + ") g").execute().await();
        plain.query("insert into work(id, flow, step, body_node, body_segment, body_position, body_length, held_at, attempts)"
                + " select 'h-' || g, 't', 1, '" + NODE + "', 1, g * 200, 132, now(), 1 from generate_series(1, " + HELD + ") g").execute().await();
        plain.query("insert into work(id, flow, step, body_node, body_segment, body_position, body_length)"
                + " select 'm-' || g, 't', 1, '" + NODE + "', 2, g * 200, 132 from generate_series(1, " + DEPTH + ") g").execute().await();
        plain.query("analyze work").execute().await();
        NodeConfig.Pg config = new NodeConfig.Pg(pg.getHost(), pg.getMappedPort(5432), pg.getDatabaseName(), pg.getUsername(), pg.getPassword(), 1);
        nodePool = PgBuilder.pool().connectingTo(Node.connectOptions(config)).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
    }

    @AfterEach
    void closeNodePool() { nodePool.close().await(); }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"force_generic_plan", "force_custom_plan"})
    void theNodesPreparedClaimOnAFiftyThousandRowQueueScansNoTableCompilesNoJitAndReadsBuffersBoundedByItsRoom(String planCacheMode) {
        assertTrue(jitAvailable(), "witness: this server compiles JIT when a session allows it, so a plan without JIT is the node's doing");
        assertTrue(new PgWorkQueue(nodePool, NODE).claim("prepare", "no-such-flow", ROOM, LEASE).await().isEmpty(),
                "the preparing claim is on a flow without rows: it takes nothing");

        SqlConnection conn = nodePool.getConnection().await();
        JsonObject explain;
        try {
            List<String> prepared = new ArrayList<>();
            for (Row r : conn.query("select name from pg_prepared_statements where not from_sql").execute().await()) prepared.add(r.getString(0));
            assertEquals(1, prepared.size(), "claim() prepared exactly one statement on the node's connection: " + prepared);
            conn.query("begin").execute().await();
            try {
                conn.query("set local plan_cache_mode = " + planCacheMode).execute().await();
                Row row = conn.query("explain (analyze, buffers, format json) execute \"" + prepared.get(0).replace("\"", "\"\"")
                        + "\"('fix130', 't', " + ROOM + ", " + LEASE.toSeconds() + ".0, '" + NODE + "')").execute().await().iterator().next();
                explain = row.getJsonArray(0).getJsonObject(0);
            } finally {
                conn.query("rollback").execute().await();
            }
        } finally {
            conn.close().await();
        }

        JsonObject plan = explain.getJsonObject("Plan");
        List<JsonObject> nodes = new ArrayList<>();
        collect(plan, nodes);
        List<String> seqScans = nodes.stream()
                .filter(n -> "Seq Scan".equals(n.getString("Node Type")) && "work".equals(n.getString("Relation Name")))
                .filter(n -> n.getLong("Actual Loops", 0L) > 0)
                .map(n -> n.getString("Alias") + " (" + n.getLong("Actual Rows") + " rows, " + buffers(n) + " buffers)")
                .toList();
        JsonObject jit = explain.getJsonObject("JIT");
        long jitFunctions = jit == null ? 0 : jit.getLong("Functions", 0L);
        long read = nodes.stream().mapToLong(PgWorkQueueClaimPlanIT::buffers).max().orElseThrow();
        long bound = BUFFERS_PER_ROW * ROOM + BUFFERS_FIXED;

        assertAll(planCacheMode,
                () -> assertEquals(ROOM, plan.getLong("Actual Rows"), "the claim took its room's worth of rows (the measurement is of a real claim)"),
                () -> assertEquals(List.of(), seqScans, "no Seq Scan of work ran"),
                () -> assertEquals(0L, jitFunctions, "no JIT compiled: " + jit),
                () -> assertTrue(read <= bound, "shared buffers " + read + " > " + BUFFERS_PER_ROW + " x room " + ROOM + " + " + BUFFERS_FIXED
                        + " = " + bound));
    }

    /** A plain session allowed to JIT: the server has a JIT provider (pg_jit_available() is true only while jit is on). */
    private static boolean jitAvailable() {
        SqlConnection conn = plain.getConnection().await();
        try {
            conn.query("set jit = on").execute().await();
            return conn.query("select pg_jit_available()").execute().await().iterator().next().getBoolean(0);
        } finally {
            conn.query("reset jit").execute().await();
            conn.close().await();
        }
    }

    private static void collect(JsonObject node, List<JsonObject> into) {
        into.add(node);
        JsonArray children = node.getJsonArray("Plans");
        if (children != null) for (int i = 0; i < children.size(); i++) collect(children.getJsonObject(i), into);
    }

    /**
     * A node's shared buffers, hit or read; each node's count includes its children's, so the largest over the tree is the
     * statement's (a CTE finished outside the top node still counts).
     */
    private static long buffers(JsonObject node) {
        return node.getLong("Shared Hit Blocks", 0L) + node.getLong("Shared Read Blocks", 0L);
    }
}
