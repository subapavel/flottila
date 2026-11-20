package dev.rail8.runtime.deploy;

import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres record store passes the store contract over the {@code deployments} table, on a container whose database collates
 * en_US.utf8 (the image's default): {@code listIsSortedByName} holds there too. {@link #aDeleteRemovesTheTableRow} reads the
 * table itself, so a store that only hides the row from its own {@code list} shows; {@link #aLockIsFreedWithItsConnection} closes a
 * holder's connection under it.
 */
class PgDeploymentStoreIT extends DeploymentStoreContract {

    static final SharedPostgres pg = SharedPostgres.database();
    static Vertx vertx;
    static Pool pool;
    static PgConnectOptions connect;
    /** The connections the stores opened for their locks, latest last. */
    static final List<SqlConnection> opened = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
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
        return store(pool, connect);
    }

    /** A store over {@code on}, its lock on a connection of its own opened with {@code options}, kept in {@link #opened}. */
    static PgDeploymentStore store(Pool on, PgConnectOptions options) {
        return new PgDeploymentStore(on, () -> PgConnection.connect(vertx, options).map(c -> {
            opened.add(c);
            return (SqlConnection) c;
        }));
    }

    /** A lock whose connection closes without freeing it — its node vanished — is freed with the connection: another holder takes it. */
    @Test
    void aLockIsFreedWithItsConnection() {
        DeploymentStore s = emptyStore();
        DeploymentStore.PoolLock held = s.lock().await().orElseThrow();
        assertTrue(s.lock().await().isEmpty(), "fixture: the lock is held");

        opened.get(opened.size() - 2).close().await();   // the holder's connection; the refused one was opened after it

        DeploymentStore.PoolLock next = s.lock().await().orElseThrow(() -> new AssertionError("the lock went with its connection"));
        next.close().await();
        assertTrue(held.save(record("shop", "1.0.0", C_1, SHA_1, AT)).transform(done -> io.vertx.core.Future.succeededFuture(done.failed())).await(),
                "the lost lock writes nothing");
    }

    /** delete removes the row from the {@code deployments} table, read with plain SQL: the other package's row stays. */
    @Test
    void aDeleteRemovesTheTableRow() {
        save(record("shop", "1.0.0", SHA_1, List.of(pin("common", "1.2.0", SHA_2, C_2))));
        save(record("common", "1.2.0", C_2, SHA_2, AT));

        delete("shop");

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

    /**
     * A row written at V26 names no at-least-once flows: V27 deletes it and starts the pool's generation at 0, and the store saves
     * and reads rows that name them.
     */
    @Test
    void aRowWrittenBeforeV27IsDeleted() throws Exception {
        String database = "v26_row";
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String url = path.substring(0, path.lastIndexOf('/') + 1) + database + (query < 0 ? "" : base.substring(query));
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("26").load().migrate();
        PgConnectOptions options = new PgConnectOptions(connect).setDatabase(database);
        Pool own = PgBuilder.pool().connectingTo(options).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        try {
            own.query("insert into deployments(package, version, repository, commit, content, deployed_at, dependencies) values ('shop', '1.0.0', 'shop', '"
                    + C_1 + "', '" + SHA_1 + "', timestamptz '2026-10-27 09:30:00.123456+00', '[]'::jsonb)").execute().await();

            Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());

            assertEquals("27", r.schemaVersion(), "V27 applied over the V26 database");
            PgDeploymentStore store = store(own, options);
            assertEquals(new DeploymentStore.Snapshot(0, List.of()), store.read().await(), "the row is deleted; the generation starts at 0");
            DeploymentRecord shop = record("shop", "1.0.0", C_1, SHA_1, AT);
            DeploymentStore.PoolLock lock = store.lock().await().orElseThrow();
            assertEquals(1L, lock.save(shop).await());
            lock.close().await();
            assertEquals(new DeploymentStore.Snapshot(1, List.of(shop)), store.read().await(), "a row naming its at-least-once flows saves and reads");
        } finally {
            own.close().await();
        }
    }
}
