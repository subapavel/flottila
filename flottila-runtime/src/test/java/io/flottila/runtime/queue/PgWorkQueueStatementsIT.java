package io.flottila.runtime.queue;

import io.flottila.core.node.NodeLease;
import io.flottila.core.queue.WorkQueue;
import io.flottila.runtime.journal.PgWire;
import io.flottila.runtime.node.PgMembership;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-15a acceptance 5 (D-101 (1), architect decision 1): the node lease costs no statement per message and one renew per
 * period, counted at the client by {@link PgWire} relays — one in front of the queue's pool, one in front of the lease's
 * dedicated connection. The lease runs on a fake clock and the fake timer; the database measures its own time.
 *
 * <p>A statement is a simple Query or an Execute ({@link PgWire}). To count everything the lease sent, the case runs two
 * {@code select 1} sentinels one after the other on the lease's connection (commands on one connection run in order, so a
 * statement the lease sends on an earlier answer is sent before the second sentinel) and counts what came before them.
 *
 * <p>At most one renew is in flight (P2-15a reading (5)): a period that finds the last renew unanswered sends nothing. The
 * fake timer fires a due tick inside {@code clock.advance}, on the test thread, and the renew is handed to the lease's
 * connection there; so each case lets the renew answer before the next period by one sentinel round trip on that connection
 * ({@link #renewAnswered}) — commands on one connection are answered in order, so the sentinel's answer comes after the renew's.
 *
 * <p>The API assumed: {@code NodeLease} as in core's {@code NodeLeaseTest}; {@code new PgMembership(SqlConnection)}.
 */
class PgWorkQueueStatementsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Instant T0 = Instant.parse("2026-11-05T08:00:00Z");
    static final Duration L = Duration.ofSeconds(30);
    static final Duration R = Duration.ofSeconds(5);
    static final Duration ITEM_LEASE = Duration.ofSeconds(30);
    static final String SENTINEL = "select 1";
    static Vertx vertx;

    PgWire queueWire;
    PgWire leaseWire;
    Pool pool;
    PgConnection leaseConnection;
    final FakeClock clock = FakeClock.at(T0);
    final FakeTimer timer = new FakeTimer(clock);
    final LongSupplier nanos = () -> Duration.between(T0, clock.instant()).toNanos();
    final AtomicInteger halts = new AtomicInteger();
    NodeLease lease;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        io.flottila.runtime.db.Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() { vertx.close().await(); pg.stop(); }

    static PgConnectOptions through(PgWire wire) {
        return new PgConnectOptions().setHost("127.0.0.1").setPort(wire.port()).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
    }

    @BeforeEach
    void open() throws Exception {
        queueWire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        leaseWire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        pool = PgBuilder.pool().connectingTo(through(queueWire)).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        pool.query("truncate work, nodes").execute().await();
        leaseConnection = PgConnection.connect(vertx, through(leaseWire)).await();
        lease = new NodeLease("n1", new PgMembership(leaseConnection), L, R, nanos, timer, halts::incrementAndGet);
        lease.start().await(10, SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        lease.close();
        leaseConnection.close().await();
        pool.close().await();
        queueWire.close();
        leaseWire.close();
    }

    /** The statements {@code wire} relayed after {@code mark}, trimmed and on one line. */
    static List<String> statements(PgWire wire, int mark) {
        return wire.since(mark).stream().filter(s -> s.type() == 'Q' || s.type() == 'E').map(s -> s.sql().strip().replaceAll("\\s+", " ")).toList();
    }

    /** Advances the lease's clock by {@code step}; a renew that came due is answered when this returns (a sentinel round trip behind it). */
    void advanceAndLetRenewAnswer(Duration step) throws Exception {
        clock.advance(step);
        renewAnswered();
    }

    /** One sentinel round trip on the lease's connection: everything sent on it before has been answered. */
    void renewAnswered() throws Exception {
        leaseConnection.query(SENTINEL).execute().await(10, SECONDS);
    }

    /** What the lease sent after {@code mark}, once two sentinels in a row came back on its connection; the sentinels left out. */
    List<String> leaseStatements(int mark) throws Exception {
        leaseConnection.query(SENTINEL).execute().await(10, SECONDS);
        leaseConnection.query(SENTINEL).execute().await(10, SECONDS);
        return statements(leaseWire, mark).stream().filter(s -> !s.equalsIgnoreCase(SENTINEL)).toList();
    }

    @Test
    void theNodeLeaseAddsNoStatementPerMessage() throws Exception {
        int n = 20;
        PgWorkQueue queue = new PgWorkQueue(pool);
        int queueMark = queueWire.mark();
        int leaseMark = leaseWire.mark();
        for (int i = 0; i < n; i++) {
            queue.enqueue("m-" + i, "f", 1, null).await();
            advanceAndLetRenewAnswer(R.dividedBy(4));   // 20 messages over 25 s of the lease's clock: renews at +5, +10, +15, +20, +25 s
        }
        List<WorkQueue.Item> items = queue.claim("n1", "f", n, ITEM_LEASE).await();
        assertEquals(n, items.size(), "fixture: every message claimed in one round");
        for (WorkQueue.Item item : items) assertEquals(true, queue.complete(item.seq(), item.attempts()).await(), "fixture: completed");

        List<String> perMessage = statements(queueWire, queueMark);
        assertEquals(2 * n + 1, perMessage.size(), "an enqueue and a complete per message and one claim — nothing per message for the lease: " + perMessage);
        List<String> renews = leaseStatements(leaseMark);
        assertEquals(5, renews.size(), "the lease's connection: one renew per period over the 25 s, whatever the messages: " + renews);
        assertEquals(1, Set.copyOf(renews).size(), "one statement per renew (renew and detect together): " + renews);
        assertEquals(0, halts.get());
    }

    @Test
    void anIdleNodeIssuesOneRenewPerPeriod() throws Exception {
        int leaseMark = leaseWire.mark();
        for (int period = 1; period <= 12; period++) advanceAndLetRenewAnswer(R);   // each renew answered before the next is due
        clock.advance(R.dividedBy(2));   // half a period more: no renew is due
        List<String> renews = leaseStatements(leaseMark);
        assertEquals(12, renews.size(), "twelve periods, twelve renews: " + renews);
        assertEquals(1, renews.stream().collect(Collectors.toSet()).size(), "each a single statement, the same one: " + renews);
        assertEquals(List.of(), statements(queueWire, 0).stream().filter(s -> !s.startsWith("truncate")).toList(),
                "an idle node sends nothing else: nothing on the queue's pool");
        assertEquals(0, halts.get());
    }
}
