package io.flottila.runtime.deploy;

import io.flottila.runtime.db.Migrations;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-09c3a (D-070): the Postgres record store passes the store contract over V7's {@code deployments} table, on a container
 * whose database collates en_US.utf8 (the image's default): {@code listIsSortedByName} holds there too.
 */
class PgDeploymentStoreIT extends DeploymentStoreContract {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        // fixture: the database's own order is not String order, so listIsSortedByName is not passed by a plain 'order by'
        String collated = pool.query("select string_agg(n, ',' order by n) from (values ('shop-eu'), ('shopa')) v(n)").execute().await()
                .iterator().next().getString(0);
        assertEquals("shopa,shop-eu", collated, "the container's collation ignores the dash, unlike String.compareTo");
    }

    @AfterAll
    static void stop() {
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @Override
    protected DeploymentStore emptyStore() {
        pool.query("truncate deployments").execute().await();
        return new PgDeploymentStore(pool);
    }
}
