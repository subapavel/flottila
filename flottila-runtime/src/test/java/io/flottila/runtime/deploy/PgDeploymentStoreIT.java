package io.flottila.runtime.deploy;

import io.flottila.runtime.db.Migrations;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-09c3a (D-070): the Postgres record store passes the store contract over V7's {@code deployments} table, on a container
 * whose database collates en_US.utf8 (the image's default): {@code listIsSortedByName} holds there too.
 *
 * <p>P2-02b (D-087): V9 adds {@code dependencies jsonb not null default '[]'::jsonb}; a row written before it reads as no pins.
 *
 * <p>P2-02c (D-095): the contract's {@code aDeleteRemovesOnlyThatPackagesRow} runs here too; {@link #aDeleteRemovesTheTableRow}
 * reads the table itself, so a store that only hides the row from its own {@code list} shows.
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

    /** delete removes the row from the {@code deployments} table, read with plain SQL: the other package's row stays. */
    @Test
    void aDeleteRemovesTheTableRow() {
        DeploymentStore s = emptyStore();
        s.save(record("shop", "1.0.0", SHA_1, List.of(new DeploymentRecord.Pin("common", "1.2.0", SHA_2)))).await();
        s.save(record("common", "1.2.0", Optional.empty(), SHA_2, AT)).await();

        s.delete("shop").await();

        List<String> left = new java.util.ArrayList<>();
        pool.query("select package from deployments order by package").execute().await().forEach(row -> left.add(row.getString(0)));
        assertEquals(List.of("common"), left, "shop's row is deleted from the table, common's stays");
    }

    /** A row written at V8 (no dependencies column), then V9 applied: the store lists it with no pins, every other field as written. */
    @Test
    void aV7RowReadsAsNoPins() throws Exception {
        String database = "v7_row";
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String url = path.substring(0, path.lastIndexOf('/') + 1) + database + (query < 0 ? "" : base.substring(query));
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("8").load().migrate();
        Pool own = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword())).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        try {
            own.query("insert into deployments(package, version, revision, archive_sha256, deployed_at) values ('shop', '1.0.0', 'r1', '" + SHA_1
                    + "', timestamptz '2026-10-27 09:30:00.123456+00')").execute().await();

            Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());

            assertEquals("12", r.schemaVersion(), "V9 (and V10, P2-07, V11, P2-07c, and V12, CORE-4c1) applied over the V8 database");
            assertEquals(List.of(new DeploymentRecord("shop", "1.0.0", Optional.of("r1"), SHA_1, Instant.parse("2026-10-27T09:30:00.123456Z"), List.of())),
                    new PgDeploymentStore(own).list().await(), "the row written before V9 lists with no pins");
        } finally {
            own.close().await();
        }
    }
}
