package dev.rail8.runtime.deploy;

import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.queue.NotAdmittedException;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
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
import io.vertx.sqlclient.Transaction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Postgres record store passes the store contract over the {@code deployments} table, on a container whose database collates
 * en_US.utf8 (the image's default): {@code listIsSortedByName} holds there too. {@link #aDeleteRemovesTheTableRow} reads the
 * table itself, so a store that only hides the row from its own {@code list} shows; {@link #aLockIsFreedWithItsConnection} closes a
 * holder's connection under it. A work queue built with admission admits a write for a flow a record runs at-least-once, a mark
 * retires or the node names, and refuses the rest; a mark ends only once no work row of its flow is left and no write admitted for
 * it is open.
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
        pool.query("truncate deployments, retiring").execute().await();
        pool.query("delete from work").execute().await();
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

    /**
     * A save and a delete under a lock whose generation another writer raised, by plain SQL, both fail and write nothing: the
     * generation is compared and set in the transaction that writes the row.
     */
    @Test
    void aWriteUnderAGenerationRaisedMeanwhileFailsAndWritesNothing() {
        save(record("shop", "1.0.0", C_1, SHA_1, AT));
        DeploymentStore.PoolLock held = lock();
        long read = held.snapshot().generation();
        pool.query("update deploy_pool set generation = generation + 1").execute().await();

        assertTrue(held.save(record("billing", "1.0.0", C_2, SHA_2, AT)).transform(done -> io.vertx.core.Future.succeededFuture(done.failed())).await(),
                "the save under the stale generation fails");
        assertTrue(held.delete("shop").transform(done -> io.vertx.core.Future.succeededFuture(done.failed())).await(),
                "the delete under it too");
        held.close().await();

        List<String> rows = new java.util.ArrayList<>();
        pool.query("select package from deployments order by package").execute().await().forEach(row -> rows.add(row.getString(0)));
        assertEquals(List.of("shop"), rows, "no row was written or deleted");
        long now = pool.query("select generation from deploy_pool").execute().await().iterator().next().getLong(0);
        assertEquals(read + 1, now, "only the plain SQL raised the generation");
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

    /**
     * A write is admitted for the flows a record runs at-least-once, a mark retires or the node runs without a record yet; one that
     * names any other flow is refused with the flows refused. A queue built without admission admits every flow.
     */
    @Test
    void aWriteIsAdmittedOnlyForAFlowThePoolRunsRetiresOrTheNodeNames() {
        save(record("shop", "1.0.0", C_1, SHA_1, AT));
        save(new DeploymentRecord("shop", "2.0.0", "shop", C_2, SHA_2, AT, List.of(), List.of("shop/orders")));
        assertEquals(List.of("shop/audit"), store.read().await().marked(), "fixture: shop/audit is retired");
        PgWorkQueue admitting = new PgWorkQueue(pool, "node-1", null, timer(), () -> Set.of("new/flow"));

        admit(admitting, List.of("shop/orders", "shop/audit", "new/flow"));   // run by a record, retired by a mark, named by the node

        NotAdmittedException refused = assertThrows(NotAdmittedException.class, () -> admit(admitting, List.of("other/x", "shop/orders", "gone/flow")));
        assertEquals(List.of("gone/flow", "other/x"), refused.flows(), "the flows refused, sorted");
        admit(new PgWorkQueue(pool, "node-1", null, timer()), List.of("gone/flow"));   // built without admission
    }

    /**
     * A mark ends only once no work row of its flow is left and no write admitted for it is open: a row keeps it, an open
     * transaction that admitted a message for the flow keeps it, and once that commits the next {@code endMarks} ends it. A write
     * for the flow is then refused.
     */
    @Test
    void aMarkEndsOnceItsRowsAreGoneAndNoAdmittedWriteIsOpen() {
        save(record("shop", "1.0.0", C_1, SHA_1, AT));
        delete("shop");
        PgWorkQueue admitting = new PgWorkQueue(pool, "node-1", null, timer(), Set::of);
        pool.query("insert into work(id, flow, step) values ('m-1', 'shop/audit', 1)").execute().await();

        store.endMarks().await();
        assertEquals(List.of("shop/audit"), store.read().await().marked(), "shop/orders had no row and ends; the row keeps shop/audit");

        pool.query("delete from work where id = 'm-1'").execute().await();
        SqlConnection writer = pool.getConnection().await();
        try {
            Transaction open = writer.begin().await();
            admitting.admit(writer, List.of("shop/audit")).await();   // admitted: it holds the flow's admission lock until it ends

            store.endMarks().await();
            assertEquals(List.of("shop/audit"), store.read().await().marked(), "the open write keeps the mark");

            open.commit().await();
        } finally {
            writer.close().await();
        }
        store.endMarks().await();
        assertEquals(List.of(), store.read().await().marked(), "no row and no open write: the mark ends");
        assertThrows(NotAdmittedException.class, () -> admit(admitting, List.of("shop/audit")), "the flow is no longer admitted");
    }

    /** {@code queue} admits {@code flows} in a transaction of its own, which then commits. */
    static void admit(PgWorkQueue queue, Collection<String> flows) {
        pool.withTransaction(tx -> queue.admit(tx, flows)).await();
    }

    static FakeTimer timer() {
        return new FakeTimer(FakeClock.at(Instant.parse("2026-10-27T09:30:00Z")));
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

            assertEquals("28", r.schemaVersion(), "the migrations after V26 applied over the V26 database");
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
