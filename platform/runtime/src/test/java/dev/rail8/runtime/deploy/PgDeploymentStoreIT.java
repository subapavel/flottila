package dev.rail8.runtime.deploy;

import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Postgres record store passes the store contract over the {@code deployments} table, on a container whose database collates
 * en_US.utf8 (the image's default): {@code listIsSortedByName} holds there too. {@link #aDeleteRemovesTheTableRow} reads the
 * table itself, so a store that only hides the row from its own {@code list} shows.
 */
class PgDeploymentStoreIT extends DeploymentStoreContract {

    static final SharedPostgres pg = SharedPostgres.database();
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
        s.save(record("shop", "1.0.0", SHA_1, List.of(pin("common", "1.2.0", SHA_2, C_2)))).await();
        s.save(record("common", "1.2.0", C_2, SHA_2, AT)).await();

        s.delete("shop").await();

        List<String> left = new java.util.ArrayList<>();
        pool.query("select package from deployments order by package").execute().await().forEach(row -> left.add(row.getString(0)));
        assertEquals(List.of("common"), left, "shop's row is deleted from the table, common's stays");
    }

    /** An audit row of a deploy: the actor, the action {@code deploy}, the subject {@code repository/<name>} and the detail as given. */
    @Test
    void anAuditRowIsWritten() {
        pool.query("delete from audit where action = 'deploy'").execute().await();
        JsonObject detail = new JsonObject().put("repository", "shop").put("commit", C_1).put("outcome", "deployed");
        emptyStore().audit("ops@example", "shop", detail).await();

        Row row = pool.query("select actor, action, subject, detail from audit where action = 'deploy'").execute().await().iterator().next();
        assertEquals(List.of("ops@example", "deploy", "repository/shop"), List.of(row.getString(0), row.getString(1), row.getString(2)));
        assertEquals(detail, new JsonObject(row.getValue(3).toString()), "the detail as given");
    }

    /** A row written at V25 names an archive: V26 deletes it, and the store saves and lists rows from git. */
    @Test
    void aRowNamingAnArchiveIsDeleted() throws Exception {
        String database = "v25_row";
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String url = path.substring(0, path.lastIndexOf('/') + 1) + database + (query < 0 ? "" : base.substring(query));
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("25").load().migrate();
        Pool own = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword())).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        try {
            own.query("insert into deployments(package, version, revision, archive_sha256, deployed_at) values ('shop', '1.0.0', 'r1', '" + "1".repeat(64)
                    + "', timestamptz '2026-10-27 09:30:00.123456+00')").execute().await();

            Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());

            assertEquals("26", r.schemaVersion(), "V26 applied over the V25 database");
            PgDeploymentStore store = new PgDeploymentStore(own);
            assertEquals(List.of(), store.list().await(), "the row naming an archive is deleted");
            DeploymentRecord shop = record("shop", "1.0.0", SHA_1, List.of(pin("common", "1.2.0", SHA_2, C_2)));
            store.save(shop).await();
            assertEquals(List.of(shop), store.list().await(), "a row from git saves and lists");
        } finally {
            own.close().await();
        }
    }
}
