package io.flottila.bench;

import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-15b, the implementer's cases on {@link DbSampler.PgSource} against a real server with fsync off and a database without a
 * work table or the pg_stat_statements extension: the source reads on a session of its own (its
 * application_name; its statement_timeout ends a read held by a lock), says why the WAL syncs, the work table and the statements are unknown, and a read
 * once its connection is gone is a reading of nulls naming the failure.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class DbSamplerPgIT {

    /** Testcontainers' default fsync off; pg_stat_statements preloaded, created in no database but the dbid case's. */
    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name()).withCommand("postgres", "-c", "fsync=off", "-c",
            "shared_preload_libraries=pg_stat_statements");
    static Vertx vertx;

    @BeforeAll static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll static void stop() {
        vertx.close().await();
        pg.stop();
    }

    static PgConnectOptions options() {
        return options(pg.getDatabaseName());
    }

    static PgConnectOptions options(String db) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(db).setUser(pg.getUsername())
                .setPassword(pg.getPassword());
    }

    static Connection connect(String db) throws java.sql.SQLException {
        return DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db, pg.getUsername(),
                pg.getPassword());
    }

    @Test
    void theStatementsAreTheRunDatabasesOnlyNotAnotherDatabasesOnTheSameServer() throws Exception {
        try (Connection c = pg.createConnection(""); Statement s = c.createStatement()) {
            s.execute("create database dbid_run_bench");
            s.execute("create database dbid_other_bench");
        }
        try (Connection c = connect("dbid_run_bench"); Statement s = c.createStatement()) {
            s.execute("create extension pg_stat_statements");
        }
        DbSampler sampler;
        try (DbSampler.PgSource source = DbSampler.PgSource.open(vertx, options("dbid_run_bench"))) {
            sampler = new DbSampler(source);
            sampler.sample();
            try (Connection run = connect("dbid_run_bench"); Connection other = connect("dbid_other_bench");
                 Statement inRun = run.createStatement(); Statement inOther = other.createStatement()) {
                for (int i = 0; i < 3; i++) {
                    inRun.execute("select 1 as in_the_run_database");
                    inOther.execute("select 1 as in_another_database");
                }
            }
            sampler.sample();
        }

        JsonObject db = sampler.series();
        assertNotNull(db.getJsonArray("statements"), db.encode());
        List<String> queries = db.getJsonArray("statements").stream().map(q -> ((JsonObject) q).getString("query")).toList();
        assertTrue(queries.stream().anyMatch(q -> q.contains("in_the_run_database")), "the run database's statement: " + queries);
        assertTrue(queries.stream().noneMatch(q -> q.contains("in_another_database")), "not another database's (dbid): " + queries);
    }

    @Test
    void aReadingOnItsOwnSessionSaysWhyEachUnknownFieldIsUnknownAndAReadAfterTheConnectionWentIsAFailure() throws Exception {
        DbSampler.PgSource source = DbSampler.PgSource.open(vertx, options());
        JsonObject r = source.get();

        assertNotNull(r.getValue("xactCommit"), r.encode());
        assertNotNull(r.getValue("walBytes"), r.encode());
        JsonObject notes = r.getJsonObject("notes");
        assertNull(r.getValue("walSync"), "fsync off: no sync is counted: " + r.encode());
        assertTrue(notes.getString("walSync").contains("fsync off"), r.encode());
        assertNull(r.getValue("walSyncTimeMs"), r.encode());
        for (String work : List.of("workDeadTup", "workLiveTup", "workHotUpd", "workUpd", "workLastAutovacuum")) {
            assertNull(r.getValue(work), r.encode());
            assertTrue(notes.getString(work).contains("no row for work in schema public"), r.encode());
        }
        assertNull(r.getValue("statements"), r.encode());
        assertTrue(notes.getString("statements").contains("pg_stat_statements is not readable in " + pg.getDatabaseName()), r.encode());

        try (Connection c = pg.createConnection(""); Statement s = c.createStatement();
             ResultSet sessions = s.executeQuery("select count(*) from pg_stat_activity where application_name = '"
                     + DbSampler.PgSource.APPLICATION_NAME + "'")) {
            assertTrue(sessions.next());
            assertEquals(1, sessions.getInt(1), "the sampler's one session of its own");
        }
        try (Connection c = pg.createConnection(""); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("lock table pg_database in access exclusive mode");   // pg_stat_database reads it: the sampler's read waits
            JsonObject held = source.get();
            c.rollback();
            assertNull(held.getValue("xactCommit"), held.encode());
            assertTrue(held.getJsonObject("notes").getString("xactCommit").contains("statement timeout"),
                    "the session's own statement_timeout ended the wait: " + held.encode());
        }
        assertNotNull(source.get().getValue("xactCommit"), "the session reads again once the lock is gone");

        source.close();
        JsonObject gone = source.get();
        for (String field : DbSampler.FIELDS) {
            assertTrue(gone.containsKey(field) && gone.getValue(field) == null, field + ": " + gone.encode());
            assertTrue(gone.getJsonObject("notes").getString(field).startsWith("the read failed: "), gone.encode());
        }
    }
}
