package io.flottila.runtime.journal;

import io.flottila.runtime.db.Migrations;
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
 * A database of its own per case on a class's container (FIX-5's pattern, CORE-4c1 ITs): created by the admin JDBC
 * connection, its JDBC URL (for Flyway) is the container's with the database path segment swapped (Testcontainers 2.0.5's
 * URL ends with {@code ?loggerLevel=OFF}), its pool is the case's observer — never a connection of the node's.
 */
record CaseDatabase(PostgreSQLContainer pg, String name, String url, Pool pool) implements AutoCloseable {

    private static final AtomicInteger CASES = new AtomicInteger();

    static CaseDatabase create(PostgreSQLContainer pg, Vertx vertx, String prefix) throws SQLException {
        String name = prefix + CASES.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + name + "\"");
        }
        String base = pg.getJdbcUrl();
        int query = base.indexOf('?');
        String path = query < 0 ? base : base.substring(0, query);
        String suffix = "/" + pg.getDatabaseName();
        if (!path.endsWith(suffix)) throw new AssertionError("the container URL ends with its database: " + base);
        String url = path.substring(0, path.length() - suffix.length()) + "/" + name + (query < 0 ? "" : base.substring(query));
        Pool pool = PgBuilder.pool().connectingTo(options(pg, name)).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        return new CaseDatabase(pg, name, url, pool);
    }

    static PgConnectOptions options(PostgreSQLContainer pg, String database) {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(database)
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    PgConnectOptions options() { return options(pg, name); }

    /** Migrates this database to the latest packaged version (V12 and on). */
    CaseDatabase migrated() {
        Migrations.migrate(url, pg.getUsername(), pg.getPassword());
        return this;
    }

    long count(String sql) {
        return pool.query(sql).execute().await().iterator().next().getLong(0);
    }

    @Override public void close() {
        pool.close().await();
    }
}
