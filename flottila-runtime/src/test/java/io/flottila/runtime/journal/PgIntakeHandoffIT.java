package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Pipeline;
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
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Page;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgIntake;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.flottila.runtime.journal.JournalWorld.FLOW;
import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static io.flottila.runtime.journal.JournalWorld.count;
import static io.flottila.runtime.journal.JournalWorld.settled;
import static io.flottila.runtime.journal.JournalWorld.undecided;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-16a (D-121 A2–A5, D-116, F-30): the hand-off through runtime's {@link PgIntake}. Node {@code node-a} (a {@link JournalWorld}: its
 * journal, tiered store, message store and queue on a pool relayed by {@link PgWire}) has the started worker of flow {@code orders}
 * registered in its {@link Workers}; the intake under test reserves its slots there. The saved item is written leased to the node —
 * {@code locked_by}, {@code locked_until = now() + lease}, attempts 1 — by the intake's own insert, in the group transaction; a keyed
 * insert and a keyed complete take the key's transaction advisory lock, so two same-key saves never both lease and a keyed
 * completion never misses a same-key save (F-30's lost hand-on); a commit in doubt leaves the row to its lease, claimed at its end
 * with a doubt.
 *
 * <p>Witnesses without sleeps: Postgres' {@code pg_locks} (a waiter on a two-key advisory lock — {@code objsubid = 2} — is a key lock;
 * the test's own plug is a one-key lock, {@code objsubid = 1}); {@code xmin} of the rows (one transaction wrote the record and the
 * leased item, no later update leased it); a trigger killing its own backend (FATAL inside the transaction); {@link PgWire} replacing
 * the COMMIT's reply by a FATAL (the commit landed, its outcome unknown to the client). The worker's claims are counted at the queue
 * boundary; its poll interval is ten minutes and no case advances the fake clock that far but the in-doubt one, by one lease.
 *
 * <p>The API assumed (packet P2-16a; the constructor chosen by the test-author): {@code new PgIntake(Pool, TieredBlobStore,
 * PgMessageStore, PgWorkQueue, Workers)}, every save a group of one, its hand-offs leased to the queue's node; {@code new Workers()};
 * {@code FlowBuilder.workers(Workers)}.
 */
