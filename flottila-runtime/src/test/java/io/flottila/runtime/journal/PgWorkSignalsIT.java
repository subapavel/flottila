package io.flottila.runtime.journal;

import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.runtime.queue.PgWorkSignals;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16b (D-121 (b), B1–B3): {@link PgWorkSignals} — work events between nodes through Postgres {@code LISTEN/NOTIFY} on channel
 * {@code flottila_work}. A node listens on a dedicated connection (relayed by {@link PgWire} {@code listenWire}, never the pool)
 * and sends on its pool (relayed by {@code poolWire}, one connection: a {@code select 1} through it after a timer step is a
 * barrier — anything the step sent was answered before it). Each node's {@link Workers} holds the started workers of flows
 * {@code orders} and {@code invoices} over in-memory queues that count claims: a wake is one claim, answered at once.
 *
 * <p>Witnesses: a raw observer session of the case's own, {@code LISTEN flottila_work}, records every payload in delivery order;
 * a NOTIFY of the case's own ({@link #notifyRaw}) is delivered to a listener after every notification committed before it, so a
 * positive payload sent after negative ones is the barrier that the negative ones were received and ignored. One database per
 * case (channels are per database). The fake clock drives the 50 ms window and any reconnect backoff; worker intervals are ten
 * minutes and never reached.
 *
 * <p>The API assumed (packet P2-16b B1–B3; the shape chosen by the test-author): {@code new PgWorkSignals(Vertx vertx,
 * PgConnectOptions listen, Pool pool, String node, Workers workers, Timer timer)} — {@code listen} where its dedicated connection
 * connects (and reconnects), {@code pool} what it sends {@code pg_notify} on; {@code Future<Void> start()} completes once its
 * {@code LISTEN} is acknowledged; {@code Future<Void> close()}; {@code announce(String targetNode, String flow)} of core's
 * {@code WorkSignals}, a null target being {@code *}. A WARNING names the payload's prefix (its text before the first blank).
 */
