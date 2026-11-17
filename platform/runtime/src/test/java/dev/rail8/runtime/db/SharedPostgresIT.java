package dev.rail8.runtime.db;

import dev.rail8.testkit.SharedPostgres;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SharedPostgres}: handles of one JVM share one server, each with a database of its own; {@code stop()} drops the
 * handle's database and the others its class created, ends their sessions, and leaves the server running for the next class.
 */
class SharedPostgresIT {

    static List<String> databases(SharedPostgres pg) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432)
                + "/postgres", pg.getUsername(), pg.getPassword());
             Statement s = c.createStatement();
             ResultSet rows = s.executeQuery("select datname from pg_database where not datistemplate order by 1")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    @Test
    void twoHandlesShareTheServerButNotADatabase() throws SQLException {
        SharedPostgres first = SharedPostgres.database();
        SharedPostgres second = SharedPostgres.database();
        first.start();
        second.start();
        try {
            assertNotEquals(first.getDatabaseName(), second.getDatabaseName(), "a database each");
            assertEquals(first.getMappedPort(5432), second.getMappedPort(5432), "one server");
            try (Connection c = first.createConnection(); Statement s = c.createStatement()) {
                s.execute("create table only_in_first (id int)");
            }
            try (Connection c = second.createConnection(); Statement s = c.createStatement();
                 ResultSet rows = s.executeQuery("select count(*) from pg_tables where tablename = 'only_in_first'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1), "the second database does not see the first one's table");
            }
            assertTrue(first.getJdbcUrl().endsWith("/" + first.getDatabaseName()), first.getJdbcUrl());
        } finally {
            first.stop();
        }
        assertFalse(databases(second).contains(second.getDatabaseName()), "one stop drops every database the class created");
    }

    @Test
    void stopDropsTheClassesDatabasesAndEndsTheirSessionsAndTheServerStaysUp() throws SQLException {
        SharedPostgres pg = SharedPostgres.database();
        pg.start();
        try (Connection c = pg.createConnection(); Statement s = c.createStatement()) {
            s.execute("create database per_case_1");
        }
        Connection open = pg.createConnection();
        assertTrue(databases(pg).containsAll(List.of(pg.getDatabaseName(), "per_case_1")), "fixture: " + databases(pg));

        pg.stop();

        List<String> left = databases(pg);
        assertFalse(left.contains(pg.getDatabaseName()), "the handle's database is dropped: " + left);
        assertFalse(left.contains("per_case_1"), "a database the class created is dropped too: " + left);
        assertTrue(left.contains("postgres"), "the server's own databases stay: " + left);
        assertFalse(open.isValid(5), "the session still connected to the dropped database was ended");
        open.close();

        SharedPostgres next = SharedPostgres.database();
        next.start();
        try (Connection c = next.createConnection()) {
            assertTrue(c.isValid(5), "the next class's database is served by the same running server");
        } finally {
            next.stop();
        }
    }
}
