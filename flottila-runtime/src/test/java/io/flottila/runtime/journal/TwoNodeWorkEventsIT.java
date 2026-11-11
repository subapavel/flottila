package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.flottila.runtime.admin.AdminServer;
import io.flottila.runtime.queue.PgWorkSignals;
import io.flottila.runtime.store.PgIntake;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static io.flottila.runtime.journal.JournalWorld.FLOW;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P2-16b (D-121 (b), B3, B4): the live work events between two nodes over one Postgres. Nodes {@code node-a} and {@code node-b} are
 * each a {@link JournalWorld} (journal, tiered store, message store, queue of the node) on a pool of its own relayed by a
 * {@link PgWire}, a {@link Workers} registry, a {@link PgWorkSignals} listening on a dedicated connection and sending on the node's
 * pool, an intake ({@link PgIntake}) and the started worker of flow {@code orders} (a {@link PgIntakeHandoffIT.Hold} step per node:
 * which node ran a message is which node's hold saw it). A node claims only the rows whose body lies in its own journal (D-105), so
 * each case leaves a row on node-b's body that node-a's action makes runnable — and node-b runs it, woken by node-a's event:
 *
 * <ul>
 *   <li>(i) node-a completes key K; K's next row lies on node-b — the completion cannot take it, it announces it;</li>
 *   <li>(ii) node-a's admin API releases a held row of node-b;</li>
 *   <li>(iii) node-a saves a message whose body is kept already on node-b — written plain, announced after the commit.</li>
 * </ul>
 *
 * <p>The fake clock never moves (asserted at the end of each case): no reconciliation, no lease end and no window end can explain a
 * run; every announce here is the leading edge of its window, sent at once. Each case also reads node-a's wire: its pool sent a
 * {@code pg_notify}, and never between a {@code BEGIN} and its {@code COMMIT} on one connection (B3: NOTIFY never inside a
 * transaction).
 *
 * <p>The API assumed (packet P2-16b; the shapes chosen by the test-author): {@code new PgWorkSignals(Vertx, PgConnectOptions listen,
 * Pool pool, String node, Workers, Timer)} with {@code start()}/{@code close()} ({@link PgWorkSignalsIT}); {@code new PgIntake(Pool,
 * TieredBlobStore, PgMessageStore, PgWorkQueue, Workers, WorkSignals)}; {@code FlowBuilder.signals(WorkSignals)} — the workers it
 * builds announce through it; {@code new AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional<Deployments>,
 * WorkQueue, Workers, WorkSignals)} — the held-work routes over the queue, a release waking the node's own worker and announcing
 * the row to its body's node.
 */
