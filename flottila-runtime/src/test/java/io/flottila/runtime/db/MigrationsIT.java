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
 * FIX-5: every case runs on a database of its own, created on the class's container before the case. P2-07: V10 indexes the
 * retention job's scans by age. P2-07c (D-097, F-23): V11 moves the hash algorithm onto {@code messages} and drops {@code payloads}.
 * CORE-4c1 (D-105, CORE-3 item 5): V12 adds the body journal's tables and the work row's body ref; the counts below moved from 11
 * to 12 because V12 is one more packaged version (a changed expectation, not a weakened one). CORE-4c3: V13 adds
 * {@code work.lost_at} and {@code journal_losses}; the counts moved from 12 to 13 for the same reason, and the fresh table set
 * gained {@code journal_losses}. P2-15a: V14 adds {@code nodes} and {@code work.doubts}; the counts moved from 13 to 14 for the
 * same reason, and the fresh table set gained {@code nodes}. P2-16a: V15 adds the index {@code work_flow_key_seq}; the counts moved
 * from 14 to 15 for the same reason, the table set unchanged.
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
        Set<String> app = Set.of("bodies", "deployments", "dlq", "message_keys", "messages", "payloads", "processed_ids", "work", "body_refs", "journal_keys",
                "journal_nodes", "journal_segments", "journal_losses", "nodes");
        assertTrue(java.util.Collections.disjoint(tables(), app), "fresh database has none of the application tables: " + tables());
        Migrations.Result first = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals(15, first.migrationsExecuted(), "V1 (m1 schema), V2 (snapshot columns, P1-04), V3 (DLQ by reference, P1-05), V4 (work attempts, P1-08b), V5 (admin paging, P1-09a), V6 (bodies, P1-09h), V7 (deployments, P1-09c3a), V8 (processed_ids, P2-02D), V9 (deployment dependencies, P2-02b), V10 (retention indexes, P2-07), V11 (payloads dropped, P2-07c), V12 (body journal, CORE-4c1), V13 (journal losses, CORE-4c3), V14 (node lease, P2-15a), V15 (work key index, P2-16a)");
        assertEquals("15", first.schemaVersion());
        assertEquals(Set.of("body_refs", "bodies", "deployments", "dlq", "flyway_schema_history", "journal_keys", "journal_losses", "journal_nodes", "journal_segments",
                "message_keys", "messages", "nodes", "processed_ids", "work"), tables(), "V11 dropped payloads (D-097); V12 added the journal's four tables (CORE-3 item 5); V13 journal_losses (CORE-4c3); V14 nodes (P2-15a)");
        Set<String> indexes = new TreeSet<>();
        for (Row r : pool.query("select indexname from pg_indexes where schemaname = 'public'").execute().await()) indexes.add(r.getString(0));
        assertTrue(indexes.containsAll(List.of("message_keys_name_value", "work_flow_seq", "dlq_parked_at", "messages_pkey", "dlq_pkey", "work_pkey")), indexes.toString());
        assertTrue(!indexes.contains("payloads_pkey"), "V11: payloads' key went with the table: " + indexes);
        java.util.Map<String, String> defs = new java.util.HashMap<>();
        for (Row r : pool.query("select indexname, indexdef from pg_indexes where schemaname = 'public'").execute().await()) defs.put(r.getString(0), r.getString(1));
        assertTrue(defs.get("message_keys_name_value").endsWith("(name, value, seq)"), "V5: key search in seq order: " + defs.get("message_keys_name_value"));
        assertTrue(defs.get("message_keys_name_value_message").startsWith("CREATE UNIQUE INDEX") && defs.get("message_keys_name_value_message").endsWith("(name, value, message_id)"),
                "V5: one key row per (name, value, message): " + defs.get("message_keys_name_value_message"));
        assertTrue(String.valueOf(defs.get("work_held")).endsWith("(seq) WHERE (held_at IS NOT NULL)"), "V5: held work rows, partial: " + defs.get("work_held"));
        assertTrue(String.valueOf(defs.get("work_flow_key_seq")).endsWith("(flow, partition_key, seq) WHERE (partition_key IS NOT NULL)"),
                "V15 (P2-16a): the keyed rows of a flow by key and seq, partial: " + defs.get("work_flow_key_seq"));
        assertBodiesTable();
        Migrations.Result second = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals(0, second.migrationsExecuted(), "already at V15");
        assertEquals("15", second.schemaVersion());
        long applied = pool.query("select count(*) from flyway_schema_history where success").execute().await().iterator().next().getLong(0);
        assertEquals(15, applied);
    }

    /**
     * V6 (D-058, D-061): {@code bodies} keyed by message id, step and part — never by content — with the bytes not null and
     * stored out of line uncompressed (storage {@code external}: bodies are mostly compressed or small, and a read is a slice).
     */
    private void assertBodiesTable() {
        java.util.Map<String, String> columns = new java.util.LinkedHashMap<>();
        for (Row r : pool.query("select column_name, data_type, is_nullable, column_default from information_schema.columns "
                + "where table_schema = 'public' and table_name = 'bodies' order by ordinal_position").execute().await()) {
            columns.put(r.getString(0), r.getString(1) + (r.getString(2).equals("NO") ? " not null" : "") + (r.getString(3) == null ? "" : " default " + r.getString(3)));
        }
        assertEquals(java.util.Map.of("message_id", "text not null", "step", "integer not null", "part", "text not null", "bytes", "bytea not null",
                "created_at", "timestamp with time zone not null default now()"), columns, "V6: the bodies columns");
        assertEquals(List.of("message_id", "step", "part", "bytes", "created_at"), List.copyOf(columns.keySet()), "in this order");
        String pk = pool.query("select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'bodies'::regclass and contype = 'p'")
                .execute().await().iterator().next().getString(0);
        assertEquals("PRIMARY KEY (message_id, step, part)", pk, "V6: one row per message, step and part");
        Row bytes = pool.query("select attnotnull, attstorage::text from pg_attribute where attrelid = 'bodies'::regclass and attname = 'bytes'")
                .execute().await().iterator().next();
        assertTrue(bytes.getBoolean(0), "V6: bytes not null");
        assertEquals("e", bytes.getString(1), "V6: bytes storage external (out of line, not compressed)");
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
        assertEquals(11, r.migrationsExecuted(), "V5, V6, V7, V8, V9, V10, V11, V12, V13, V14 and V15 on top of V4");
        assertEquals("15", r.schemaVersion());
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

    /**
     * V7 (D-070, P1-09c3a): {@code deployments} holds one row per package — its name the primary key, shared by every node of the
     * pool — with the version, the revision (nullable), the archive's SHA-256 (64 lower-case hex digits, checked by the database)
     * and when it was deployed; and no bytes: the archive lives in the archive store, keyed by that SHA-256.
     */
    @Test
    @Order(5)
    void v7DeploymentsHoldsTheRecordAndNoBytes() {
        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals("15", r.schemaVersion());
        java.util.Map<String, String> columns = new java.util.LinkedHashMap<>();
        for (Row c : pool.query("select column_name, data_type, is_nullable from information_schema.columns "
                + "where table_schema = 'public' and table_name = 'deployments' order by ordinal_position").execute().await()) {
            columns.put(c.getString(0), c.getString(1) + (c.getString(2).equals("NO") ? " not null" : ""));
        }
        assertEquals(java.util.Map.of("package", "text not null", "version", "text not null", "revision", "text", "archive_sha256", "text not null",
                "deployed_at", "timestamp with time zone not null", "dependencies", "jsonb not null"), columns, "V7: the deployments columns, V9's dependencies");
        assertEquals(List.of("package", "version", "revision", "archive_sha256", "deployed_at", "dependencies"), List.copyOf(columns.keySet()), "in this order");
        assertTrue(columns.values().stream().noneMatch(t -> t.startsWith("bytea")), "no bytes in the record: the archive is in the archive store");
        String pk = pool.query("select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'deployments'::regclass and contype = 'p'")
                .execute().await().iterator().next().getString(0);
        assertEquals("PRIMARY KEY (package)", pk, "V7: one row per package");

        String sha = "0123456789abcdef".repeat(4);
        String insert = "insert into deployments(package, version, revision, archive_sha256, deployed_at) values ($1, $2, $3, $4, now())";
        pool.preparedQuery(insert).execute(io.vertx.sqlclient.Tuple.of("shop", "1.0.0", null, sha)).await();   // no revision: null
        assertEquals("23505", sqlState(() -> pool.preparedQuery(insert).execute(io.vertx.sqlclient.Tuple.of("shop", "2.0.0", "r2", sha)).await()),
                "a second row for a package is a unique violation");
        for (String bad : List.of(sha.toUpperCase(java.util.Locale.ROOT), sha.substring(1), sha + "0", sha.substring(0, 63) + "g", "sha256:" + sha.substring(7))) {
            assertEquals("23514", sqlState(() -> pool.preparedQuery(insert).execute(io.vertx.sqlclient.Tuple.of("other", "1.0.0", null, bad)).await()),
                    "archive_sha256 '" + bad + "' is refused by the check: 64 lower-case hex digits only");
        }
        long rows = pool.query("select count(*) from deployments").execute().await().iterator().next().getLong(0);
        assertEquals(1L, rows, "only the valid row was written");
    }

    /**
     * V8 (D-084, P2-02D): {@code processed_ids} holds one row per (scope, key_hash) — the qualified flow id and the SHA-256 of a
     * consumer-supplied dedupe key (32 bytes, checked by the database) — naming the first message saved under it and when; no
     * foreign key (the retention job deletes it with or after its message's rows, P2-07).
     */
    @Test
    @Order(6)
    void v8ProcessedIdsHoldsOneRowPerScopeAndKeyHash() {
        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals("15", r.schemaVersion());
        java.util.Map<String, String> columns = new java.util.LinkedHashMap<>();
        for (Row c : pool.query("select column_name, data_type, is_nullable from information_schema.columns "
                + "where table_schema = 'public' and table_name = 'processed_ids' order by ordinal_position").execute().await()) {
            columns.put(c.getString(0), c.getString(1) + (c.getString(2).equals("NO") ? " not null" : ""));
        }
        assertEquals(java.util.Map.of("scope", "text not null", "key_hash", "bytea not null", "message_id", "text not null",
                "created_at", "timestamp with time zone not null"), columns, "V8: the processed_ids columns");
        assertEquals(List.of("scope", "key_hash", "message_id", "created_at"), List.copyOf(columns.keySet()), "in this order");
        List<String> constraints = new java.util.ArrayList<>();
        for (Row c : pool.query("select contype, pg_get_constraintdef(oid) from pg_constraint where conrelid = 'processed_ids'::regclass order by contype")
                .execute().await()) {
            constraints.add(c.getString(0) + " " + c.getString(1));
        }
        assertTrue(constraints.contains("p PRIMARY KEY (scope, key_hash)"), "V8: one row per scope and key hash: " + constraints);
        assertTrue(constraints.stream().noneMatch(c -> c.startsWith("f ")), "V8: no foreign key: " + constraints);

        String insert = "insert into processed_ids(scope, key_hash, message_id, created_at) values ($1, $2, $3, now())";
        io.vertx.core.buffer.Buffer hash = io.vertx.core.buffer.Buffer.buffer(new byte[32]);
        pool.preparedQuery(insert).execute(io.vertx.sqlclient.Tuple.of("shop/orders", hash, "m-1")).await();
        assertEquals("23505", sqlState(() -> pool.preparedQuery(insert).execute(io.vertx.sqlclient.Tuple.of("shop/orders", hash, "m-2")).await()),
                "a second row for a scope and key hash is a unique violation");
        pool.preparedQuery(insert).execute(io.vertx.sqlclient.Tuple.of("shop/invoices", hash, "m-3")).await();   // another scope: its own row
        for (int length : new int[] {0, 31, 33, 64}) {
            assertEquals("23514", sqlState(() -> pool.preparedQuery(insert).execute(io.vertx.sqlclient.Tuple.of("other/flow",
                    io.vertx.core.buffer.Buffer.buffer(new byte[length]), "m-4")).await()), "a key_hash of " + length + " bytes is refused by the check: 32 only");
        }
        long rows = pool.query("select count(*) from processed_ids").execute().await().iterator().next().getLong(0);
        assertEquals(2L, rows, "only the valid rows were written");
    }

    /**
     * V9 (D-087, P2-02b): {@code deployments.dependencies} is jsonb, not null, default {@code '[]'::jsonb}: a row written before it
     * reads as no pins, an insert that names no dependencies gets an empty array, an explicit null is refused.
     */
    @Test
    @Order(7)
    void v9DependenciesDefaultToAnEmptyArray() {
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("8").load().migrate();
        String sha = "0123456789abcdef".repeat(4);
        String v7Insert = "insert into deployments(package, version, revision, archive_sha256, deployed_at) values ($1, $2, $3, $4, now())";
        pool.preparedQuery(v7Insert).execute(io.vertx.sqlclient.Tuple.of("before", "1.0.0", null, sha)).await();

        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());

        assertEquals(7, r.migrationsExecuted(), "V9, V10, V11, V12, V13, V14 and V15 on top of V8");
        assertEquals("15", r.schemaVersion());
        Row column = pool.query("select column_default, is_nullable, data_type from information_schema.columns where table_schema = 'public' "
                + "and table_name = 'deployments' and column_name = 'dependencies'").execute().await().iterator().next();
        assertEquals("'[]'::jsonb", column.getString(0), "V9: the default");
        assertEquals("NO", column.getString(1), "V9: not null");
        assertEquals("jsonb", column.getString(2));
        pool.preparedQuery(v7Insert).execute(io.vertx.sqlclient.Tuple.of("after", "1.0.0", null, sha)).await();
        List<String> rows = new java.util.ArrayList<>();
        for (Row row : pool.query("select package, dependencies::text from deployments order by package").execute().await()) {
            rows.add(row.getString(0) + " " + row.getString(1));
        }
        assertEquals(List.of("after []", "before []"), rows, "the row written before V9 and one naming no dependencies: an empty array each");
        assertEquals("23502", sqlState(() -> pool.preparedQuery("insert into deployments(package, version, revision, archive_sha256, deployed_at, dependencies) "
                + "values ('nulled', '1.0.0', null, '" + sha + "', now(), null)").execute().await()), "an explicit null is refused: not null");
    }

    /**
     * V10 (D-088, P2-07): the retention job's keyset scans by age — one plain btree index per table it deletes from, led by
     * {@code created_at} and ending in the table's key, so a batch's cursor {@code (created_at, key) > (...)} is one index range.
     */
    @Test
    @Order(8)
    void v10IndexesTheRetentionScansByAge() {
        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals("15", r.schemaVersion());
        java.util.Map<String, String> expected = java.util.Map.of(
                "messages", " USING btree (created_at, id, step)",
                "bodies", " USING btree (created_at, message_id, step, part)",
                "processed_ids", " USING btree (created_at, scope, key_hash)");
        for (java.util.Map.Entry<String, String> e : expected.entrySet()) {
            List<String> defs = new java.util.ArrayList<>();
            for (Row row : pool.preparedQuery("select indexdef from pg_indexes where schemaname = 'public' and tablename = $1")
                    .execute(io.vertx.sqlclient.Tuple.of(e.getKey())).await()) {
                defs.add(row.getString(0));
            }
            List<String> byAge = defs.stream().filter(d -> d.endsWith(e.getValue())).toList();
            assertEquals(1, byAge.size(), "V10: one index on " + e.getKey() + e.getValue() + ", among " + defs);
            assertTrue(byAge.get(0).startsWith("CREATE INDEX "), "a plain index, not unique, not partial: " + byAge.get(0));
        }
    }

    /**
     * V11 (D-097, F-23, P2-07c) on a database at V10 holding payloads rows of two algorithms and messages rows naming each: every
     * messages row keeps its own algorithm in {@code messages.hash_algorithm} (text, not null, default {@code 'sha256-64'}), its
     * body_hash and body_size unchanged; {@code payloads} is gone, and with it the messages → payloads foreign key — a row naming
     * a hash no table holds is written, one naming no algorithm gets the default, an explicit null is refused.
     */
    @Test
    @Order(9)
    void v11MovesTheHashAlgorithmOntoMessagesAndDropsPayloads() {
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("10").load().migrate();
        pool.query("insert into payloads(hash, size, algorithm) values ('\\x0000000000000001'::bytea, 10, 'sha256-64'), ('\\x0000000000000002'::bytea, 20, 'xxh3-64')")
                .execute().await();
        pool.query("insert into messages(id, step, flow, body_hash, body_size) values "
                + "('m-1', 0, 'shop/orders', '\\x0000000000000001'::bytea, 10), "
                + "('m-2', 0, 'shop/orders', '\\x0000000000000002'::bytea, 20), "
                + "('m-2', 1, 'shop/orders', '\\x0000000000000002'::bytea, 20), "
                + "('m-3', 0, 'shop/orders', '\\x0000000000000001'::bytea, 10)").execute().await();
        assertTrue(tables().contains("payloads"), "fixture: V10 still has payloads");

        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());

        assertEquals(5, r.migrationsExecuted(), "V11, V12, V13, V14 and V15 on top of V10");
        assertEquals("15", r.schemaVersion());
        List<String> rows = new java.util.ArrayList<>();
        for (Row row : pool.query("select id, step, hash_algorithm, encode(body_hash, 'hex'), body_size from messages order by id, step").execute().await()) {
            rows.add(row.getString(0) + "@" + row.getInteger(1) + " " + row.getString(2) + ":" + row.getString(3) + " " + row.getLong(4));
        }
        assertEquals(List.of("m-1@0 sha256-64:0000000000000001 10", "m-2@0 xxh3-64:0000000000000002 20", "m-2@1 xxh3-64:0000000000000002 20",
                "m-3@0 sha256-64:0000000000000001 10"), rows, "each row keeps the algorithm its payloads row named; hash and size unchanged");
        assertTrue(!tables().contains("payloads"), "V11 dropped payloads: " + tables());
        List<String> foreign = new java.util.ArrayList<>();
        for (Row c : pool.query("select conname, pg_get_constraintdef(oid) from pg_constraint where conrelid = 'messages'::regclass and contype = 'f'").execute().await()) {
            foreign.add(c.getString(0) + " " + c.getString(1));
        }
        assertEquals(List.of(), foreign, "V11: no foreign key on messages");
        Row column = pool.query("select data_type, is_nullable, column_default from information_schema.columns where table_schema = 'public' "
                + "and table_name = 'messages' and column_name = 'hash_algorithm'").execute().await().iterator().next();
        assertEquals(List.of("text", "NO", "'sha256-64'::text"), List.of(column.getString(0), column.getString(1), column.getString(2)),
                "V11: hash_algorithm text not null default 'sha256-64'");
        pool.query("insert into messages(id, step, flow, body_hash) values ('m-4', 0, 'shop/orders', '\\x00000000000000ff'::bytea)").execute().await();
        assertEquals("sha256-64", pool.query("select hash_algorithm from messages where id = 'm-4'").execute().await().iterator().next().getString(0),
                "a row naming a hash no table holds is written (no foreign key) and, naming no algorithm, gets the default");
        assertEquals("23502", sqlState(() -> pool.query("insert into messages(id, step, flow, body_hash, hash_algorithm) values "
                + "('m-5', 0, 'shop/orders', '\\x0000000000000001'::bytea, null)").execute().await()), "an explicit null is refused: not null");
    }

    /**
     * V12 (CORE-3 item 5, CORE-4c1, D-105): the body journal's tables — {@code journal_nodes(node pk, journal_id uuid)},
     * {@code journal_segments(node, segment, copies text[], created_at, pk(node, segment))}, {@code body_refs(message_id, step,
     * part, node, segment, position, length, archived_at, lost_at, created_at, pk(message_id, step, part))} with no keep and no
     * hash column, {@code journal_keys(key_id, tenant, wrapped bytea, created_at)} — and the work row's body ref
     * {@code work.body_node text, body_segment, body_position, body_length bigint}, all nullable (null = pre-V12). Indexes: the
     * partial {@code body_refs (node, segment, created_at) where archived_at is null} and the retention index by age (V10's
     * pattern); none on work's body columns (no index before CORE-GATE measures the need). No bytea in the new tables but the
     * wrapped tenant key: bytes live in the journal (D-105). {@code bodies} unchanged ({@link #assertBodiesTable} after V12).
     */
    @Test
    @Order(10)
    void v12AddsTheJournalTablesAndTheWorkRowsBodyRefWithoutBodyBytes() {
        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        assertEquals("15", r.schemaVersion());

        assertEquals(java.util.Map.of("node", "text not null", "journal_id", "uuid not null"), columnsOf("journal_nodes"),
                "V12: journal_nodes, one journal id per node");
        java.util.Map<String, String> segments = columnsOf("journal_segments");
        assertEquals(Set.of("node", "segment", "copies", "created_at"), segments.keySet(), "V12: the journal_segments columns");
        assertEquals("text not null", segments.get("node"));
        assertEquals("bigint not null", segments.get("segment"), "a per-node u64 segment number");
        assertTrue(segments.get("copies").startsWith("text[]"), "copies is a text array: " + segments.get("copies"));
        assertEquals("timestamp with time zone not null", segments.get("created_at"));
        assertEquals(java.util.Map.ofEntries(
                java.util.Map.entry("message_id", "text not null"), java.util.Map.entry("step", "integer not null"),
                java.util.Map.entry("part", "text not null"), java.util.Map.entry("node", "text not null"),
                java.util.Map.entry("segment", "bigint not null"), java.util.Map.entry("position", "bigint not null"),
                java.util.Map.entry("length", "bigint not null"), java.util.Map.entry("archived_at", "timestamp with time zone"),
                java.util.Map.entry("lost_at", "timestamp with time zone"), java.util.Map.entry("created_at", "timestamp with time zone not null")),
                columnsOf("body_refs"), "V12: body_refs — no keep column (every row is a kept ref), no hash column (FIX-35 compares bytes)");
        assertEquals(java.util.Map.of("key_id", "integer not null", "tenant", "text not null", "wrapped", "bytea not null",
                "created_at", "timestamp with time zone not null"), columnsOf("journal_keys"), "V12: journal_keys, a tenant key wrapped under the master");

        java.util.Map<String, String> work = columnsOf("work");
        for (String c : List.of("body_node", "body_segment", "body_position", "body_length")) assertTrue(work.containsKey(c), "V12: work." + c + ": " + work);
        assertEquals(List.of("text", "bigint", "bigint", "bigint"),
                List.of(work.get("body_node"), work.get("body_segment"), work.get("body_position"), work.get("body_length")),
                "V12: work's body ref, every column nullable (null = a pre-V12 item, its body in bodies)");

        assertEquals("PRIMARY KEY (node)", primaryKey("journal_nodes"));
        assertEquals("PRIMARY KEY (node, segment)", primaryKey("journal_segments"));
        assertEquals("PRIMARY KEY (message_id, step, part)", primaryKey("body_refs"));
        assertEquals("PRIMARY KEY (key_id)", primaryKey("journal_keys"));

        List<String> refIndexes = indexDefs("body_refs");
        List<String> unarchived = refIndexes.stream().filter(d -> d.endsWith(" USING btree (node, segment, created_at) WHERE (archived_at IS NULL)")).toList();
        assertEquals(1, unarchived.size(), "V12: the partial index of unarchived refs by segment, among " + refIndexes);
        List<String> byAge = refIndexes.stream().filter(d -> d.endsWith(" USING btree (created_at, message_id, step, part)")).toList();
        assertEquals(1, byAge.size(), "V12: the retention index by age ending in the key (V10's pattern), among " + refIndexes);
        assertTrue(byAge.get(0).startsWith("CREATE INDEX "), "a plain index, not unique, not partial: " + byAge.get(0));
        List<String> onBodyRef = indexDefs("work").stream().filter(d -> d.contains("body_node") || d.contains("body_segment")).toList();
        assertEquals(List.of(), onBodyRef, "V12: no index on work's body ref until CORE-GATE measures the need (CORE-3 item 5)");

        List<String> bytea = new java.util.ArrayList<>();
        for (Row c : pool.query("select table_name, column_name from information_schema.columns where table_schema = 'public' and data_type = 'bytea' "
                + "and (table_name in ('journal_nodes', 'journal_segments', 'body_refs', 'journal_keys') or (table_name = 'work' and column_name like 'body\\_%')) "
                + "order by table_name, column_name").execute().await()) {
            bytea.add(c.getString(0) + "." + c.getString(1));
        }
        assertEquals(List.of("journal_keys.wrapped"), bytea, "no body bytes in the new tables: the one bytea column is a wrapped 32-byte key");
        assertBodiesTable();
    }

    /**
     * V12 on a database at V11 holding a work row and its body: the row keeps every column it had and reads a null body ref —
     * a pre-V12 item, its body read from {@code bodies} (CORE-3 item 5) — and the bodies row is untouched.
     */
    @Test
    @Order(11)
    void v12LeavesAnEarlierWorkRowWithoutABodyRefAndItsBodyInBodies() {
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("11").load().migrate();
        long seq = pool.query("insert into work(id, flow, step, partition_key, attempts) values ('m-1', 'shop/orders', 0, 'K', 2) returning seq")
                .execute().await().iterator().next().getLong(0);
        pool.query("insert into bodies(message_id, step, part, bytes) values ('m-1', 0, 'body', '\\x0102ff'::bytea)").execute().await();

        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());

        assertEquals(4, r.migrationsExecuted(), "V12, V13, V14 and V15 on top of V11");
        assertEquals("15", r.schemaVersion());
        Row row = pool.preparedQuery("select id, flow, step, partition_key, attempts, body_node, body_segment, body_position, body_length from work where seq = $1")
                .execute(io.vertx.sqlclient.Tuple.of(seq)).await().iterator().next();
        assertEquals(List.of("m-1", "shop/orders", "0", "K", "2"), List.of(row.getString(0), row.getString(1), String.valueOf(row.getInteger(2)),
                row.getString(3), String.valueOf(row.getInteger(4))), "the row keeps what it had");
        assertEquals(java.util.Arrays.asList(null, null, null, null), java.util.Arrays.asList(row.getValue(5), row.getValue(6), row.getValue(7), row.getValue(8)),
                "a pre-V12 row has no body ref");
        assertEquals("0102ff", pool.query("select encode(bytes, 'hex') from bodies where message_id = 'm-1'").execute().await().iterator().next().getString(0),
                "its body stays in bodies");
    }

    /**
     * V13 (CORE-4c3, CORE-3 "Review r2 additions"): {@code work.lost_at timestamptz}, nullable (null = not lost: an item an
     * accepted {@code FLOTTILA_JOURNAL_ACCEPT_LOSS} held gets the time), and {@code journal_losses(node text, journal_id uuid,
     * digest text, accepted_at timestamptz, pk(node, journal_id, digest))} — each accepted loss once; no bytea. On a database at
     * V12 holding a work row the row keeps what it had and reads a null {@code lost_at}.
     */
    @Test
    @Order(12)
    void v13AddsTheWorkRowsLostAtAndTheAcceptedLosses() {
        org.flywaydb.core.Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).locations(Migrations.LOCATION).target("12").load().migrate();
        long seq = pool.query("insert into work(id, flow, step, partition_key, body_node, body_segment, body_position, body_length) "
                + "values ('m-1', 'shop/orders', 1, 'K', 'node-1', 3, 64, 400) returning seq").execute().await().iterator().next().getLong(0);

        Migrations.Result r = Migrations.migrate(url, pg.getUsername(), pg.getPassword());

        assertEquals(3, r.migrationsExecuted(), "V13, V14 and V15 on top of V12");
        assertEquals("15", r.schemaVersion());
        assertEquals("timestamp with time zone", columnsOf("work").get("lost_at"), "V13: work.lost_at, nullable: " + columnsOf("work"));
        Row row = pool.preparedQuery("select id, body_node, body_segment, body_position, body_length, lost_at from work where seq = $1")
                .execute(io.vertx.sqlclient.Tuple.of(seq)).await().iterator().next();
        assertEquals(java.util.Arrays.asList("m-1", "node-1", 3L, 64L, 400L, null),
                java.util.Arrays.asList(row.getString(0), row.getString(1), row.getLong(2), row.getLong(3), row.getLong(4), row.getValue(5)),
                "an item of V12 keeps its ref and is not lost");
        assertEquals(java.util.Map.of("node", "text not null", "journal_id", "uuid not null", "digest", "text not null",
                "accepted_at", "timestamp with time zone not null"), columnsOf("journal_losses"), "V13: journal_losses, one row per accepted loss");
        assertEquals("PRIMARY KEY (node, journal_id, digest)", primaryKey("journal_losses"));
        assertEquals("23505", sqlState(() -> pool.query("insert into journal_losses(node, journal_id, digest, accepted_at) values "
                + "('node-1', '00000000-0000-0000-0000-000000000001', 'd1', now()), ('node-1', '00000000-0000-0000-0000-000000000001', 'd1', now())")
                .execute().await()), "a loss is recorded once");
        long bytea = pool.query("select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'journal_losses' "
                + "and data_type = 'bytea'").execute().await().iterator().next().getLong(0);
        assertEquals(0, bytea, "no bytes in journal_losses");
    }

    /** {@code table}'s columns in order: name → {@code format_type} and {@code not null} when the column is. */
    private java.util.Map<String, String> columnsOf(String table) {
        java.util.Map<String, String> columns = new java.util.LinkedHashMap<>();
        for (Row c : pool.preparedQuery("select attname::text, format_type(atttypid, atttypmod), attnotnull from pg_attribute "
                + "where attrelid = $1::regclass and attnum > 0 and not attisdropped order by attnum").execute(io.vertx.sqlclient.Tuple.of(table)).await()) {
            columns.put(c.getString(0), c.getString(1) + (c.getBoolean(2) ? " not null" : ""));
        }
        return columns;
    }

    private String primaryKey(String table) {
        return pool.preparedQuery("select pg_get_constraintdef(oid) from pg_constraint where conrelid = $1::regclass and contype = 'p'")
                .execute(io.vertx.sqlclient.Tuple.of(table)).await().iterator().next().getString(0);
    }

    private List<String> indexDefs(String table) {
        List<String> defs = new java.util.ArrayList<>();
        for (Row row : pool.preparedQuery("select indexdef from pg_indexes where schemaname = 'public' and tablename = $1")
                .execute(io.vertx.sqlclient.Tuple.of(table)).await()) {
            defs.add(row.getString(0));
        }
        return defs;
    }

    /** The SQLSTATE of the Postgres error {@code call} fails with. */
    private static String sqlState(org.junit.jupiter.api.function.Executable call) {
        io.vertx.pgclient.PgException e = org.junit.jupiter.api.Assertions.assertThrows(io.vertx.pgclient.PgException.class, call);
        return e.getSqlState();
    }
}
