package io.flottila.runtime.queue;

import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.Workers;
import io.flottila.core.node.NodeLease;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.runtime.journal.PgWire;
import io.flottila.runtime.node.PgMembership;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.FakeTimer;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
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
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16b B6 (D-121 (b), D-102 (3)): idle silence. A node with two started flows and nothing to do sends, over five minutes of a
 * fake clock, only its membership renewals and one reconciliation claim per flow per 30 s — no NOTIFY, no other statement. Counted
 * at the client by three {@link PgWire} relays: the queue's pool (claims, and the signals' sends), the lease's dedicated
 * connection, the signals' dedicated listening connection. One fake clock and timer drive the lease, the workers and the signals;
 * the clock moves a second at a time, and after each step every renew and claim it caused is answered (a sentinel round trip on
 * the lease's connection, as {@code PgWorkQueueStatementsIT} does; the claims counted issued and answered at the queue boundary).
 *
 * <p>Expected counts, from the spec (lease renew period 5 s; reconciliation every 30 s, the first 30 s +
 * {@code floorMod(flow.hashCode(), 30 000)} ms after the start): over (T0, T0 + 300 s] the lease renews 300 / 5 = 60 times;
 * {@code orders} (offset 9 669 ms) reconciles at 39.669 s + 30 k ≤ 300 s, k = 0..8: 9 claims; {@code invoices} (offset 25 638 ms)
 * at 55.638 s + 30 k ≤ 300 s, k = 0..8: 9 claims. Whether a late-fired reconciliation reschedules from its due instant or from
 * the step it fired in, the counts are the same.
 *
 * <p>The API assumed (packet P2-16b; shapes chosen by the test-author, as in {@code PgWorkSignalsIT}): {@code new PgWorkSignals(Vertx,
 * PgConnectOptions listen, Pool pool, String node, Workers, Timer)}, {@code start()}, {@code close()}; {@code FlowBuilder.signals};
 * {@code WorkerPolicy.DEFAULT} reconciles every 30 s (the 3-argument {@code worker} uses it).
 */
@Timeout(240)
class IdleNodeStatementsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final Instant T0 = Instant.parse("2026-11-10T08:00:00Z");
    static final Duration L = Duration.ofSeconds(30);
    static final Duration R = Duration.ofSeconds(5);
    static final String SENTINEL = "select 1";
    static final String NODE = "n-idle";
    static Vertx vertx;

    PgWire poolWire;
    PgWire leaseWire;
    PgWire listenWire;
    Pool pool;
    PgConnection leaseConnection;
    final FakeClock clock = FakeClock.at(T0);
    final FakeTimer timer = new FakeTimer(clock);
    final LongSupplier nanos = () -> Duration.between(T0, clock.instant()).toNanos();
    final AtomicInteger halts = new AtomicInteger();
    NodeLease lease;
    PgWorkSignals signals;
    final Workers workers = new Workers();
    final Map<String, Counting> queues = new HashMap<>();
    final List<FlowWorker> started = new ArrayList<>();

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

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("not within 20 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** The node's queue counting the claims issued and answered (the store boundary); everything else straight through. */
    static final class Counting implements WorkQueue {
        final WorkQueue inner;
        final AtomicInteger issued = new AtomicInteger();
        final AtomicInteger answered = new AtomicInteger();

        Counting(WorkQueue inner) { this.inner = inner; }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return inner.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
            issued.incrementAndGet();
            return inner.claim(workerId, flow, max, lease).onComplete(done -> answered.incrementAndGet());
        }
        @Override public Future<Boolean> complete(long seq, int attempts) { return inner.complete(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            return inner.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return inner.renew(seq, attempts, lease); }
        @Override public Future<Boolean> defer(long seq, int attempts, Duration delay) { return inner.defer(seq, attempts, delay); }
        @Override public Future<Boolean> restart(String messageId, String flow, String partitionKey, int attempt) { return inner.restart(messageId, flow, partitionKey, attempt); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return inner.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return inner.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return inner.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return inner.hasItems(flow); }

        boolean settled() { return issued.get() == answered.get(); }
    }

    static final StepFactory PASS = new StepFactory() {
        @Override public String type() { return "pass"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Stage create(StepConfig step, StepResources resources) { return Stage.of(e -> e); }
    };

    static FlowDefinition flow(String id) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", id);
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 4));
        doc.put("steps", List.of(Map.of("type", "pass", "name", "pass")));
        return FlowDefinition.from(doc);
    }

    @BeforeEach
    void open() throws Exception {
        poolWire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        leaseWire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        listenWire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        pool = PgBuilder.pool().connectingTo(through(poolWire)).with(new PoolOptions().setMaxSize(2)).using(vertx).build();
        pool.query("truncate work, nodes").execute().await();
        leaseConnection = PgConnection.connect(vertx, through(leaseWire)).await();
        lease = new NodeLease(NODE, new PgMembership(leaseConnection), L, R, nanos, timer, halts::incrementAndGet);
        lease.start().await(10, SECONDS);
        signals = new PgWorkSignals(vertx, through(listenWire), pool, NODE, workers, timer);
        signals.start().await(20, SECONDS);
        FakeRuntime rt = FakeRuntime.create();
        Runtime runtime = new Runtime(clock, timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of());
        for (String f : List.of("orders", "invoices")) {
            Counting q = new Counting(new PgWorkQueue(pool, NODE));
            FlowWorker w = new FlowBuilder(runtime, List.of(PASS), List.of(), Map.of()).recording(new InMemoryMessageStore(), Meter.system())
                    .queue(q).dlq(new InMemoryDlq()).workers(workers).signals(signals).worker(flow(f), Path.of(""), NODE);
            queues.put(f, q);
            w.start();
            started.add(w);
        }
        until(() -> queues.values().stream().allMatch(q -> q.issued.get() == 1 && q.settled()), () -> "the start claims answered: " + issued());
    }

    @AfterEach
    void close() throws Exception {
        started.forEach(FlowWorker::stop);
        if (signals != null) signals.close().await(20, SECONDS);
        lease.close();
        leaseConnection.close().await();
        pool.close().await();
        poolWire.close();
        leaseWire.close();
        listenWire.close();
    }

    Map<String, Integer> issued() {
        Map<String, Integer> m = new HashMap<>();
        queues.forEach((f, q) -> m.put(f, q.issued.get()));
        return m;
    }

    /** The statements {@code wire} relayed after {@code mark}, trimmed, lower case, on one line. */
    static List<String> statements(PgWire wire, int mark) {
        return wire.since(mark).stream().filter(s -> s.type() == 'Q' || s.type() == 'E')
                .map(s -> s.sql().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)).toList();
    }

    @Test
    void anIdleNodeSendsOnlyItsRenewalsAndOneReconciliationClaimPerFlowPer30Seconds() throws Exception {
        assertTrue(statements(listenWire, 0).stream().anyMatch(s -> s.startsWith("listen")), "fixture: the signals listen on their own connection: "
                + statements(listenWire, 0));
        int poolMark = poolWire.mark();
        int leaseMark = leaseWire.mark();
        int listenMark = listenWire.mark();

        for (int s = 1; s <= 300; s++) {
            clock.advance(Duration.ofSeconds(1));
            leaseConnection.query(SENTINEL).execute().await(10, SECONDS);   // a renew that came due is answered
            until(() -> queues.values().stream().allMatch(Counting::settled), () -> "every claim answered; issued " + issued());
        }
        leaseConnection.query(SENTINEL).execute().await(10, SECONDS);
        leaseConnection.query(SENTINEL).execute().await(10, SECONDS);

        assertEquals(Map.of("orders", 10, "invoices", 10), issued(), "the start claim and nine reconciliations per flow in 5 minutes");
        List<String> onPool = statements(poolWire, poolMark);
        assertEquals(18, onPool.size(), "the pool carried the 18 reconciliation claims and nothing else: " + onPool);
        assertTrue(onPool.stream().allMatch(s -> s.contains("for update skip locked")), "each one a claim: " + onPool);
        assertTrue(onPool.stream().noneMatch(s -> s.contains("pg_notify")), "no NOTIFY: " + onPool);
        List<String> renews = statements(leaseWire, leaseMark).stream().filter(s -> !s.equals(SENTINEL)).toList();
        assertEquals(60, renews.size(), "one renewal per 5 s period: " + renews.size());
        assertEquals(1, Set.copyOf(renews).size(), "each the same single statement: " + Set.copyOf(renews));
        assertEquals(List.of(), statements(listenWire, listenMark), "the listening connection sends nothing while idle");
        assertEquals(0, halts.get());
    }
}