@Timeout(180)
class PgWorkSignalsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration QUIET = Duration.ofMinutes(10);
    static final Duration WINDOW = Duration.ofMillis(50);
    static final String ORDERS = "orders";
    static final String INVOICES = "invoices";
    static final String SENTINEL = "sentinel";
    static final long BOUND_SECONDS = 20;
    static Vertx vertx;

    CaseDatabase db;
    PgWire poolWire;
    PgWire listenWire;
    Pool pool;
    PgConnection observer;
    final List<String> observed = new CopyOnWriteArrayList<>();
    final FakeRuntime rt = FakeRuntime.create();
    final List<SignalNode> nodes = new ArrayList<>();
    final java.util.logging.Logger signalsLog = java.util.logging.Logger.getLogger(PgWorkSignals.class.getName());
    final NodeJournalIT.Logs logs = new NodeJournalIT.Logs();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() throws Exception {
        signalsLog.addHandler(logs);
        db = CaseDatabase.create(pg, vertx, "signals_");
        poolWire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        listenWire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        pool = PgBuilder.pool().connectingTo(through(poolWire)).with(new PoolOptions().setMaxSize(1)).using(vertx).build();
        observer = bounded(PgConnection.connect(vertx, db.options()), "the observer connects");
        observer.notificationHandler(n -> observed.add(n.getPayload()));
        bounded(observer.query("LISTEN flottila_work").execute(), "the observer listens");
    }

    @AfterEach
    void close() throws Exception {
        signalsLog.removeHandler(logs);
        for (SignalNode n : nodes) n.close();
        observer.close().await();   // a second close of the reconnect case's observer has nothing to do
        pool.close().await();
        poolWire.close();
        listenWire.close();
        db.close();
    }

    /** {@code f}'s result, waited for at most {@link #BOUND_SECONDS}; a timeout fails the case naming what it waited for. */
    static <T> T bounded(Future<T> f, String what) {
        try {
            return f.await(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("not within " + BOUND_SECONDS + " s: " + what, e);
        }
    }

    PgConnectOptions through(PgWire wire) {
        return db.options().setHost("127.0.0.1").setPort(wire.port());
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("not within 20 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** An in-memory queue counting the claims of its worker (the store boundary). */
    static final class Counting implements WorkQueue {
        final InMemoryWorkQueue inner;
        final AtomicInteger claims = new AtomicInteger();

        Counting(InMemoryWorkQueue inner) { this.inner = inner; }

        @Override public String toString() { return claims.get() + " claims"; }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return inner.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
            claims.incrementAndGet();
            return inner.claim(workerId, flow, max, lease);
        }
        @Override public Future<Boolean> complete(long seq, int attempts) { return inner.complete(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            return inner.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return inner.renew(seq, attempts, lease); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return inner.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return inner.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return inner.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return inner.hasItems(flow); }
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
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("steps", List.of(Map.of("type", "pass", "name", "pass")));
        return FlowDefinition.from(doc);
    }

    /** One node: its signals (started first, so its first LISTEN finds no worker to wake), then its two started workers. */
    final class SignalNode {
        final String id;
        final Workers workers = new Workers();
        final PgWorkSignals signals;
        final Map<String, Counting> queues = new HashMap<>();
        final List<FlowWorker> started = new ArrayList<>();

        SignalNode(String id, PgConnectOptions listen) {
            this.id = id;
            this.signals = new PgWorkSignals(vertx, listen, pool, id, workers, rt.timer());
            nodes.add(this);
            bounded(signals.start(), "the signals of " + id + " listen");
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of());
            for (String f : List.of(ORDERS, INVOICES)) {
                Counting q = new Counting(new InMemoryWorkQueue(rt.clock()));
                FlowWorker w = new FlowBuilder(runtime, List.of(PASS), List.of(), Map.of()).recording(new InMemoryMessageStore(), Meter.system())
                        .queue(q).dlq(new InMemoryDlq()).workers(workers).worker(flow(f), Path.of(""), id, new WorkerPolicy(LEASE, 5, QUIET));
                queues.put(f, q);
                w.start();
                started.add(w);
            }
            until(() -> claims(ORDERS) == 1 && claims(INVOICES) == 1, () -> "the start claims of " + id);
        }

        int claims(String flow) { return queues.get(flow).claims.get(); }

        void close() {
            started.forEach(FlowWorker::stop);
            bounded(signals.close(), "the signals of " + id + " close");
        }
    }

    /** A NOTIFY of the case's own on {@code flottila_work}, committed when this returns. */
    void notifyRaw(String payload) {
        bounded(db.pool().preparedQuery("select pg_notify('flottila_work', $1)").execute(Tuple.of(payload)), "the NOTIFY " + payload);
    }

    /** The {@code pg_notify} statements the node pool sent after {@code mark}. */
    long notifyStatements(int mark) {
        return poolWire.since(mark).stream().filter(s -> (s.type() == 'Q' || s.type() == 'E') && s.sql() != null
                && s.sql().toLowerCase(Locale.ROOT).contains("pg_notify")).count();
    }

    /** Everything the node pool sent before this was answered (one connection), and the observer has received every NOTIFY committed before. */
    void barrier() {
        bounded(pool.query("select 1").execute(), "the pool barrier");
        int before = (int) observed.stream().filter(SENTINEL::equals).count();
        notifyRaw(SENTINEL);
        until(() -> observed.stream().filter(SENTINEL::equals).count() == before + 1, () -> "the observer's sentinel; observed " + observed);
    }

    List<String> warnings() {
        return logs.lines.stream().filter(l -> l.startsWith("WARNING ")).toList();
    }

    // --- B1/B2: receiving

    /** B1/B2: parse and filter — another node's and the own sender's payloads wake nothing; this node's, {@code *} and flow {@code *} do. */
    @Test
    void aNotifyReachesTheListenerParsedAndFiltered() {
        SignalNode a = new SignalNode("n-a", through(listenWire));
        notifyRaw("w1 n-x n-b orders");    // another node's
        notifyRaw("w1 n-a n-a orders");    // this node's own sender
        notifyRaw("w1 n-x n-a invoices");  // the barrier: delivered after both
        until(() -> a.claims(INVOICES) == 2, () -> "invoices woken; claims " + a.queues);
        assertEquals(1, a.claims(ORDERS), "neither another node's payload nor the own sender's woke orders");

        notifyRaw("w1 n-x * orders");
        until(() -> a.claims(ORDERS) == 2, () -> "target *: orders woken");
        notifyRaw("w1 n-x n-a *");
        until(() -> a.claims(ORDERS) == 3 && a.claims(INVOICES) == 3, () -> "flow *: every worker woken; claims " + a.queues);
        notifyRaw("w1 n-x n-a invoices");
        until(() -> a.claims(INVOICES) == 4, () -> "the last barrier");
        assertEquals(3, a.claims(ORDERS), "each payload woke its workers once: " + a.queues);
        assertEquals(List.of(), warnings(), "well-formed payloads log nothing");
    }

    /** B1/B3: an announce is a NOTIFY on the pool that the other node's listener receives; the sender's own listener ignores it. */
    @Test
    void anAnnounceReachesTheOtherNodeAndNotItsSender() {
        SignalNode a = new SignalNode("n-a", through(listenWire));
        SignalNode b = new SignalNode("n-b", db.options());
        int mark = poolWire.mark();
        a.signals.announce("n-b", ORDERS);
        until(() -> b.claims(ORDERS) == 2, () -> "n-b's orders woken by n-a's announce; observed " + observed);
        notifyRaw("w1 n-x n-a orders");   // committed after n-a's NOTIFY: n-a's listener receives it after its own
        until(() -> a.claims(ORDERS) == 2, () -> "n-a woken by the raw one");
        assertEquals(2, a.claims(ORDERS), "n-a's own NOTIFY woke nothing on n-a");
        assertEquals(1, a.claims(INVOICES));
        assertEquals(1, b.claims(INVOICES));
        until(() -> observed.size() >= 2, () -> "the observer received both; observed " + observed);
        assertEquals(List.of("w1 n-a n-b orders", "w1 n-x n-a orders"), observed, "the payload: w1 <sender> <target> <flow>");
        assertEquals(1, notifyStatements(mark), "one pg_notify on the pool: " + poolWire.since(mark));
        assertEquals(0, listenWire.since(0).stream().filter(s -> s.sql() != null && s.sql().toLowerCase(Locale.ROOT).contains("pg_notify")).count(),
                "nothing is sent on the listener's dedicated connection");
        assertTrue(listenWire.since(0).stream().anyMatch(s -> s.sql() != null && s.sql().strip().toLowerCase(Locale.ROOT).startsWith("listen")),
                "n-a's LISTEN went on its dedicated connection: " + listenWire.since(0));
        assertEquals(0, poolWire.since(0).stream().filter(s -> s.sql() != null && s.sql().strip().toLowerCase(Locale.ROOT).startsWith("listen")).count(),
                "never a LISTEN on the pool");
    }

    // --- B3: sending

    @Test
    void laterAnnouncesInAWindowAreOneStatementAtItsEnd() {
        SignalNode a = new SignalNode("n-a", through(listenWire));
        int mark = poolWire.mark();
        a.signals.announce("n-b", ORDERS);
        until(() -> observed.size() == 1, () -> "the leading edge sent at once, the clock not moved");
        assertEquals(1, notifyStatements(mark));

        a.signals.announce("n-b", ORDERS);
        a.signals.announce("n-b", ORDERS);
        a.signals.announce("n-b", INVOICES);
        a.signals.announce(null, ORDERS);
        rt.clock().advance(WINDOW.minusMillis(1));
        barrier();
        assertEquals(List.of("w1 n-a n-b orders", SENTINEL), observed, "nothing more before the window's end");
        assertEquals(1, notifyStatements(mark));

        rt.clock().advance(Duration.ofMillis(1));
        barrier();
        assertEquals(2, notifyStatements(mark), "the window's later announces: one statement at its end: " + poolWire.since(mark));
        List<String> seen = List.copyOf(observed);
        List<String> folded = seen.subList(2, seen.size() - 1);
        assertEquals(Set.of("w1 n-a n-b orders", "w1 n-a n-b invoices", "w1 n-a * orders"), Set.copyOf(folded), "the distinct payloads, a null target as *");
        assertEquals(3, folded.size(), "each once: " + folded);
    }

    @Test
    void moreThan64DistinctAnnouncesInAWindowFoldToOnePayloadForEveryNodeAndFlow() {
        SignalNode a = new SignalNode("n-a", through(listenWire));
        int mark = poolWire.mark();
        a.signals.announce("n-b", ORDERS);
        until(() -> observed.size() == 1, () -> "the leading edge");
        for (int i = 1; i <= 65; i++) a.signals.announce("n-b", "f-" + i);
        rt.clock().advance(WINDOW);
        barrier();
        assertEquals(List.of("w1 n-a n-b orders", "w1 n-a * *", SENTINEL), observed, "65 distinct payloads fold to w1 <sender> * *");
        assertEquals(2, notifyStatements(mark));
    }

    /**
     * B3: a send that fails — its statement never reaches the server, the client's connection cut — logs one WARNING and is dropped:
     * never sent again; the next window's announce is sent at once.
     */
    @Test
    void aFailedSendIsDroppedWithOneWarningAndTheNextWindowSends() throws Exception {
        SignalNode a = new SignalNode("n-a", through(listenWire));
        PgWire.Held held = poolWire.holdNext(PgWire.statementStarting("select pg_notify"));
        try {
            a.signals.announce("n-b", ORDERS);
            held.awaitReached();
            held.cut();
            held.abort();
            until(() -> !warnings().isEmpty(), () -> "the failed send logged; lines " + logs.all());
        } finally {
            held.releaseQuietly();
        }
        rt.clock().advance(WINDOW);   // the window of the failed send ends: nothing pending
        a.signals.announce("n-b", INVOICES);
        until(() -> observed.contains("w1 n-a n-b invoices"), () -> "the next window's leading edge; observed " + observed);
        rt.clock().advance(WINDOW);
        rt.clock().advance(WINDOW);
        barrier();
        assertEquals(List.of("w1 n-a n-b invoices", SENTINEL), observed, "the failed payload was dropped, never sent again");
        // only the send-failure warnings: the barrier's own `sentinel` payload is not of the w1 form and warns on its own (B2)
        assertEquals(1, logs.warningsNaming("not sent").size(), "one WARNING for the failed send: " + warnings());
    }

    // --- B2: the dedicated connection

    /**
     * B2: the listener's backend terminated, the node reconnects (backoff on the node timer, driven here by the fake clock) and,
     * after its new LISTEN, wakes every worker exactly once — a missed event costs one reconciliation; the new LISTEN receives.
     */
    @Test
    void aTerminatedListenerReconnectsAndWakesEveryWorkerOnce() {
        SignalNode a = new SignalNode("n-a", through(listenWire));
        bounded(observer.close(), "the observer closes");   // this case observes the node only: its listener is the one listening backend
        String listening = "from pg_stat_activity where datname = current_database() and query ilike 'listen%' and pid <> pg_backend_pid()";
        until(() -> db.count("select count(*) " + listening) == 1, () -> "fixture: the node's listener is the one listening backend");
        int listenMark = listenWire.mark();
        long killed = db.count("select count(pg_terminate_backend(pid)) " + listening);
        assertEquals(1, killed, "fixture: the node's listener terminated");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!(a.claims(ORDERS) >= 2 && a.claims(INVOICES) >= 2)) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("no reconnect wake within 20 s; claims " + a.queues + ", pending " + rt.timer().pending());
            if (rt.timer().pending() > 0 && rt.timer().nextDelay().compareTo(Duration.ofMinutes(1)) <= 0) rt.clock().advance(rt.timer().nextDelay());
            Thread.onSpinWait();
        }
        assertEquals(2, a.claims(ORDERS), "the reconnect woke orders once");
        assertEquals(2, a.claims(INVOICES), "and invoices once");

        notifyRaw("w1 n-x n-a orders");
        until(() -> a.claims(ORDERS) == 3, () -> "the new LISTEN receives; claims " + a.queues);
        for (int s = 0; s < 60; s++) rt.clock().advance(Duration.ofSeconds(1));
        notifyRaw("w1 n-x n-a invoices");
        until(() -> a.claims(INVOICES) == 3, () -> "a later barrier; claims " + a.queues);
        assertEquals(3, a.claims(ORDERS), "no second wake of every worker: " + a.queues);
        assertEquals(1, listenWire.since(listenMark).stream().filter(s -> s.sql() != null && s.sql().strip().toLowerCase(Locale.ROOT).startsWith("listen")).count(),
                "one new LISTEN, on a new dedicated connection: " + listenWire.since(listenMark));
    }

    /** B2: a payload not of the {@code w1} form wakes nothing and logs one WARNING per distinct prefix. */
    @Test
    void aPayloadNotOfTheW1FormIsIgnoredWithOneWarningPerDistinctPrefix() {
        SignalNode a = new SignalNode("n-a", through(listenWire));
        notifyRaw("hello there");
        notifyRaw("hello again");
        notifyRaw("w9 n-x n-a orders");
        notifyRaw("w1 n-x n-a orders");   // the barrier: delivered after the three
        until(() -> a.claims(ORDERS) == 2, () -> "the well-formed one woke orders; claims " + a.queues);
        assertEquals(1, a.claims(INVOICES), "nothing else woken");
        List<String> w = warnings();
        assertEquals(2, w.size(), "one WARNING per distinct prefix (hello, w9): " + w);
        assertTrue(w.stream().anyMatch(l -> l.contains("hello")) && w.stream().anyMatch(l -> l.contains("w9")), "each naming its prefix: " + w);
    }
}
