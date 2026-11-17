package dev.rail8.testkit;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A database of its own on the one Postgres container of this JVM. The first {@link #start()} in a JVM starts a container of
 * {@link PostgresImage#name()}; every later test class reuses it, so a test run pays for one container start per JVM, not one
 * per class. The container is never stopped by this class: Testcontainers' resource reaper removes it when the JVM ends.
 *
 * <p>Each handle names a database no other handle of the JVM uses. {@link #start()} creates it empty. {@link #stop()} drops it
 * and every other database the class created on the server (a database per case, say), ending every session still connected
 * to them, so a class sees neither the tables nor the sessions of the class before it.
 * The accessors are named as {@link PostgreSQLContainer}'s, so a class swaps its own container for a handle by its declaration
 * alone.
 *
 * <p>What is shared, and so not for a class that changes it: everything of the server outside one database — roles, the
 * server's configuration and log, the container's process. A class that creates a role, alters the system, reads the log,
 * pauses, stops or execs into the container starts a container of its own. Classes of one JVM run one after another; two JVMs
 * (parallel test forks) each have a container of their own.
 *
 * <p>The JDBC driver is the caller's: the modules whose tests start Postgres bring it.
 *
 * @since 0.1
 */
public final class SharedPostgres {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static PostgreSQLContainer server;

    private final String name;

    private SharedPostgres(String name) {
        this.name = name;
    }

    /** A handle on a database of its own, created at {@link #start()}. */
    public static SharedPostgres database() {
        return new SharedPostgres("rail8_" + DATABASES.incrementAndGet());
    }

    private static synchronized PostgreSQLContainer server() {
        if (server == null) {
            PostgreSQLContainer started = new PostgreSQLContainer(PostgresImage.name());
            started.start();
            server = started;
        }
        return server;
    }

    private static Connection admin() throws SQLException {
        PostgreSQLContainer s = server();
        return DriverManager.getConnection(s.getJdbcUrl(), s.getUsername(), s.getPassword());
    }

    /** Starts the JVM's container if no class has yet, and creates this handle's database, empty. */
    public void start() {
        try (Connection c = admin(); Statement statement = c.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException("creating the database " + name + " on the shared Postgres container failed", e);
        }
    }

    /**
     * Drops this handle's database and every other database on the server but its own ({@code postgres}, the templates and the
     * container's default), ending every session still connected to them. The container keeps running.
     */
    public void stop() {
        try (Connection c = admin(); Statement statement = c.createStatement()) {
            List<String> created = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("select datname from pg_database where not datistemplate"
                    + " and datname not in ('postgres', current_database())")) {
                while (rows.next()) created.add(rows.getString(1));
            }
            for (String database : created) {
                statement.execute("DROP DATABASE IF EXISTS \"" + database.replace("\"", "\"\"") + "\" WITH (FORCE)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("dropping the databases of " + name + " on the shared Postgres container failed", e);
        }
    }

    /** The host the container's ports are reachable on. */
    public String getHost() {
        return server().getHost();
    }

    /** The host port the container's {@code port} (5432) is mapped to. */
    public int getMappedPort(int port) {
        return server().getMappedPort(port);
    }

    /** The superuser's name; the role is the server's, shared by every database. */
    public String getUsername() {
        return server().getUsername();
    }

    /** The superuser's password. */
    public String getPassword() {
        return server().getPassword();
    }

    /** This handle's database. */
    public String getDatabaseName() {
        return name;
    }

    /** The JDBC URL of this handle's database. */
    public String getJdbcUrl() {
        return "jdbc:postgresql://" + getHost() + ":" + getMappedPort(5432) + "/" + name;
    }

    /** A JDBC connection to this handle's database, as {@link PostgreSQLContainer#createConnection(String)} with no query. */
    public Connection createConnection() throws SQLException {
        return DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
    }
}
