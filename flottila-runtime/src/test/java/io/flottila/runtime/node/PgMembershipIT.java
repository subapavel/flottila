package io.flottila.runtime.node;

import io.flottila.core.node.Membership;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * P2-15a: {@code PgMembership} on V14's {@code nodes} table passes the {@link MembershipContract}, each instance on a dedicated,
 * non-pooled connection of its own (two connections race in the contract's last case). The leases are measured by the database
 * clock; {@link #pastTheLease()} moves every {@code lease_until} back by an hour instead of waiting.
 *
 * <p>The API assumed (P2-15a, test-author): {@code new PgMembership(SqlConnection)} — the node hands it its dedicated connection.
 */
class PgMembershipIT extends MembershipContract {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    PgConnection first;
    PgConnection other;
    PgConnection admin;
    PgMembership one;
    PgMembership two;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());   // V14 brings nodes
    }

    @AfterAll
    static void stop() { vertx.close().await(); pg.stop(); }

    static PgConnectOptions options() {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    @BeforeEach
    void connect() {
        admin = PgConnection.connect(vertx, options()).await();
        admin.query("truncate nodes").execute().await();
        first = PgConnection.connect(vertx, options()).await();
        other = PgConnection.connect(vertx, options()).await();
        one = new PgMembership(first);
        two = new PgMembership(other);
    }

    @AfterEach
    void disconnect() {
        first.close().await();
        other.close().await();
        admin.close().await();
    }

    @Override Membership membership() { return one; }

    @Override Membership second() { return two; }

    @Override void pastTheLease() {
        admin.query("update nodes set lease_until = lease_until - interval '1 hour' where lease_until is not null").execute().await();
    }
}
