package io.flottila.runtime.queue;

import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.core.queue.WorkQueue.Completion;
import io.flottila.core.queue.WorkQueue.Item;
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
 * FIX-130, the fast path beside {@link PgWorkQueueClaimPlanIT}: the next-of-key claim {@link PgWorkQueue#completeAndTakeNext}
 * prepares takes its row through the primary key, never through a Seq Scan of the UPDATE's own target ({@code work w}), under
 * {@code plan_cache_mode} force_generic_plan and force_custom_plan, on 50 000 rows over 100 keys (the old {@code seq in (..)}
 * form joined its one row to a Seq Scan of the table in both plans here; a single-key table hid it). Only the UPDATE's target is
 * checked: the keyed NOT EXISTS subplans still scan by (flow, partition_key) without an index (FIX-131).
 *
 * <p>The statement under test is the one the node's queue prepared: a one-connection pool from {@link Node#connectOptions}, a claim
 * and a completeAndTakeNext on a two-row key of another flow, then the connection's prepared statement that carries the key
 * condition ({@code w1.partition_key = $3}) executed by its name. Assumptions: its parameters are $1 worker, $2 flow, $3 key,
 * $4 lease seconds, $5 node.
 */
class PgWorkQueueNextOfKeyPlanIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final int DEPTH = 50_000;
    static final int KEYS = 100;
    static final String NODE = "n1";
    static final Duration LEASE = Duration.ofSeconds(30);

    static Vertx vertx;
    static Pool plain;
    Pool nodePool;

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

    /** Flow t: 50 000 rows over keys k0..k99 (k1 first); flow p: two rows of key prep for the preparing claim. Then analyzed. */
    @BeforeEach
    void fill() {
        plain.query("truncate work").execute().await();
        plain.query("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length)"
                + " select 'm-' || g, 't', 1, 'k' || (g % " + KEYS + "), '" + NODE + "', 2, g * 200, 132 from generate_series(1, " + DEPTH + ") g").execute().await();
        plain.query("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length)"
                + " select 'p-' || g, 'p', 1, 'prep', '" + NODE + "', 2, g * 200, 132 from generate_series(1, 2) g").execute().await();
        plain.query("analyze work").execute().await();
        NodeConfig.Pg config = new NodeConfig.Pg(pg.getHost(), pg.getMappedPort(5432), pg.getDatabaseName(), pg.getUsername(), pg.getPassword(), 1);
        nodePool = PgBuilder.pool().connectingTo(Node.connectOptions(config)).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
    }

    @AfterEach
    void closeNodePool() { nodePool.close().await(); }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"force_generic_plan", "force_custom_plan"})
    void theNodesPreparedNextOfKeyClaimTakesItsRowWithoutScanningTheTable(String planCacheMode) {
        PgWorkQueue queue = new PgWorkQueue(nodePool, NODE);
        List<Item> first = queue.claim("prepare", "p", 1, LEASE).await();
        assertEquals(1, first.size(), "the preparing claim takes the head of key prep");
        Completion done = queue.completeAndTakeNext(first.getFirst().seq(), first.getFirst().attempts(), "prepare", LEASE).await();
        assertTrue(done.next().isPresent(), "the preparing completion took the next row of key prep through the fast path");

        SqlConnection conn = nodePool.getConnection().await();
        JsonObject explain;
        try {
            List<String> next = new ArrayList<>();
            for (Row r : conn.query("select name from pg_prepared_statements where not from_sql and statement like '%w1.partition_key = $3%'")
                    .execute().await()) next.add(r.getString(0));
            assertEquals(1, next.size(), "the fast path prepared exactly one keyed statement: " + next);
            conn.query("begin").execute().await();
            try {
                conn.query("set local plan_cache_mode = " + planCacheMode).execute().await();
                Row row = conn.query("explain (analyze, buffers, format json) execute \"" + next.getFirst().replace("\"", "\"\"")
                        + "\"('fix130', 't', 'k1', " + LEASE.toSeconds() + ".0, '" + NODE + "')").execute().await().iterator().next();
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
        List<String> targetScans = nodes.stream()
                .filter(n -> "Seq Scan".equals(n.getString("Node Type")) && "work".equals(n.getString("Relation Name")) && "w".equals(n.getString("Alias")))
                .filter(n -> n.getLong("Actual Loops", 0L) > 0)
                .map(n -> n.getString("Alias") + " (" + n.getLong("Actual Rows") + " rows)")
                .toList();

        assertAll(planCacheMode,
                () -> assertEquals(1L, plan.getLong("Actual Rows"), "the fast path took the key's head (the measurement is of a real claim)"),
                () -> assertEquals(List.of(), targetScans, "no Seq Scan of the UPDATE's target ran"));
    }

    private static void collect(JsonObject node, List<JsonObject> into) {
        into.add(node);
        JsonArray children = node.getJsonArray("Plans");
        if (children != null) for (int i = 0; i < children.size(); i++) collect(children.getJsonObject(i), into);
    }
}
