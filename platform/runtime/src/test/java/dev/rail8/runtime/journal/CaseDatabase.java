package dev.rail8.runtime.journal;

import dev.rail8.runtime.db.Migrations;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A database of its own per case on a class's Postgres server — the JVM's shared one or a container of the class's own:
 * created by the admin JDBC connection, its JDBC URL (for Flyway) names the new database on the same host and port, its pool
 * is the case's observer — never a connection of the node's.
 */
record CaseDatabase(String host, int port, String user, String password, String name, String url, Pool pool)
        implements AutoCloseable {

    private static final AtomicInteger CASES = new AtomicInteger();

    static CaseDatabase create(SharedPostgres pg, Vertx vertx, String prefix) throws SQLException {
        return create(pg.getJdbcUrl(), pg.getHost(), pg.getMappedPort(5432), pg.getUsername(), pg.getPassword(), vertx, prefix);
    }

    static CaseDatabase create(PostgreSQLContainer pg, Vertx vertx, String prefix) throws SQLException {
        return create(pg.getJdbcUrl(), pg.getHost(), pg.getMappedPort(5432), pg.getUsername(), pg.getPassword(), vertx, prefix);
    }

    private static CaseDatabase create(String adminUrl, String host, int port, String user, String password, Vertx vertx,
                                       String prefix) throws SQLException {
        String name = prefix + CASES.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + name + "\"");
        }
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + name;
        Pool pool = PgBuilder.pool().connectingTo(options(host, port, user, password, name))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        return new CaseDatabase(host, port, user, password, name, url, pool);
    }

    private static PgConnectOptions options(String host, int port, String user, String password, String database) {
        return new PgConnectOptions().setHost(host).setPort(port).setDatabase(database).setUser(user).setPassword(password);
    }

    PgConnectOptions options() { return options(host, port, user, password, name); }

    /** Migrates this database to the latest packaged version (V12 and on). */
    CaseDatabase migrated() {
        Migrations.migrate(url, user, password);
        return this;
    }

    long count(String sql) {
        return pool.query(sql).execute().await().iterator().next().getLong(0);
    }

    @Override public void close() {
        pool.close().await();
    }
}
