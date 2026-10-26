package io.flottila.runtime.db;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-08: a fresh Postgres → migrated → the milestone-1 tables and indexes exist; migrating again changes nothing.
 * FIX-5: every case runs on a database of its own, created on the class's container before the case.
 */
// the order that failed in P1-04 (FIX-5): the fresh case leaves V3 applied, the empty location then sees it as current and throws nothing
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    /** The case's own database: its JDBC URL (for Flyway) and its pool (for the assertions). */
    String url;
    Pool pool;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() { vertx.close().await(); pg.stop(); }

    @BeforeEach
    void createTheCaseDatabase() throws SQLException {
        String database = "case_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
        // getJdbcUrl() is jdbc:postgresql://host:port/<db> followed by the container's query string (?loggerLevel=OFF)
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String suffix = "/" + pg.getDatabaseName();
        String casePath = path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) + "/" + database : path;
        assertNotEquals(path, casePath, "the container URL's database segment is replaced by the case's database: " + base);
        url = casePath + (query < 0 ? "" : base.substring(query));
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(database).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterEach
    void closeThePool() { if (pool != null) pool.close().await(); }

    private Set<String> tables() {
        Set<String> names = new TreeSet<>();
        for (Row r : pool.query("select tablename from pg_tables where schemaname = 'public'").execute().await()) names.add(r.getString(0));
        return names;
    }

    @Test
    @Order(1)
    void freshDatabaseIsMigratedToV1AndASecondRunIsANoOp() {
        Set<String> app = Set.of("dlq", "message_keys", "messages", "payloads", "work");
        assertTrue(java.util.Collections.disjoint(tables(), app), "fresh database has none of the application tables: " + tables());
        Migrations.Result first = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals(5, first.migrationsExecuted(), "V1 (m1 schema), V2 (snapshot columns, P1-04), V3 (DLQ by reference, P1-05), V4 (work attempts, P1-08b), V5 (admin paging, P1-09a)");
        assertEquals("5", first.schemaVersion());
        assertEquals(Set.of("dlq", "flyway_schema_history", "message_keys", "messages", "payloads", "work"), tables());
        Set<String> indexes = new TreeSet<>();
        for (Row r : pool.query("select indexname from pg_indexes where schemaname = 'public'").execute().await()) indexes.add(r.getString(0));
        assertTrue(indexes.containsAll(List.of("message_keys_name_value", "work_flow_seq", "dlq_parked_at", "messages_pkey", "payloads_pkey", "dlq_pkey", "work_pkey")), indexes.toString());
        java.util.Map<String, String> defs = new java.util.HashMap<>();
        for (Row r : pool.query("select indexname, indexdef from pg_indexes where schemaname = 'public'").execute().await()) defs.put(r.getString(0), r.getString(1));
        assertTrue(defs.get("message_keys_name_value").endsWith("(name, value, seq)"), "V5: key search in seq order: " + defs.get("message_keys_name_value"));
        assertTrue(defs.get("message_keys_name_value_message").startsWith("CREATE UNIQUE INDEX") && defs.get("message_keys_name_value_message").endsWith("(name, value, message_id)"),
                "V5: one key row per (name, value, message): " + defs.get("message_keys_name_value_message"));
        assertTrue(String.valueOf(defs.get("work_held")).endsWith("(seq) WHERE (held_at IS NOT NULL)"), "V5: held work rows, partial: " + defs.get("work_held"));
        Migrations.Result second = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals(0, second.migrationsExecuted(), "already at V5");
        assertEquals("5", second.schemaVersion());
        long applied = pool.query("select count(*) from flyway_schema_history where success").execute().await().iterator().next().getLong(0);
        assertEquals(5, applied);
    }

    @Test
    @Order(2)
    void aLocationWithoutMigrationsIsAMisconfigurationNotASilentSuccess() {
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> Migrations.migrate(url, pg.getUsername(), pg.getPassword(), "classpath:db/nowhere"));
        assertTrue(e.getMessage().contains("db/nowhere"), e.getMessage());
    }

    @Test
    @Order(3)
    void migrationsAreReadFromTheRuntimeClasspath() {
        assertTrue(Migrations.class.getResource("/db/migration/V1__m1_schema.sql") != null,
                "deploy/postgres/V*.sql is packaged under db/migration on the runtime classpath");
    }

    /**
     * V5 (D-057 a) on a database that already holds key rows: V1..V4 wrote one row per (message, step) carrying a key, so a
     * message has duplicates. V5 numbers the rows in the order they were written, deletes each duplicate (name, value,
     * message_id) keeping the lowest number — the first row — and makes the triple unique.
     */
    @Test
    @Order(4)
    void v5KeepsTheFirstKeyRowOfEachDuplicate() {
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("4").load().migrate();
        String[][] rows = {
                {"invoiceId", "4711", "m-2", "3"},
                {"invoiceId", "4711", "m-1", "0"},
                {"invoiceId", "4711", "m-2", "1"},   // m-2 again, a lower step: the first row (step 3) stays
                {"invoiceId", "4711", "m-1", "2"},
                {"orderNo", "o-1", "m-1", "0"},
                {"invoiceId", "4711", "m-2", "3"},   // an exact duplicate
                {"invoiceId", "4712", "m-3", "5"}};
        for (String[] r : rows) {
            pool.preparedQuery("insert into message_keys(name, value, message_id, step) values ($1, $2, $3, $4)")
                    .execute(io.vertx.sqlclient.Tuple.of(r[0], r[1], r[2], Integer.parseInt(r[3]))).await();
        }
        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals(1, r.migrationsExecuted(), "V5 on top of V4");
        assertEquals("5", r.schemaVersion());
        List<String> kept = new java.util.ArrayList<>();
        for (Row row : pool.query("select name, value, message_id, step from message_keys order by seq").execute().await()) {
            kept.add(row.getString(0) + "=" + row.getString(1) + " " + row.getString(2) + "@" + row.getInteger(3));
        }
        assertEquals(List.of("invoiceId=4711 m-2@3", "invoiceId=4711 m-1@0", "orderNo=o-1 m-1@0", "invoiceId=4712 m-3@5"), kept,
                "the first row of each (name, value, message) survives, numbered in the order the rows were written");
        Exception duplicate = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> pool.query(
                "insert into message_keys(name, value, message_id, step) values ('invoiceId', '4711', 'm-1', 9)").execute().await(), "the triple is unique now");
        assertTrue(duplicate.getMessage().contains("duplicate key"), duplicate.getMessage());
        pool.query("insert into message_keys(name, value, message_id, step) values ('invoiceId', '4711', 'm-4', 0)").execute().await();
        List<String> order = new java.util.ArrayList<>();
        for (Row row : pool.query("select message_id from message_keys where name = 'invoiceId' and value = '4711' order by seq").execute().await()) {
            order.add(row.getString(0));
        }
        assertEquals(List.of("m-2", "m-1", "m-4"), order, "a row written after V5 is numbered after the migrated ones");
    }
}
