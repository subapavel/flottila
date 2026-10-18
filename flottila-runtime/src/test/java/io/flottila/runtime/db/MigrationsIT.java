package io.flottila.runtime.db;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0-08: a fresh Postgres → migrated → the milestone-1 tables and indexes exist; migrating again changes nothing. */
class MigrationsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(2)).using(vertx).build();
    }

    @AfterAll
    static void stop() { pool.close().await(); vertx.close().await(); pg.stop(); }

    private static Set<String> tables() {
        Set<String> names = new TreeSet<>();
        for (Row r : pool.query("select tablename from pg_tables where schemaname = 'public'").execute().await()) names.add(r.getString(0));
        return names;
    }

    @Test
    void freshDatabaseIsMigratedToV1AndASecondRunIsANoOp() {
        Set<String> app = Set.of("dlq", "message_keys", "messages", "payloads", "work");
        assertTrue(java.util.Collections.disjoint(tables(), app), "fresh database has none of the application tables: " + tables());
        Migrations.Result first = Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        assertEquals(1, first.migrationsExecuted());
        assertEquals("1", first.schemaVersion());
        assertEquals(Set.of("dlq", "flyway_schema_history", "message_keys", "messages", "payloads", "work"), tables());
        Set<String> indexes = new TreeSet<>();
        for (Row r : pool.query("select indexname from pg_indexes where schemaname = 'public'").execute().await()) indexes.add(r.getString(0));
        assertTrue(indexes.containsAll(List.of("message_keys_name_value", "work_flow_seq", "messages_pkey", "payloads_pkey", "dlq_pkey", "work_pkey")), indexes.toString());
        Migrations.Result second = Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        assertEquals(0, second.migrationsExecuted(), "already at V1");
        assertEquals("1", second.schemaVersion());
        long applied = pool.query("select count(*) from flyway_schema_history where success").execute().await().iterator().next().getLong(0);
        assertEquals(1, applied);
    }

    @Test
    void aLocationWithoutMigrationsIsAMisconfigurationNotASilentSuccess() {
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), "classpath:db/nowhere"));
        assertTrue(e.getMessage().contains("db/nowhere"), e.getMessage());
    }

    @Test
    void migrationsAreReadFromTheRuntimeClasspath() {
        assertTrue(Migrations.class.getResource("/db/migration/V1__m1_schema.sql") != null,
                "deploy/postgres/V*.sql is packaged under db/migration on the runtime classpath");
    }
}
