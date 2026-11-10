package io.flottila.runtime.journal;

import io.flottila.api.Envelope;
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
import io.flottila.runtime.store.PgIntake;
import io.flottila.testkit.FakeRuntime;
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

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final String NODE = "node-a";
    static Vertx vertx;

    @TempDir Path tmp;
    CaseDatabase db;
    PgWire wire;
    Pool relayed;
    JournalWorld a;
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
}
