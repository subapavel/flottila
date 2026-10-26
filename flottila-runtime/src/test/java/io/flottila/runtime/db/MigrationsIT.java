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
        assertEquals(4, first.migrationsExecuted(), "V1 (m1 schema), V2 (snapshot columns, P1-04), V3 (DLQ by reference, P1-05), V4 (work attempts, P1-08b)");
        assertEquals("4", first.schemaVersion());
        assertEquals(Set.of("dlq", "flyway_schema_history", "message_keys", "messages", "payloads", "work"), tables());
        Set<String> indexes = new TreeSet<>();
        for (Row r : pool.query("select indexname from pg_indexes where schemaname = 'public'").execute().await()) indexes.add(r.getString(0));
        assertTrue(indexes.containsAll(List.of("message_keys_name_value", "work_flow_seq", "dlq_parked_at", "messages_pkey", "payloads_pkey", "dlq_pkey", "work_pkey")), indexes.toString());
        Migrations.Result second = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals(0, second.migrationsExecuted(), "already at V4");
        assertEquals("4", second.schemaVersion());
        long applied = pool.query("select count(*) from flyway_schema_history where success").execute().await().iterator().next().getLong(0);
        assertEquals(4, applied);
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
}
