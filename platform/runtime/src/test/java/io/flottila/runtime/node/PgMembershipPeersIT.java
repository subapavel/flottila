package io.flottila.runtime.node;

import io.flottila.core.node.Membership;
import io.flottila.runtime.journal.PgWire;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
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
import java.util.Locale;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5a acceptance A3 (packet S1): V18's {@code nodes.peer_address} is written at join, and the renew — still one statement
 * — also answers the live peers' addresses, so a node learns where to READ from with the renew it sends anyway. Statements are
 * counted at the client by a {@link PgWire} relay in front of the renewing membership's connection, as
 * {@code IdleNodeStatementsIT} counts the renewals.
 *
 * <p>The API assumed (CORE-5a, test-author): {@code new PgMembership(SqlConnection, String peerAddress)} — its joins write
 * {@code peerAddress} ({@code host:port}, FLOTTILA_PEER_HOST and the port the peer link listens on) into the node's row, a
 * rejoin overwrites it; {@code new PgMembership(SqlConnection)} keeps joining with none (null); core
 * {@code Membership.Renewal(boolean renewed, int live, List<String> expired, Map<String, String> peers)} — {@code peers}: node
 * id → address of every other member whose lease runs now and that has an address (an expired, a released or an
 * address-less member is none of them); the three-argument constructor answers no peers.
 */
@Timeout(120)
class PgMembershipPeersIT {

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

    PgMembership member(String address) {
        PgConnection c = PgConnection.connect(vertx, options()).await();
        connections.add(c);
        return new PgMembership(c, address);
    }

    PgMembership throughTheWire(String address) {
        PgConnection c = PgConnection.connect(vertx, options().setHost("127.0.0.1").setPort(wire.port())).await();
        connections.add(c);
        return new PgMembership(c, address);
    }

    String addressOf(String node) {
        Row r = admin.preparedQuery("select peer_address from nodes where node = $1").execute(Tuple.of(node)).await().iterator().next();
        return r.getString(0);
    }

    static <T> T await(io.vertx.core.Future<T> f) throws Exception {
        return f.await(10, SECONDS);
    }

    @Test
    void aJoinWritesThePeerAddressAndARejoinOverwritesIt() throws Exception {
        await(member("10.0.0.1:8083").join("n-a", L));
        assertEquals("10.0.0.1:8083", addressOf("n-a"), "V18: the join writes nodes.peer_address");

        await(member("10.0.0.9:9083").join("n-a", L));
        assertEquals("10.0.0.9:9083", addressOf("n-a"), "a new incarnation elsewhere: its rejoin overwrites the address");

        PgConnection plain = PgConnection.connect(vertx, options()).await();
        connections.add(plain);
        await(new PgMembership(plain).join("n-none", L));
        assertEquals(null, addressOf("n-none"), "a membership without an address joins with none");
    }

    @Test
    void theRenewAnswersTheLivePeersAddressesInOneStatement() throws Exception {
        PgMembership a = throughTheWire("10.0.0.1:8083");
        long epoch = await(a.join("n-a", L));
        await(member("10.0.0.2:8083").join("n-b", L));
        await(member("10.0.0.3:8083").join("n-expired", L));
        await(member("10.0.0.4:8083").join("n-released", L));
        PgConnection plain = PgConnection.connect(vertx, options()).await();
        connections.add(plain);
        await(new PgMembership(plain).join("n-none", L));
        admin.query("update nodes set lease_until = now() - interval '1 hour' where node = 'n-expired'").execute().await();
        admin.query("update nodes set lease_until = null where node = 'n-released'").execute().await();
        await(a.renew("n-a", epoch, L));   // the renew's statement is prepared once; the one counted is a steady-state renew

        int mark = wire.mark();
        Membership.Renewal r = await(a.renew("n-a", epoch, L));

        List<String> sent = wire.since(mark).stream().filter(s -> s.type() == 'Q' || s.type() == 'E')
                .map(s -> s.sql().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)).toList();
        assertEquals(1, sent.size(), "the renew is one statement, its peers' addresses included: " + sent);
        assertTrue(r.renewed());
        assertEquals(3, r.live(), "n-a, n-b and the address-less n-none are live");
        assertEquals(List.of("n-expired"), r.expired());
        assertEquals(Map.of("n-b", "10.0.0.2:8083"), r.peers(),
                "the live peers with an address: not the renewing node, not an expired, a released or an address-less one");
    }
}