@Timeout(240)
class PgIntakeHandoffIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final Duration LEASE = Duration.ofSeconds(30);
    static final String NODE = "node-a";
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    PgWire wire;
    Pool relayed;
    JournalWorld a;
    JournalWorld b;   // node-b, only for the case of a body in another node's journal
    final FakeRuntime rt = FakeRuntime.create();
    final Workers workers = new Workers();
    PgIntake intake;
    Claims claims;
    Hold hold;
    FlowWorker worker;
    final List<SqlConnection> sessions = new ArrayList<>();

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

    /** Counts the worker's claims; every other call straight to the node's queue (a decorator at the store boundary). */
    static final class Claims implements WorkQueue {
        final WorkQueue inner;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger answered = new AtomicInteger();
        final List<Item> items = new CopyOnWriteArrayList<>();

        Claims(WorkQueue inner) { this.inner = inner; }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return inner.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<Optional<Item>> enqueueLeased(String messageId, String flow, int step, String partitionKey, String workerId, Duration lease) {
            return inner.enqueueLeased(messageId, flow, step, partitionKey, workerId, lease);
        }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) {
            calls.incrementAndGet();
            return inner.claim(workerId, flow, max, lease).onComplete(done -> {
                if (done.succeeded()) items.addAll(done.result());
                answered.incrementAndGet();
            });
        }
        @Override public Future<Boolean> complete(long seq, int attempts) { return inner.complete(seq, attempts); }
        @Override public Future<Completion> completeRow(long seq, int attempts) { return inner.completeRow(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            return inner.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return inner.renew(seq, attempts, lease); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return inner.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return inner.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return inner.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return inner.hasItems(flow); }
    }

    /** A step ({@code type: hold}) holding each run until its message is released; logs "enter id@attempt" and "exit id". */
    static final class Hold implements StepFactory {
        final List<String> events = new CopyOnWriteArrayList<>();
        final Map<String, CountDownLatch> releases = new ConcurrentHashMap<>();

        @Override public String type() { return "hold"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Stage create(StepConfig step, StepResources resources) { return this::pass; }

        private Envelope pass(Envelope e, Pipeline rest) {
            events.add("enter " + e.id() + "@" + e.get(Envelope.ATTEMPT).orElse(1));
            try {
                if (!latch(e.id()).await(60, TimeUnit.SECONDS)) throw new IllegalStateException("hold: " + e.id() + " never released");
                return rest.apply(e);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("hold: interrupted", interrupted);
            } finally {
                events.add("exit " + e.id());
            }
        }

        CountDownLatch latch(String id) { return releases.computeIfAbsent(id, k -> new CountDownLatch(1)); }

        void release(String id) { latch(id).countDown(); }

        void releaseAll() { releases.values().forEach(CountDownLatch::countDown); }

        List<String> entries(String id) {
            return events.stream().filter(s -> s.startsWith("enter " + id + "@")).toList();
        }
    }

    static void until(java.util.function.BooleanSupplier condition, java.util.function.Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) throw new AssertionError("not within 20 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    static FlowDefinition flow() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", FLOW);
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("steps", List.of(Map.of("type", "hold", "name", "hold")));
        return FlowDefinition.from(doc);
    }

    @BeforeEach
    void open() throws Exception {
        db = CaseDatabase.create(pg, vertx, "handoff_").migrated();
        wire = new PgWire(pg.getHost(), pg.getMappedPort(5432));
        relayed = PgBuilder.pool().connectingTo(db.options().setHost("127.0.0.1").setPort(wire.port()))
                .with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        a = JournalWorld.open(vertx, relayed, db.pool(), Files.createDirectories(tmp.resolve(NODE)), NODE);
        intake = new PgIntake(a.pool, a.blobs, a.messages, a.queue, workers);
        claims = new Claims(a.queue);
        hold = new Hold();
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), a.blobs, rt.hasher(), Map.of());
        worker = new FlowBuilder(runtime, List.of(hold), List.of(), Map.of()).recording(a.messages, Meter.system()).queue(claims)
                .dlq(new InMemoryDlq()).workers(workers).worker(flow(), Path.of(""), NODE, new WorkerPolicy(LEASE, 5, Duration.ofMinutes(10)));
        worker.start();
        until(() -> claims.calls.get() == 1 && claims.answered.get() == 1, () -> "the worker's one start claim answered (A6)");
    }

    @AfterEach
    void close() throws Exception {
        if (worker != null) worker.stop();
        if (hold != null) hold.releaseAll();
        for (SqlConnection s : sessions) s.close().await();
        if (a != null) a.close();
        if (b != null) b.close();
        wire.close();
        relayed.close().await();
        db.close();
    }

    /** The save of {@code id} under {@code key} (null: none) through the intake under test, not waited for; its input not kept. */
    Future<String> start(String id, String key) {
        Envelope e = a.arrive(id, bytes(2_000, id.hashCode()));
        return intake.save(e, JournalWorld.ingress(e), FLOW, key, null, false, undecided());
    }

    record WorkRow(String lockedBy, int attempts, int doubts, boolean leasedForTheLease, String xmin) {}

    WorkRow row(String id) {
        for (Row r : db.pool().preparedQuery("select locked_by, attempts, doubts, locked_until > now() + interval '20 seconds' and locked_until <= now() + interval '30 seconds', "
                + "xmin::text from work where id = $1").execute(Tuple.of(id)).await()) {
            return new WorkRow(r.getString(0), r.getInteger(1), r.getInteger(2), Boolean.TRUE.equals(r.getBoolean(3)), r.getString(4));
        }
        throw new AssertionError("no work row of " + id);
    }

    /** Sessions waiting for an advisory lock of {@code keys} keys (1: the test's plug; 2: a key lock). */
    long advisoryWaiters(int keys) {
        return count(db.pool(), "select count(*) from pg_locks where locktype = 'advisory' and not granted and objsubid = " + keys
                + " and database = (select oid from pg_database where datname = current_database())");
    }

    /** A session of the case's own holding the one-key advisory lock {@code k} until {@link #unplug}. */
    SqlConnection plug(long k) {
        SqlConnection s = db.pool().getConnection().await();
        sessions.add(s);
        s.preparedQuery("select pg_advisory_lock($1)").execute(Tuple.of(k)).await();
        return s;
    }

    static void unplug(SqlConnection s, long k) {
        s.preparedQuery("select pg_advisory_unlock($1)").execute(Tuple.of(k)).await();
    }

    /** An AFTER INSERT trigger on work: the insert of {@code id} waits for the one-key advisory lock {@code k} (its transaction open). */
    void pauseAfterInsertOf(String id, long k) {
        db.pool().query("create or replace function pause_after_insert() returns trigger language plpgsql as $$ begin "
                + "if new.id = '" + id + "' then perform pg_advisory_xact_lock(" + k + "); end if; return new; end $$").execute().await();
        db.pool().query("create trigger pause_after_insert after insert on work for each row execute function pause_after_insert()").execute().await();
    }

    @Test
    void theItemIsWrittenLeasedInTheIntakeTransaction() {
        Future<String> saved = settled(start("m-1", "K-1"));
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        WorkRow w = row("m-1");
        assertEquals(NODE, w.lockedBy(), "leased to the node that saved it: " + w);
        assertEquals(1, w.attempts(), "attempts 1: the hand-out is the insert");
        assertEquals(0, w.doubts());
        assertTrue(w.leasedForTheLease(), "locked_until = now() + the flow's lease (30 s), on the database clock: " + w);
        String recordXmin = db.pool().preparedQuery("select xmin::text from messages where id = $1 and step = 0").execute(Tuple.of("m-1")).await()
                .iterator().next().getString(0);
        assertEquals(recordXmin, w.xmin(), "one transaction wrote the record and the leased item, and nothing updated the item since (no claim)");
        until(() -> hold.entries("m-1").size() == 1, () -> "the worker runs the handed-off item; events " + hold.events);
        assertEquals(List.of("enter m-1@1"), hold.entries("m-1"), "its first run, attempts 1");
        assertEquals(1, claims.calls.get(), "no claim for it");

        db.pool().query("create or replace function kill_on_insert() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-killed' then perform pg_terminate_backend(pg_backend_pid()); end if; return new; end $$").execute().await();
        db.pool().query("create trigger kill_on_insert after insert on work for each row execute function kill_on_insert()").execute().await();
        Future<String> killed = settled(start("m-killed", null));
        assertTrue(killed.failed(), "the session died inside the save's transaction: the save fails");
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-killed'"), "a killed session leaves no item, leased or not");
        assertEquals(0, count(db.pool(), "select count(*) from messages where id = 'm-killed'"), "nor its record");

        Future<String> next = settled(start("m-2", null));   // room 2: m-1 runs, the killed save's slot must have come back
        assertTrue(next.succeeded(), "saved: " + next.cause());
        assertEquals(NODE, row("m-2").lockedBy(), "the failed save gave its slot back: the next save is handed off");
        assertEquals(List.of(), hold.entries("m-killed"), "nothing of the killed save ran");
        hold.releaseAll();
    }

    @Test
    void twoConcurrentSameKeySavesLeaseOnlyOne() {
        SqlConnection plug = plug(4242);
        pauseAfterInsertOf("m-first", 4242);
        Future<String> first = start("m-first", "K");
        until(() -> advisoryWaiters(1) == 1, () -> "the first save's insert paused on the plug, its transaction open");
        Future<String> second = start("m-second", "K");
        until(() -> advisoryWaiters(2) == 1, () -> "the second same-key save waits for the key's lock (pg_locks): " + advisoryWaiters(2) + " waiters; "
                + "rows " + count(db.pool(), "select count(*) from work"));
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-second'"), "the second save has not committed");

        unplug(plug, 4242);
        assertTrue(settled(first).succeeded(), "first saved");
        assertTrue(settled(second).succeeded(), "second saved");
        WorkRow f = row("m-first");
        WorkRow s = row("m-second");
        assertEquals(NODE, f.lockedBy(), "the first heads K: leased");
        assertEquals(1, f.attempts());
        assertEquals(null, s.lockedBy(), "the second does not head K: written unleased (" + s + ")");
        assertEquals(0, s.attempts());

        until(() -> hold.entries("m-first").size() == 1, () -> "the first runs; " + hold.events);
        assertEquals(List.of(), hold.entries("m-second"), "the second waits behind it");
        hold.release("m-first");   // its holder hands K on by the fast path
        until(() -> hold.entries("m-second").size() == 1, () -> "the second runs after the first; " + hold.events);
        hold.release("m-second");
        until(() -> count(db.pool(), "select count(*) from work") == 0, () -> "both completed; " + hold.events);
        assertEquals(List.of("enter m-first@1", "exit m-first", "enter m-second@1", "exit m-second"), hold.events, "never two runs of K at once");
        assertEquals(1, claims.calls.get(), "no claim but the start claim");
    }

    @Test
    void aKeyedCompletionAndASameKeySaveNeverStrandTheSave() {
        assertTrue(settled(start("m-1", "K")).succeeded());
        until(() -> hold.entries("m-1").size() == 1, () -> "m-1 handed off and running; " + hold.events);

        SqlConnection plug = plug(4343);
        pauseAfterInsertOf("m-2", 4343);
        Future<String> second = start("m-2", "K");   // m-1 exists: m-2 is not the head; its transaction stays open after its insert
        until(() -> advisoryWaiters(1) == 1, () -> "m-2's insert paused, uncommitted");
        hold.release("m-1");   // m-1's keyed completion runs while m-2 is uncommitted (F-30)
        until(() -> advisoryWaiters(2) >= 1 || count(db.pool(), "select count(*) from work where id = 'm-1'") == 0,
                () -> "m-1's completion waits for K's lock or has completed");
        unplug(plug, 4343);
        assertTrue(settled(second).succeeded(), "m-2 saved: " + second.cause());

        until(() -> hold.entries("m-2").size() == 1, () -> "m-2 is handed on by m-1's completion, never stranded until a tick; events " + hold.events
                + ", m-2's row " + row("m-2"));
        assertEquals(List.of("enter m-2@1"), hold.entries("m-2"));
        hold.release("m-2");
        until(() -> count(db.pool(), "select count(*) from work") == 0, () -> "both completed");
        assertEquals(1, claims.calls.get(), "no claim but the start claim: the fake clock never moved");
    }

    @Test
    void anInDoubtCommitIsClaimedAtItsLeaseEnd() throws Exception {
        PgWire.FatalReply lost = wire.fatalReplyTo(PgWire.query("COMMIT"));   // the commit lands; the client gets a FATAL instead of its answer
        Future<String> saved = settled(start("m-doubt", null));
        lost.awaitDone();
        assertTrue(saved.failed(), "a commit in doubt fails the save");
        WorkRow w = row("m-doubt");
        assertEquals(NODE, w.lockedBy(), "the commit landed: the row is leased to the node as written");
        assertEquals(1, w.attempts());

        rt.clock().advance(LEASE.minusMillis(1));
        assertEquals(1, claims.calls.get(), "nothing claims it before its lease end");
        assertEquals(List.of(), hold.entries("m-doubt"), "the in-doubt slot was cancelled, not run");
        db.pool().query("update work set locked_until = now() - interval '1 second' where id = 'm-doubt'").execute().await();   // the lease ends on the database clock
        rt.clock().advance(Duration.ofMillis(1));   // the one wake armed at now + lease
        until(() -> hold.entries("m-doubt").size() == 1, () -> "claimed at its lease end; claims " + claims.calls + ", items " + claims.items);
        assertEquals(2, claims.calls.get(), "one claim, at the lease end");
        assertEquals(List.of("enter m-doubt@2"), hold.entries("m-doubt"), "run once, its second hand-out");
        assertEquals(1, row("m-doubt").doubts(), "a re-take of a row its holder may have run counts a doubt (A5)");
        hold.releaseAll();
    }

    /** The save of {@code e} without a key through {@code on}, settled; its input not kept. */
    static Future<String> saved(PgIntake on, Envelope e) {
        return settled(on.save(e, JournalWorld.ingress(e), FLOW, null, null, false, undecided()));
    }

    /** The node of the journal that {@code id}'s work row names its body in (null: none). */
    String bodyNode(String id) {
        return JournalWorld.workRef(db.pool(), id).node();
    }

    /**
     * Review r1 R1 (PgIntake:413, ENQUEUE_FROM): a save whose body is durable already in this node's journal — no record of its own,
     * the row takes the key's ref — is written leased to the node (attempts 1) in the save's transaction and run by its slot with
     * no claim.
     */
    @Test
    void aBodyDurableAlreadyOnThisNodeIsWrittenLeasedAndRunsWithNoClaim() {
        Envelope in = a.arrive("m-durable", bytes(2_000, 11));
        assertTrue(settled(a.blobs.persist(in.body().key())).succeeded(), "fixture: durable before its save, its ref on node-a, no local copy");
        assertEquals(NODE, JournalWorld.bodyRef(db.pool(), in.body().key()).node(), "fixture: the key's ref lies in node-a's journal");

        Future<String> saved = saved(intake, in);
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        WorkRow w = row("m-durable");
        assertEquals(NODE, w.lockedBy(), "the ref lies in the holder's journal: leased to the node (" + w + ")");
        assertEquals(1, w.attempts(), "attempts 1: the hand-out is the insert");
        assertTrue(w.leasedForTheLease(), "locked_until = now() + the flow's lease: " + w);
        assertEquals(NODE, bodyNode("m-durable"), "the row took the key's ref");
        until(() -> hold.entries("m-durable").size() == 1, () -> "the slot runs the item; events " + hold.events);
        assertEquals(List.of("enter m-durable@1"), hold.entries("m-durable"));
        assertEquals(1, claims.calls.get(), "no claim but the start claim");
        hold.releaseAll();
    }

    /**
     * Review r1 R1 (PgWorkQueue ENQUEUE_FROM {@code src.node = $8}): a save whose body is durable already in another node's journal
     * is written plain — a node claims only its own bodies (D-105) — and claimed normally by that node; the slot it reserved is
     * given back.
     */
    @Test
    void aBodyDurableAlreadyOnAnotherNodeIsWrittenPlainAndClaimedNormally() throws Exception {
        b = JournalWorld.open(vertx, db.pool(), db.pool(), Files.createDirectories(tmp.resolve("node-b")), "node-b");
        Envelope onB = b.arrive("m-far", bytes(2_000, 12));
        BlobKey key = onB.body().key();
        assertTrue(settled(b.blobs.persist(key)).succeeded(), "fixture: durable in node-b's journal");
        assertEquals("node-b", JournalWorld.bodyRef(db.pool(), key).node(), "fixture: the key's ref lies in node-b's journal");
        Envelope in = Envelope.ingress("m-far", FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"),
                Body.of(a.blobs, key, onB.body().hash(), onB.body().size()));   // node-a's store, no local copy: durable already

        Future<String> saved = saved(intake, in);
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        WorkRow w = row("m-far");
        assertEquals(null, w.lockedBy(), "the ref lies in node-b's journal: written unleased (" + w + ")");
        assertEquals(0, w.attempts(), "never handed out");
        assertEquals("node-b", bodyNode("m-far"), "the row took the key's ref on node-b");

        List<WorkQueue.Item> taken = new PgWorkQueue(db.pool(), "node-b").claim("w-b", FLOW, 2, LEASE).await();
        assertEquals(List.of("m-far@1"), taken.stream().map(i -> i.messageId() + "@" + i.attempts()).toList(), "claimed by node-b, its first hand-out");

        assertTrue(settled(start("m-1", null)).succeeded());
        assertTrue(settled(start("m-2", null)).succeeded());
        assertEquals(NODE, row("m-1").lockedBy(), "room 2 on node-a: handed off");
        assertEquals(NODE, row("m-2").lockedBy(), "the plain save gave its slot back: the second is handed off too");
        assertEquals(List.of(), hold.entries("m-far"), "nothing of node-b's item ran on node-a");
        hold.releaseAll();
    }

    /**
     * Review r1 R2 (PgIntake:541-543): a commit in doubt of a save that got no slot — the worker full — leaves a plain row that may
     * have landed; the worker is woken for it, so the next chain end claims it (oldest first, A6). The fake clock never moves: no
     * tick and no lease-end wake (no slot was cancelled) can explain the claim.
     */
    @Test
    void anInDoubtSaveWithNoSlotIsClaimedByTheWakeAtTheNextChainEnd() throws Exception {
        assertTrue(settled(start("m-1", null)).succeeded());
        assertTrue(settled(start("m-2", null)).succeeded());
        until(() -> hold.entries("m-1").size() == 1 && hold.entries("m-2").size() == 1, () -> "both handed off and running; " + hold.events);

        PgWire.FatalReply lost = wire.fatalReplyTo(PgWire.query("COMMIT"));   // the commit lands; the client gets a FATAL instead
        Future<String> doubt = settled(start("m-doubt", null));
        lost.awaitDone();
        assertTrue(doubt.failed(), "a commit in doubt fails the save");
        assertTrue(causes(doubt.cause()).stream().anyMatch(c -> c.contains("57P01")), "with the lost reply: " + causes(doubt.cause()));
        WorkRow w = row("m-doubt");
        assertEquals(null, w.lockedBy(), "room 2 taken: no slot, the row is plain (" + w + ")");
        assertEquals(0, w.attempts());
        assertEquals(1, claims.calls.get(), "full: no claim yet");
        assertEquals(List.of(), List.copyOf(intake.openSaves()), "the doubt resolved by pg_xact_status: landed, no save left open");

        hold.release("m-1");   // a chain end: it claims only while a backlog is known
        until(() -> hold.entries("m-doubt").size() == 1, () -> "the wake's backlog makes m-1's chain end claim the landed row; claims "
                + claims.calls + ", items " + claims.items + ", events " + hold.events);
        assertEquals(List.of("enter m-doubt@1"), hold.entries("m-doubt"), "its first hand-out, by the claim");
        assertEquals(2, claims.calls.get(), "one claim beside the start claim: the chain end's");
        hold.releaseAll();
    }

    /**
     * Review r1 R3 (PgIntake:219): an intake whose queue is of no node hands nothing off, even given the node's workers — its rows
     * are plain, the save succeeds and the worker claims the row normally (its tick, the one case advancing the clock that far).
     */
    @Test
    void anIntakeWhoseQueueHasNoNodeHandsNothingOff() {
        PgIntake anyNode = new PgIntake(a.pool, a.blobs, a.messages, new PgWorkQueue(a.pool), workers);
        Future<String> saved = saved(anyNode, a.arrive("m-plain", bytes(2_000, 13)));
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        WorkRow w = row("m-plain");
        assertEquals(null, w.lockedBy(), "no node to lease to: a plain row (" + w + ")");
        assertEquals(0, w.attempts());
        assertEquals(List.of(), hold.entries("m-plain"), "no slot ran it");

        // the worker's first tick (P2-16b B5): its period, ten minutes, plus the flow's offset floorMod("orders".hashCode(), 600 000 ms):
        // "orders".hashCode() = -1008770331, floorMod 600 000 = 429 669 ms
        rt.clock().advance(Duration.ofMinutes(10).plusMillis(429_669));
        until(() -> hold.entries("m-plain").size() == 1, () -> "claimed at the tick; claims " + claims.calls + ", items " + claims.items);
        assertEquals(List.of("enter m-plain@1"), hold.entries("m-plain"), "its first hand-out, by the claim");
        assertEquals(2, claims.calls.get(), "the start claim and the tick's");
        hold.releaseAll();
    }

    /**
     * Review r1 (PgIntake:539 {@code mayHaveLanded}): a commit refused by an error reply is a known abort — the slot reserved for the
     * save is given back with no lease-end wake: a lease later no claim is issued (the wake's claim is issued on the thread that
     * advances the clock, as {@link #anInDoubtCommitIsClaimedAtItsLeaseEnd} shows), and the room is back.
     */
    @Test
    void aRefusedCommitGivesItsSlotBackWithNoLeaseEndWake() {
        db.pool().query("create function refuse_at_commit() returns trigger language plpgsql as $$ begin "
                + "if new.id = 'm-refused' then raise exception 'commit refused by the test'; end if; return null; end $$").execute().await();
        db.pool().query("create constraint trigger refuse_at_commit after insert on work deferrable initially deferred for each row "
                + "execute function refuse_at_commit()").execute().await();
        Future<String> refused = settled(start("m-refused", null));
        assertTrue(refused.failed(), "the commit was refused");
        assertTrue(causes(refused.cause()).stream().anyMatch(c -> c.contains("commit refused by the test")), "with its own cause: " + causes(refused.cause()));
        assertEquals(0, count(db.pool(), "select count(*) from work where id = 'm-refused'"), "nothing landed");

        rt.clock().advance(LEASE.plusSeconds(1));
        assertEquals(1, claims.calls.get(), "a known abort arms no lease-end wake: no claim a lease later");

        assertTrue(settled(start("m-1", null)).succeeded());
        assertTrue(settled(start("m-2", null)).succeeded());
        assertEquals(NODE, row("m-1").lockedBy(), "handed off");
        assertEquals(NODE, row("m-2").lockedBy(), "the refused save gave its slot back: room 2");
        hold.releaseAll();
    }

    /**
     * Review r3 (R06, PgWorkQueue heads {@code o.flow = $2}): a key is a key of its flow — a row of another flow under the same
     * partition key does not make this flow's save a follower: it heads its key, is written leased to the node and runs by its
     * slot with no claim. (A same-key row of the same flow does stop it: {@link #twoConcurrentSameKeySavesLeaseOnlyOne}.)
     */
    @Test
    void aSameKeyRowOfAnotherFlowDoesNotStopTheSaveFromBeingWrittenLeased() {
        db.pool().query("insert into work(id, flow, step, partition_key) values ('m-other', 'invoices', 1, 'K')").execute().await();
        Future<String> saved = settled(start("m-1", "K"));
        assertTrue(saved.succeeded(), "saved: " + saved.cause());
        WorkRow w = row("m-1");
        assertEquals(NODE, w.lockedBy(), "K of flow invoices is another key: m-1 heads K of " + FLOW + ", leased (" + w + ")");
        assertEquals(1, w.attempts(), "attempts 1: the hand-out is the insert");
        until(() -> hold.entries("m-1").size() == 1, () -> "the slot runs the item; events " + hold.events + ", claims " + claims.calls);
        assertEquals(List.of("enter m-1@1"), hold.entries("m-1"));
        assertEquals(1, claims.calls.get(), "no claim but the start claim");
        assertEquals(null, row("m-other").lockedBy(), "the other flow's row untouched");
        hold.releaseAll();
    }

    /**
     * Review r3 (R12, PgIntake handOff's spilled wake): a save that commits while the worker is full gets no slot and is written
     * plain; the worker is woken for it, so the next chain end claims it (A6). The fake clock never moves: no tick and no lease-end
     * wake can explain the claim. Twin of {@link #anInDoubtSaveWithNoSlotIsClaimedByTheWakeAtTheNextChainEnd} for a known commit.
     */
    @Test
    void aCommittedSaveWithNoSlotIsClaimedByTheWakeAtTheNextChainEnd() {
        assertTrue(settled(start("m-1", null)).succeeded());
        assertTrue(settled(start("m-2", null)).succeeded());
        until(() -> hold.entries("m-1").size() == 1 && hold.entries("m-2").size() == 1, () -> "both handed off and running; " + hold.events);

        Future<String> spilled = settled(start("m-3", null));
        assertTrue(spilled.succeeded(), "saved: " + spilled.cause());
        WorkRow w = row("m-3");
        assertEquals(null, w.lockedBy(), "room 2 taken: no slot, the row is plain (" + w + ")");
        assertEquals(0, w.attempts());
        assertEquals(1, claims.calls.get(), "full: no claim yet");

        hold.release("m-1");   // a chain end: it claims only while a backlog is known
        until(() -> hold.entries("m-3").size() == 1, () -> "the wake's backlog makes m-1's chain end claim the spilled row; claims "
                + claims.calls + ", items " + claims.items + ", events " + hold.events);
        assertEquals(List.of("enter m-3@1"), hold.entries("m-3"), "its first hand-out, by the claim");
        assertEquals(2, claims.calls.get(), "one claim beside the start claim: the chain end's");
        hold.releaseAll();
    }

    /**
     * Review r3 (R13, PgIntake handOff's give-back): a committed save whose key is busy — written plain behind the key's holder —
     * gives its slot back as a known outcome, arming no lease-end wake: a lease later no claim is issued (the wake's claim is issued
     * on the thread that advances the clock, as {@link #anInDoubtCommitIsClaimedAtItsLeaseEnd} shows); the key's holder hands it on.
     */
    @Test
    void aBusyKeyGivesItsSlotBackWithNoLeaseEndWake() {
        assertTrue(settled(start("m-1", "K")).succeeded());
        until(() -> hold.entries("m-1").size() == 1, () -> "m-1 handed off and running; " + hold.events);
        assertTrue(settled(start("m-2", "K")).succeeded());
        WorkRow w = row("m-2");
        assertEquals(null, w.lockedBy(), "K is busy: m-2 written plain (" + w + ")");
        assertEquals(0, w.attempts());

        rt.clock().advance(LEASE.plusSeconds(1));
        assertEquals(1, claims.calls.get(), "a slot given back for a busy key arms no lease-end wake: no claim a lease later");
        assertEquals(List.of(), hold.entries("m-2"), "m-2 waits behind its key's holder");

        hold.release("m-1");   // the key's holder hands K on by the fast path
        until(() -> hold.entries("m-2").size() == 1, () -> "m-2 handed on by m-1's completion; events " + hold.events);
        assertEquals(List.of("enter m-2@1"), hold.entries("m-2"));
        assertEquals(1, claims.calls.get(), "still no claim but the start claim");
        hold.releaseAll();
    }

    /**
     * Review r3 (R14, PgIntake answered): a committed group is handed off once — each slot run or given back exactly once — so a
     * save whose slot runs and one whose slot is given back log no hand-off warning (a slot used twice throws, and the throw is
     * logged at WARNING: {@code PgIntakeHandoffGroupLogIT} shows the capture sees such a line).
     */
    @Test
    void aCommittedGroupIsHandedOffOnceAndLogsNoSlotWarning() {
        java.util.logging.Logger intakeLog = java.util.logging.Logger.getLogger(PgIntake.class.getName());
        NodeJournalIT.Logs logs = new NodeJournalIT.Logs();
        intakeLog.addHandler(logs);
        try {
            assertTrue(settled(start("m-1", "K")).succeeded());
            until(() -> hold.entries("m-1").size() == 1, () -> "m-1's slot ran its item; " + hold.events);
            assertTrue(settled(start("m-2", "K")).succeeded());
            assertEquals(null, row("m-2").lockedBy(), "fixture: K busy, m-2's slot is given back");
            hold.release("m-1");
            until(() -> hold.entries("m-2").size() == 1, () -> "m-2 handed on; events " + hold.events);
            hold.release("m-2");
            until(() -> count(db.pool(), "select count(*) from work") == 0, () -> "both completed; " + hold.events);
            assertEquals(List.of(), logs.warningsNaming("the hand-off slot of message"), "no slot call failed: " + logs.all());
            assertEquals(1, claims.calls.get(), "no claim but the start claim");
        } finally {
            intakeLog.removeHandler(logs);
        }
    }
}
