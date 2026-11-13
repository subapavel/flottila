package io.flottila.runtime.node;

import io.flottila.core.node.Membership;
import io.flottila.runtime.journal.PgWire;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1 T14 (packet Q1 as accepted: epochOf from the renew, a lower bound only; an owner the renew does not answer has no
 * bound): the renew — still one statement — also answers the live peers' epochs, and {@code epochOf(node)} is the epoch the
 * last answered renew found for that node. A node it did not answer (never joined, or before the first renew) bounds no
 * epoch: its {@code epochOf} is below 1, the first epoch a join gives. Statements counted at the client by a {@link PgWire}
 * relay, as {@link PgMembershipPeersIT} counts them.
 *
 * <p>The API assumed (CORE-5b1, test-author): {@code long PgMembership.epochOf(String node)} — what the holder's
 * {@code PeerServer.listen(..., ToLongFunction<String> epochOf)} is given in the node.
 */
@Timeout(120)
class PgMembershipEpochsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final Duration L = Duration.ofSeconds(30);
    static Vertx vertx;
    PgConnection admin;
    final List<PgConnection> connections = new ArrayList<>();
    PgWire wire;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() { vertx.close().await(); pg.stop(); }

    static PgConnectOptions options() {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
    }

    @BeforeEach
    void connect() throws Exception {
        admin = PgConnection.connect(vertx, options()).await();
        admin.query("truncate nodes").execute().await();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
    }

    @AfterEach
    void disconnect() throws Exception {
        for (PgConnection c : connections) c.close().await();
        admin.close().await();
        wire.close();
    }

    PgMembership member(String address, boolean throughTheWire) {
        PgConnectOptions o = options();
        if (throughTheWire) o.setHost("127.0.0.1").setPort(wire.port());
        PgConnection c = PgConnection.connect(vertx, o).await();
        connections.add(c);
        return new PgMembership(c, address);
    }

    static <T> T await(io.vertx.core.Future<T> f) throws Exception {
        return f.await(10, SECONDS);
    }

    @Test
    void theRenewAnswersTheLivePeersEpochsInOneStatementAndAnUnansweredNodeHasNoBound() throws Exception {
        PgMembership a = member("10.0.0.1:8083", true);
        long epoch = await(a.join("n-a", L));
        await(member("10.0.0.2:8083", false).join("n-b", L));
        await(member("10.0.0.2:8083", false).join("n-b", L));   // node-b restarted: its epoch is 2
        await(member("10.0.0.3:8083", false).join("n-c", L));
        assertTrue(a.epochOf("n-b") < 1, "before a renew answered, n-b has no bound: " + a.epochOf("n-b"));

        await(a.renew("n-a", epoch, L));   // the renew's statement is prepared once; the one counted is a steady-state renew
        int mark = wire.mark();
        Membership.Renewal r = await(a.renew("n-a", epoch, L));
        long statements = wire.since(mark).stream().filter(s -> s.type() == 'Q' || s.type() == 'E').count();
        assertEquals(1, statements, "the renew is one statement, its peers' epochs included");
        assertTrue(r.renewed());

        assertEquals(2, a.epochOf("n-b"), "n-b's epoch as the renew found it: its second incarnation");
        assertEquals(1, a.epochOf("n-c"), "n-c's epoch");
        assertTrue(a.epochOf("n-never") < 1, "a node the renew did not answer has no bound: " + a.epochOf("n-never"));
    }
}
