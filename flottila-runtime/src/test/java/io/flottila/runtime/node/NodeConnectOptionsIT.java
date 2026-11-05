package io.flottila.runtime.node;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-130 (F-27 addendum): the node's Postgres sessions run without JIT — a claim's cost estimate grows with the queue's depth, and
 * above jit_above_cost every claim compiled 37–39 functions (461–492 ms of a 479–530 ms EXPLAIN, bench/RESULTS.md "CORE-15b
 * preview"). Observed on a session of a pool built from {@link Node#connectOptions}, against a plain session of the same server
 * that runs with the server's default (on): the setting comes from the node's connect options, not from the server.
 */
class NodeConnectOptionsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;

    @BeforeAll
    static void start() { pg.start(); vertx = Vertx.vertx(); }

    @AfterAll
    static void stop() { vertx.close().await(); pg.stop(); }

    @Test
    void theNodesPooledSessionsRunWithoutJit() {
        Pool plain = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        Pool node = PgBuilder.pool().connectingTo(Node.connectOptions(new NodeConfig.Pg(pg.getHost(), pg.getMappedPort(5432), pg.getDatabaseName(),
                pg.getUsername(), pg.getPassword(), 2))).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
        try {
            assertEquals("on", show(plain), "witness: this server's default session runs with jit on");
            assertEquals("off", show(node), "a session of the node's pool runs with jit off");
        } finally {
            node.close().await();
            plain.close().await();
        }
    }

    private static String show(Pool pool) {
        return pool.query("show jit").execute().await().iterator().next().getString(0);
    }
}