@Timeout(240)
class TwoNodeWorkEventsIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration LEASE = Duration.ofSeconds(30);
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    final FakeRuntime rt = FakeRuntime.create();
    final List<EventNode> nodes = new ArrayList<>();
    final List<AdminServer> admins = new ArrayList<>();
    final List<PgConnection> observers = new ArrayList<>();

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
        db = CaseDatabase.create(pg, vertx, "events_").migrated();
    }

    @AfterEach
    void close() throws Exception {
        for (AdminServer s : admins) s.stop().await(10, TimeUnit.SECONDS);
        for (EventNode n : nodes) n.close();
        for (PgConnection o : observers) o.close().await(10, TimeUnit.SECONDS);
        db.close();
    }

    static void until(java.util.function.BooleanSupplier condition, java.util.function.Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("not within 20 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    /** One node of the case: see the class comment. Its signals listen before its worker starts. */
    final class EventNode {
        final String id;
        final PgWire wire;
        final Pool pool;
        final JournalWorld world;
        final Workers workers = new Workers();
        final PgWorkSignals signals;
        final PgIntake intake;
        final PgIntakeHandoffIT.Claims claims;
        final PgIntakeHandoffIT.Hold hold = new PgIntakeHandoffIT.Hold();
        final FlowWorker worker;

        EventNode(String id) throws Exception {
            this(id, new WorkerPolicy(LEASE, 5, Duration.ofMinutes(10)));
        }

        /** As {@link #EventNode(String)}, its worker under {@code policy}. */
        EventNode(String id, WorkerPolicy policy) throws Exception {
            this.id = id;
            nodes.add(this);
            wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
            pool = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                    .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
            world = JournalWorld.open(vertx, pool, db.pool(), Files.createDirectories(tmp.resolve(id)), id);
            signals = new PgWorkSignals(vertx, db.options(), pool, id, workers, rt.timer());
            signals.start().await(20, TimeUnit.SECONDS);
            intake = new PgIntake(pool, world.blobs, world.messages, world.queue, workers, signals);
            claims = new PgIntakeHandoffIT.Claims(world.queue);
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), world.blobs, rt.hasher(), Map.of());
            worker = new FlowBuilder(runtime, List.of(hold), List.of(), Map.of()).recording(world.messages, Meter.system()).queue(claims)
                    .dlq(new InMemoryDlq()).workers(workers).signals(signals)
                    .worker(PgIntakeHandoffIT.flow(), Path.of(""), id, policy);
        }

        EventNode started() {
            int before = claims.calls.get();
            worker.start();
            until(() -> claims.calls.get() == before + 1 && claims.answered.get() == before + 1, () -> "the start claim of " + id + " answered");
            return this;
        }

        /** The save of {@code id} under {@code key} (null: none) through this node's intake, settled; its body written to this node's store. */
        Future<String> save(String messageId, String key) {
            Envelope e = world.arrive(messageId, bytes(2_000, messageId.hashCode()));
            return settled(intake.save(e, JournalWorld.ingress(e), FLOW, key, null, false, undecided()));
        }

        void close() throws Exception {
            worker.stop();
            hold.releaseAll();
            signals.close().await(20, TimeUnit.SECONDS);
            world.close();
            pool.close().await();
            wire.close();
        }
    }

    record WorkRow(String lockedBy, int attempts, String bodyNode) {}

    WorkRow row(String id) {
        for (Row r : db.pool().preparedQuery("select locked_by, attempts, body_node from work where id = $1").execute(Tuple.of(id)).await()) {
            return new WorkRow(r.getString(0), r.getInteger(1), r.getString(2));
        }
        throw new AssertionError("no work row of " + id);
    }

    /**
     * A raw session of the case's own, {@code LISTEN flottila_work}: every payload delivered on the channel, in delivery order —
     * what any node's listener receives (Postgres delivers one channel's notifications to every listener in commit order).
     */
    List<String> observer() throws Exception {
        List<String> observed = new CopyOnWriteArrayList<>();
        PgConnection o = PgConnection.connect(vertx, db.options()).await(10, TimeUnit.SECONDS);
        observers.add(o);
        o.notificationHandler(n -> observed.add(n.getPayload()));
        o.query("LISTEN flottila_work").execute().await(10, TimeUnit.SECONDS);
        return observed;
    }

    /**
     * B3 on node-a's wire: its pool sent at least one {@code pg_notify}, and none while a transaction was open on its connection —
     * a simple-query {@code BEGIN} opens one, {@code COMMIT} or {@code ROLLBACK} ends it.
     */
    static void notifiedOutsideEveryTransaction(PgWire wire) {
        Set<Integer> open = new HashSet<>();
        int notifies = 0;
        for (PgWire.Sent s : wire.since(0)) {
            String sql = s.sql() == null ? "" : s.sql().strip().toLowerCase(Locale.ROOT);
            if (s.type() == 'Q' && sql.equals("begin")) open.add(s.conn());
            else if (s.type() == 'Q' && (sql.equals("commit") || sql.equals("rollback"))) open.remove(s.conn());
            else if ((s.type() == 'Q' || s.type() == 'E') && sql.contains("pg_notify")) {
                notifies++;
                if (open.contains(s.conn())) fail("a pg_notify inside a transaction on connection " + s.conn() + ": " + wire.since(0));
            }
        }
        assertTrue(notifies >= 1, "node-a announced on its pool: " + wire.since(0));
    }

    /** B4 (i): node-a completes K; K's next row lies on node-b, which node-a cannot claim — node-a announces it, node-b runs it. */
    @Test
    void aKeyedCompletionWhoseNextRowLiesOnAnotherNodeRunsItThere() throws Exception {
        EventNode a = new EventNode("node-a").started();
        EventNode b = new EventNode("node-b").started();
        assertTrue(a.save("m-1", "K").succeeded(), "m-1 saved on node-a");
        until(() -> a.hold.entries("m-1").size() == 1, () -> "m-1 handed off on node-a and running; " + a.hold.events);
        assertTrue(b.save("m-2", "K").succeeded(), "m-2 saved on node-b");
        WorkRow m2 = row("m-2");
        assertEquals(new WorkRow(null, 0, "node-b"), m2, "fixture: m-2 waits behind K's holder, plain, its body on node-b");

        a.hold.release("m-1");   // node-a's keyed completion: K's next row is node-b's
        until(() -> b.hold.entries("m-2").size() == 1, () -> "node-b runs m-2, woken by node-a's completion; node-b's claims "
                + b.claims.calls + " (items " + b.claims.items + "), row " + row("m-2") + ", node-a events " + a.hold.events);
        assertEquals(List.of("enter m-2@1"), b.hold.entries("m-2"), "its first hand-out, on node-b");
        assertEquals(List.of(), a.hold.entries("m-2"), "never on node-a");
        assertTrue(b.claims.items.stream().anyMatch(i -> i.messageId().equals("m-2")), "node-b claimed it: " + b.claims.items);
        assertEquals(FakeRuntime.T0, rt.clock().instant(), "no clock advance: no reconciliation, lease end or window end");
        notifiedOutsideEveryTransaction(a.wire);
    }

    /**
     * B4 (i), a plain keyed completion — the chain's bound: node-a's worker has no fast path ({@code maxChain} 0), so m-1 of K is
     * completed by a plain complete, which takes nothing; K's next row lies on node-b — node-a announces it all the same, and node-b
     * runs it with no tick.
     */
    @Test
    void aKeyedCompletionAtTheChainBoundWhoseNextRowLiesOnAnotherNodeRunsItThere() throws Exception {
        EventNode a = new EventNode("node-a", new WorkerPolicy(LEASE, 5, Duration.ofMinutes(10), 0)).started();
        EventNode b = new EventNode("node-b").started();
        plainKeyedCompletionRunsTheNextRowOnB(a, b, () -> { });
    }

    /**
     * B4 (i), a plain keyed completion — a stopped worker: node-a's worker is stopped while m-1 of K runs, so m-1 is completed by a
     * plain complete (a stopped worker takes no next item); K's next row lies on node-b — node-a announces it, node-b runs it.
     */
    @Test
    void aStoppedWorkersKeyedCompletionWhoseNextRowLiesOnAnotherNodeRunsItThere() throws Exception {
        EventNode a = new EventNode("node-a").started();
        EventNode b = new EventNode("node-b").started();
        plainKeyedCompletionRunsTheNextRowOnB(a, b, a.worker::stop);
    }

    /** m-1 of K running on node-a, m-2 of K saved on node-b behind it; {@code beforeRelease}, then m-1's run ends: node-b runs m-2. */
    void plainKeyedCompletionRunsTheNextRowOnB(EventNode a, EventNode b, Runnable beforeRelease) {
        assertTrue(a.save("m-1", "K").succeeded(), "m-1 saved on node-a");
        until(() -> a.hold.entries("m-1").size() == 1, () -> "m-1 handed off on node-a and running; " + a.hold.events);
        assertTrue(b.save("m-2", "K").succeeded(), "m-2 saved on node-b");
        assertEquals(new WorkRow(null, 0, "node-b"), row("m-2"), "fixture: m-2 waits behind K's holder, plain, its body on node-b");
        beforeRelease.run();

        a.hold.release("m-1");   // node-a's plain keyed completion: K's next row is node-b's
        until(() -> b.hold.entries("m-2").size() == 1, () -> "node-b runs m-2, woken by node-a's plain completion; node-b's claims "
                + b.claims.calls + " (items " + b.claims.items + "), row " + row("m-2") + ", node-a events " + a.hold.events
                + ", node-a's statements " + a.wire.statementsSince(0).stream().map(q -> q.substring(0, Math.min(40, q.length()))).toList());
        assertEquals(List.of("enter m-2@1"), b.hold.entries("m-2"), "its first hand-out, on node-b");
        assertEquals(List.of(), a.hold.entries("m-2"), "never on node-a");
        assertEquals(FakeRuntime.T0, rt.clock().instant(), "no clock advance: no reconciliation, lease end or window end");
        notifiedOutsideEveryTransaction(a.wire);
    }

    /** B4 (ii): node-a's admin API releases a held row whose body lies on node-b; node-b runs it. */
    @Test
    void anAdminReleaseOnOneNodeOfAnotherNodesHeldRowRunsItThere() throws Exception {
        EventNode a = new EventNode("node-a").started();
        EventNode b = new EventNode("node-b");   // started once its row is held
        assertTrue(b.save("m-h", null).succeeded(), "m-h saved on node-b");
        assertEquals(new WorkRow(null, 0, "node-b"), row("m-h"), "fixture: no worker started on node-b: a plain row");
        List<WorkQueue.Item> taken = b.world.queue.claim("node-b", FLOW, 1, LEASE).await();
        assertEquals(1, taken.size(), "fixture: claimed by node-b");
        assertTrue(b.world.queue.hold(taken.get(0).seq(), taken.get(0).attempts()).await(), "fixture: held");
        b.started();   // its start claim finds the row held

        AdminServer admin = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.empty()), a.world.messages, a.world.dlq, a.world.blobs,
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.empty(), a.world.queue, a.workers, a.signals);
        admins.add(admin);
        int port = admin.start(vertx).await(10, TimeUnit.SECONDS);
        HttpResponse<String> released = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/work/" + taken.get(0).seq() + "/release"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, released.statusCode(), released.body());

        until(() -> b.hold.entries("m-h").size() == 1, () -> "node-b runs the released row, woken by node-a's release; node-b's claims "
                + b.claims.calls + " (items " + b.claims.items + "), row " + row("m-h"));
        assertEquals(List.of("enter m-h@2"), b.hold.entries("m-h"), "its second hand-out (the release keeps the attempts), on node-b");
        assertEquals(List.of(), a.hold.entries("m-h"), "never on node-a");
        assertEquals(FakeRuntime.T0, rt.clock().instant(), "no clock advance");
        notifiedOutsideEveryTransaction(a.wire);
    }

    /** B4 (iii): node-a saves a message whose body is kept already in node-b's journal: written plain, announced, run by node-b. */
    @Test
    void aSaveWhoseKeptBodyLiesOnAnotherNodeRunsThere() throws Exception {
        EventNode a = new EventNode("node-a").started();
        EventNode b = new EventNode("node-b").started();
        Envelope onB = b.world.arrive("m-far", bytes(2_000, 12));
        BlobKey key = onB.body().key();
        assertTrue(settled(b.world.blobs.persist(key)).succeeded(), "fixture: durable in node-b's journal");
        Envelope in = Envelope.ingress("m-far", FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"),
                Body.of(a.world.blobs, key, onB.body().hash(), onB.body().size()));   // node-a's store, no local copy: kept already

        Future<String> saved = settled(a.intake.save(in, JournalWorld.ingress(in), FLOW, null, null, false, undecided()));
        assertTrue(saved.succeeded(), "saved on node-a: " + saved.cause());
        until(() -> b.hold.entries("m-far").size() == 1, () -> "node-b runs m-far, woken by node-a's save; node-b's claims "
                + b.claims.calls + " (items " + b.claims.items + ")");
        assertEquals(List.of("enter m-far@1"), b.hold.entries("m-far"), "its first hand-out, on node-b");
        assertEquals(List.of(), a.hold.entries("m-far"), "never on node-a");
        assertEquals(FakeRuntime.T0, rt.clock().instant(), "no clock advance");
        notifiedOutsideEveryTransaction(a.wire);
    }

    /**
     * Review r1 V4, PgIntake's hand-off: node-a's intake announces through signals whose announce throws (their window's timer
     * refused) — after the commit, so every save still answers its id: two saves whose kept bodies lie on node-b, each written
     * plain and announced (the announce tried, and threw), then a save of node-a's own, which runs on node-a: the intake goes on.
     */
    @Test
    void savesWhoseAnnounceThrowsAreAnsweredAndTheIntakeGoesOn() throws Exception {
        EventNode a = new EventNode("node-a").started();
        EventNode b = new EventNode("node-b").started();
        java.util.concurrent.atomic.AtomicInteger tried = new java.util.concurrent.atomic.AtomicInteger();
        IllegalStateException refusal = new IllegalStateException("the work events' timer refused the window");
        io.flottila.core.flow.WorkSignals refusing = (target, flow) -> {
            tried.incrementAndGet();
            throw refusal;
        };
        java.util.logging.Logger intakeLog = java.util.logging.Logger.getLogger(PgIntake.class.getName());   // held: JUL keeps loggers weakly
        NodeJournalIT.Logs logs = new NodeJournalIT.Logs();
        List<java.util.logging.LogRecord> warned = new CopyOnWriteArrayList<>();
        java.util.logging.Handler carrying = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel() == java.util.logging.Level.WARNING && (r.getThrown() == refusal
                        || String.valueOf(r.getMessage()).contains(refusal.getMessage()))) warned.add(r);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        intakeLog.addHandler(carrying);
        intakeLog.addHandler(logs);
        try {
            savedDespiteTheThrow(a, b, refusing, tried);
            assertEquals(2, warned.size(), "each announce's throw logged at WARNING by the intake: " + logs.all());
        } finally {
            intakeLog.removeHandler(carrying);
            intakeLog.removeHandler(logs);
        }
    }

    void savedDespiteTheThrow(EventNode a, EventNode b, io.flottila.core.flow.WorkSignals refusing, java.util.concurrent.atomic.AtomicInteger tried) throws Exception {
        PgIntake intake = new PgIntake(a.pool, a.world.blobs, a.world.messages, a.world.queue, a.workers, refusing);
        for (String id : List.of("m-far-1", "m-far-2")) {
            Envelope onB = b.world.arrive(id, bytes(2_000, id.hashCode()));
            BlobKey key = onB.body().key();
            assertTrue(settled(b.world.blobs.persist(key)).succeeded(), "fixture: durable in node-b's journal");
            Envelope in = Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"),
                    Body.of(a.world.blobs, key, onB.body().hash(), onB.body().size()));   // node-a's store, no local copy: kept already
            Future<String> saved = settled(intake.save(in, JournalWorld.ingress(in), FLOW, null, null, false, undecided()));
            assertTrue(saved.succeeded(), id + " answered despite the announce's throw: " + saved.cause());
            assertEquals(id, saved.result());
            assertEquals("node-b", row(id).bodyNode(), "fixture: " + id + " written plain, its body on node-b");
        }
        assertEquals(2, tried.get(), "witness: each save's announce was made and threw");

        Envelope near = a.world.arrive("m-near", bytes(2_000, 7));
        Future<String> saved = settled(intake.save(near, JournalWorld.ingress(near), FLOW, null, null, false, undecided()));
        assertTrue(saved.succeeded(), "a later save of node-a's own answered: " + saved.cause());
        until(() -> a.hold.entries("m-near").size() == 1, () -> "m-near runs on node-a: the intake's hand-off goes on; a's claims " + a.claims.calls);
        assertEquals(FakeRuntime.T0, rt.clock().instant(), "no clock advance");
    }

    /**
     * B4 (iii) with B3's "after the commit is acknowledged": the reply to the COMMIT of node-a's save is held at node-a's relay — the
     * transaction committed on the server, node-a not told yet. While it is held, no NOTIFY reaches a listener of the channel: a
     * barrier NOTIFY sent through node-a's own pool once the hold is reached is the first payload delivered. Once the reply goes, the
     * announce follows it and node-b runs m-far.
     */
    @Test
    void aSaveWhoseKeptBodyLiesOnAnotherNodeIsAnnouncedOnlyOnceItsCommitIsAcknowledged() throws Exception {
        EventNode a = new EventNode("node-a").started();
        EventNode b = new EventNode("node-b").started();
        List<String> observed = observer();
        Envelope onB = b.world.arrive("m-far", bytes(2_000, 12));
        BlobKey key = onB.body().key();
        assertTrue(settled(b.world.blobs.persist(key)).succeeded(), "fixture: durable in node-b's journal");
        Envelope in = Envelope.ingress("m-far", FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"),
                Body.of(a.world.blobs, key, onB.body().hash(), onB.body().size()));   // node-a's store, no local copy: kept already

        Set<Integer> enqueuing = ConcurrentHashMap.newKeySet();   // node-a's connections that sent the save's work insert
        Predicate<PgWire.Sent> saveCommit = s -> {
            if (s.sql() != null && s.sql().contains("insert into work(")) enqueuing.add(s.conn());
            return enqueuing.contains(s.conn()) && PgWire.query("commit").test(s);
        };
        PgWire.HeldReply commitReply = a.wire.holdReplyTo(saveCommit);
        Future<String> saved;
        String barrier = "w1 test node-z orders";   // of the w1 form, for no node of the case: ignored by both
        try {
            saved = a.intake.save(in, JournalWorld.ingress(in), FLOW, null, null, false, undecided());
            commitReply.awaitReached();   // committed on the server; its acknowledgement held
            a.wire.awaitQuiet();
            assertTrue(a.pool.preparedQuery("select pg_notify('flottila_work', $1)").execute(Tuple.of(barrier))
                    .await(10, TimeUnit.SECONDS).size() == 1, "the barrier NOTIFY through node-a's pool");
            until(() -> observed.contains(barrier), () -> "the observer receives the barrier; observed " + observed);
            assertEquals(List.of(barrier), observed, "no NOTIFY reached a listener before the save's COMMIT was acknowledged");
            assertFalse(saved.isComplete(), "fixture: the save waits for its COMMIT's reply: " + saved);
            assertEquals(List.of(), b.hold.entries("m-far"), "not run on node-b before the acknowledgement");
        } finally {
            commitReply.release();
        }
        assertTrue(settled(saved).succeeded(), "saved on node-a: " + saved.cause());
        until(() -> b.hold.entries("m-far").size() == 1, () -> "node-b runs m-far, woken by node-a's save; node-b's claims "
                + b.claims.calls + " (items " + b.claims.items + "); observed " + observed);
        assertEquals(List.of(barrier, "w1 node-a node-b " + FLOW), observed, "the announce follows the acknowledged commit, once");
        assertEquals(List.of("enter m-far@1"), b.hold.entries("m-far"), "its first hand-out, on node-b");
        assertEquals(FakeRuntime.T0, rt.clock().instant(), "no clock advance");
        notifiedOutsideEveryTransaction(a.wire);
    }
}
