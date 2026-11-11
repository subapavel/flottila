package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.NackException;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.dlq.Parked;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.Replay;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.record.Meter;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.journal.JournalRef;
import io.flottila.runtime.journal.TestJournals;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.PostgresImage;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09j (F-7, D-062) against Postgres: a parked message's body is made durable — its journal record forced and its
 * {@code body_refs} row committed — before its DLQ row is written, so a DLQ row survives the process that wrote it: the node
 * restarted (its journal reopened, a new store and pool) retries it from the ref Postgres names. A step body that cannot be made durable (above the durable bound) writes no row and
 * leaves the item to its lease; past the attempts bound the message parks its ingress, durable since its delivery (D-058).
 * So does a step body whose key already holds other durable bytes (FIX-35, D-061 amended): its persist fails.
 *
 * <p>Flow {@code orders}, at-least-once: {@code rewrite} (hands on a new body of {@value #H_SIZE} bytes, written into the
 * runtime's tiered store — memory up to {@value #MEMORY_TIER} bytes, the journal's spill above, durable bound {@value #DURABLE_BOUND}),
 * then {@code to partner} (the mock partner, refusing as the case says). Nothing waits on wall time but the bounds of the
 * spins; a lease runs out by an update of its row, never by a wait.
 *
 * <p>The order "body durable, then the DLQ row" as the database saw it: a trigger on {@code dlq} notes, at each insert, whether
 * the {@code body_refs} row of the new row's body key was committed at that moment ({@code dlq_audit}). It refuses nothing:
 * the system under test behaves as without it.
 *
 * <p>CORE-4c2 changed the expectations, each for one reason: a durable body is a journal record named by its {@code body_refs}
 * row, never a {@code bodies} row (D-105; the table is read only, pre-V12); the spool is the journal's spill; "a fresh store"
 * is the same node restarted over its journal, since a body is read on its owner node only until CORE-5 (CORE-3 item 8); and
 * "other durable bytes under the key" (FIX-35) are a kept ref to a record of other bytes, which the persist reads back.
 */
class DlqBodiesIT {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final long MIB = 1L << 20;
    /** Bodies up to this many bytes stay in memory; larger ones spill into the journal. */
    static final long MEMORY_TIER = 1024;
    /** The durable bound: a larger body cannot be made durable. */
    static final long DURABLE_BOUND = 4096;
    static final int SMALL = 300;    // memory
    static final int LARGE = 3000;   // spill, under the durable bound
    static final int ABOVE = 5000;   // spill, above the durable bound
    /** The ingress header that sets the size of the body {@code rewrite} hands on. */
    static final String H_SIZE = "x-size";
    static final Duration LEASE = Duration.ofSeconds(30);
    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool admin;   // the test's own sessions: truncate, counts, rows as another session sees them, lease expiry

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
    final FlowDefinition def = flow();
    /** The bytes every {@code rewrite} run wrote, by key. */
    final Map<BlobKey, Buffer> written = new ConcurrentHashMap<>();
    /** Where the runtime's store held each body {@code rewrite} wrote, right after the write. */
    final Map<BlobKey, Optional<TieredBlobStore.Location>> where = new ConcurrentHashMap<>();
    /** The input of every {@code rewrite} run, read whole as the step saw it, in run order. */
    final List<Input> inputs = new CopyOnWriteArrayList<>();
    final List<NodeStores> nodes = new CopyOnWriteArrayList<>();
    final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
    @TempDir Path data;

    record Input(String messageId, Buffer bytes) {}

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        admin = PgBuilder.pool().connectingTo(connectOptions()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        admin.query("create table dlq_audit (seq bigserial primary key, body_key text not null, body_committed boolean not null)").execute().await();
        admin.query("create function dlq_audit() returns trigger language plpgsql as $$ begin "
                + "insert into dlq_audit(body_key, body_committed) values (new.body_key, exists("
                + "select 1 from body_refs b where b.message_id || '/' || b.step || '/' || b.part = new.body_key)); "
                + "return new; end $$").execute().await();
        admin.query("create trigger dlq_audit before insert on dlq for each row execute function dlq_audit()").execute().await();
    }

    @AfterAll
    static void stop() {
        admin.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void empty() {
        // a case's node journal lives in its own temp dir: its node row and segment rows go with the case
        admin.query("truncate message_keys, messages, dlq, work, bodies, body_refs, journal_segments, journal_nodes, dlq_audit").execute().await();
    }

    @AfterEach
    void close() {
        workers.forEach(FlowWorker::stop);
        nodes.forEach(NodeStores::close);
    }

    static PgConnectOptions connectOptions() {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    static FlowDefinition flow() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 4));
        doc.put("steps", List.of(Map.of("type", "rewrite", "name", "rewrite"), Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        return FlowDefinition.from(doc);
    }

    /** {@code size} printable bytes of a position-dependent pattern (an assertion message stays readable). */
    static Buffer bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) ('a' + Math.floorMod(i * 7 + seed, 26));
        return Buffer.buffer(b);
    }

    /** {@code rewrite}: notes its input, hands on a new body of {@value #H_SIZE} bytes written into the runtime's store, notes where it went. */
    StepFactory rewrite() {
        return new StepFactory() {
            @Override public String type() { return "rewrite"; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) {
                return Stage.of(e -> {
                    inputs.add(new Input(e.id(), e.body().whole()));
                    BlobKey key = BlobKey.body(e.id(), e.step() + 1);
                    Buffer out = bytes(Integer.parseInt(e.header(H_SIZE).orElseThrow()), e.id().hashCode());
                    Body body = res.blobs().write(key, out, res.maxBodyBytes()).await();
                    written.put(key, out);
                    if (res.blobs() instanceof TieredBlobStore tiered) where.put(key, tiered.location(key));
                    return e.next(body);
                });
            }
        };
    }

    /**
     * One process of node {@code node-a} over a pool of its own: its journal under {@code <data>/journal} (reopened by the next
     * process), the runtime's tiered store over it, the Postgres message store, queue and DLQ, and a builder over them with the
     * fake clock and timer.
     */
    final class NodeStores {
        final Pool pool;
        final TestJournals.Opened journal;
        final TieredBlobStore.Config config = new TieredBlobStore.Config(MIB, MEMORY_TIER, 16 * MIB, DURABLE_BOUND);
        final TieredBlobStore blobs;
        final PgMessageStore messages;
        final FlowBuilder builder;
        private boolean closed;

        NodeStores() {
            pool = PgBuilder.pool().connectingTo(connectOptions()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
            try {
                journal = TestJournals.open(pool, Files.createDirectories(data.resolve("journal")), "node-a");
            } catch (Exception e) {
                throw new IllegalStateException("fixture: the node's journal did not open", e);
            }
            blobs = new TieredBlobStore(config, Hasher.sha256(), journal.journal(), journal.tenant(), pool);
            messages = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), blobs, rt.hasher(), Map.of(partner.scheme(), partner));
            builder = new FlowBuilder(runtime, List.of(rewrite()), List.of(), endpoints)
                    .recording(messages, Meter.system()).queue(new PgWorkQueue(pool, "node-a")).dlq(new PgDlq(pool));
            nodes.add(this);
        }

        FlowWorker worker(int maxAttempts) {
            FlowWorker w = builder.worker(def, NO_FILES, "w-" + System.identityHashCode(this), new WorkerPolicy(LEASE, maxAttempts, Duration.ofSeconds(1)));
            workers.add(w);
            return w;
        }

        /** Its process is gone: the message store, the journal and the pool close; its memory is never used again. */
        void close() {
            if (closed) return;
            closed = true;
            messages.close();
            try {
                journal.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            pool.close().await();
        }

        /** The bytes of the record {@code key}'s {@code body_refs} row names, read through this process's journal; empty without a row. */
        Optional<Buffer> kept(BlobKey key) {
            for (Row r : admin.preparedQuery("select segment, position, length from body_refs where message_id = $1 and step = $2 and part = $3")
                    .execute(Tuple.of(key.messageId(), key.step(), key.part())).await()) {
                try {
                    return Optional.of(Buffer.buffer(journal.journal().read(new JournalRef(r.getLong(0), r.getLong(1), r.getLong(2)))));
                } catch (IOException e) {
                    throw new UncheckedIOException("the kept ref of " + key + " does not read", e);
                }
            }
            return Optional.empty();
        }
    }

    /** Message {@code id} through the flow's at-least-once delivery on {@code node}: its ingress persisted, saved and queued; nothing run. */
    String deliver(NodeStores node, String id, int stepBodySize) {
        Body body = node.blobs.write(BlobKey.body(id, 0), ORDER, def.maxBodyBytes()).await();
        Envelope in = Envelope.ingress(id, def.id(), Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml").with(H_SIZE, String.valueOf(stepBodySize)), body);
        assertEquals(new Ack.Accepted(id), node.builder.delivery(def, NO_FILES).apply(in).await(), "fixture: accepted");
        return id;
    }

    static NackException refused() {
        return new NackException("order refused", false);
    }

    /** Whether {@code key} has a {@code body_refs} row as another session sees it. */
    static boolean hasRef(BlobKey key) {
        return admin.preparedQuery("select 1 from body_refs where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(key.messageId(), key.step(), key.part())).await().size() == 1;
    }

    /** The DLQ row of {@code messageId} as another session sees it. */
    static Optional<Parked> dlqRow(String messageId) {
        return new PgDlq(admin).get(messageId).await();
    }

    /** For every DLQ insert so far, in order: whether its body's {@code body_refs} row was committed when the insert ran. */
    static List<Boolean> bodyCommittedAtDlqInsert() {
        List<Boolean> seen = new java.util.ArrayList<>();
        for (Row r : admin.query("select body_committed from dlq_audit order by seq").execute().await()) seen.add(r.getBoolean(0));
        return seen;
    }

    static long count(String from) {
        return admin.query("select count(*) from " + from).execute().await().iterator().next().getLong(0);
    }

    /** Backends of the database waiting on a lock. */
    static long lockWaiters() {
        return admin.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock'")
                .execute(Tuple.of(pg.getDatabaseName())).await().iterator().next().getLong(0);
    }

    /** The lease of {@code messageId}'s item runs out now (the queue measures leases by the database clock). */
    static void expireLease(String messageId) {
        assertEquals(1, admin.preparedQuery("update work set locked_until = now() - interval '1 second' where id = $1").execute(Tuple.of(messageId)).await().rowCount(),
                "fixture: one item to expire");
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    @Test
    void aBodyParkedOnOneNodeIsRetriedFromAFreshStoreAfterARestart() {
        NodeStores first = new NodeStores();
        BlobKey inMemory = BlobKey.body(deliver(first, "dlq-memory", SMALL), 1);
        BlobKey spooled = BlobKey.body(deliver(first, "dlq-spool", LARGE), 1);
        partner.failNext(2, DlqBodiesIT::refused);

        assertEquals(2, first.worker(5).poll().await(), "both messages claimed and run");
        assertEquals(2, partner.sent().size(), "fixture: both reached the partner, which refused");
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), where.get(inMemory), "fixture: one step body was held in memory");
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), where.get(spooled), "fixture: the other was spilled");
        for (BlobKey k : List.of(inMemory, spooled)) {
            assertEquals(Optional.of(k), dlqRow(k.messageId()).map(Parked::bodyKey), "fixture: parked at step 2 by its input's key " + k);
            assertEquals(Optional.of(written.get(k)), first.kept(k), "the parked body is durable: another session sees its ref, which reads it, " + k);
        }
        assertEquals(List.of(true, true), bodyCommittedAtDlqInsert(), "each DLQ row was inserted with its body's ref already committed");
        assertEquals(0, count("work"), "both parked: their items completed");
        assertEquals(0, count("bodies"), "no bodies row: the parked bodies are journal records");
        first.close();   // the process is gone: its memory, its index, its pool

        NodeStores restarted = new NodeStores();
        Replay replay = restarted.builder.replay(def, NO_FILES);
        for (BlobKey k : List.of(inMemory, spooled)) {
            assertEquals(Optional.empty(), restarted.blobs.location(k), "the fresh store holds nothing locally: " + k);
            int before = partner.sentTo("partner").size();
            assertDoesNotThrow(() -> replay.retry(k.messageId()), "retried on the fresh store: " + k);
            List<Envelope> sent = partner.sentTo("partner");
            assertEquals(before + 1, sent.size(), "the retry reached the partner: " + k);
            Envelope retried = sent.get(before);
            assertEquals(Optional.of(k.messageId()), retried.get(Envelope.REPLAY_OF), "a retry of the parked message");
            assertEquals(written.get(k), assertDoesNotThrow(() -> retried.body().whole(), "the retried input reads: " + k),
                    "the retried input, byte-equal to the parked body, read from the journal by the ref its key has: " + k);
            assertEquals(Optional.empty(), dlqRow(k.messageId()), "the DLQ row is gone after the retry: " + k);
        }
        assertEquals(0, count("dlq"));
    }

    /**
     * Another session holds an uncommitted {@code body_refs} row under the parked body's key: the park's persist waits for it,
     * and no DLQ row is visible while it waits. Once that session rolls back, the body's ref commits, then the DLQ row.
     * (CORE-4c2: was an uncommitted {@code bodies} row; the persist now inserts the ref.)
     */
    @Test
    void theDlqRowCommitsOnlyAfterTheBodyRef() {
        NodeStores node = new NodeStores();
        String id = deliver(node, "dlq-held", SMALL);
        BlobKey parkedBody = BlobKey.body(id, 1);
        partner.failNext(1, DlqBodiesIT::refused);
        FlowWorker worker = node.worker(5);
        Future<Integer> round;
        PgConnection holder = PgConnection.connect(vertx, connectOptions()).await();
        try {
            Transaction held = holder.begin().await();
            holder.preparedQuery("insert into body_refs(message_id, step, part, node, segment, position, length) values ($1, $2, $3, 'node-a', 999, 0, 1)")
                    .execute(Tuple.of(id, 1, "body")).await();   // another session's, rolled back
            round = worker.poll();
            until(() -> lockWaiters() == 1 || count("dlq") > 0, "the park's persist waits on the other session's uncommitted row (or a DLQ row shows)");
            assertEquals(0, count("dlq"), "no DLQ row while the parked body's row is not committed");
            assertEquals(1, lockWaiters(), "the persist's insert waits");
            assertEquals(1, partner.sent().size(), "fixture: the run reached the partner, which refused");
            assertFalse(round.isComplete(), "the run waits for its park: " + round);
            assertEquals(1, count("work"), "the item is not completed meanwhile");
            held.rollback().await();
        } finally {
            holder.close().await();
        }
        assertEquals(1, round.await(), "the round ends once the park is durable");
        assertEquals(Optional.of(written.get(parkedBody)), node.kept(parkedBody), "the body's ref committed, naming the step's bytes");
        assertEquals(Optional.of(parkedBody), dlqRow(id).map(Parked::bodyKey), "and the DLQ row, by that key");
        assertEquals(List.of(true), bodyCommittedAtDlqInsert(), "the DLQ row was inserted once, after the body's ref had committed");
        assertEquals(0, count("work"), "parked: the item completed");
    }

    @Test
    void anExhaustedItemParksItsIngressWhichAFreshStoreRetries() {
        int maxAttempts = 2;
        NodeStores first = new NodeStores();
        String id = deliver(first, "dlq-exhausted", ABOVE);
        BlobKey stepBody = BlobKey.body(id, 1);
        partner.failNext(maxAttempts, DlqBodiesIT::refused);
        FlowWorker worker = first.worker(maxAttempts);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            assertEquals(1, worker.poll().await(), "attempt " + attempt + " claimed");
            assertEquals(attempt, partner.sent().size(), "attempt " + attempt + " ran and the partner refused");
            assertEquals(0, count("dlq"), "attempt " + attempt + ": no DLQ row, the step body is above the durable bound");
            assertFalse(hasRef(stepBody), "attempt " + attempt + ": and no ref for it");
            assertEquals(0, worker.poll().await(), "attempt " + attempt + ": the item is left to its lease");
            expireLease(id);
        }
        assertEquals(1, worker.poll().await(), "the claim past the bound");
        assertEquals(maxAttempts, partner.sent().size(), "past the bound nothing runs");
        Parked parked = dlqRow(id).orElseThrow(() -> new AssertionError("no DLQ row for " + id + " past the bound"));
        assertEquals(1, parked.step(), "parked at step 1");
        assertEquals(BlobKey.body(id, 0), parked.bodyKey(), "by its ingress");
        assertTrue(parked.error().contains("AttemptsExhaustedException"), parked.error());
        assertEquals(Optional.of(ORDER), first.kept(BlobKey.body(id, 0)), "the ingress is durable, since its delivery");
        assertEquals(List.of(true), bodyCommittedAtDlqInsert(), "one DLQ insert, its body's ref committed");
        assertEquals(0, count("work"), "parked: the item completed");
        first.close();

        NodeStores restarted = new NodeStores();
        int runs = inputs.size();
        Envelope out = assertDoesNotThrow(() -> restarted.builder.replay(def, NO_FILES).retry(id), "retried on a fresh store from step 1");
        assertEquals(Optional.of(id), out.get(Envelope.REPLAY_OF));
        assertEquals(runs + 1, inputs.size(), "step 1 ran once more");
        Input retried = inputs.get(runs);
        assertEquals(out.id(), retried.messageId(), "as the retry's message");
        assertEquals(ORDER, retried.bytes(), "its input is the ingress, byte-equal, read by the restarted store through its ref");
        assertEquals(maxAttempts + 1, partner.sent().size(), "and it reached the partner");
        assertEquals(Optional.empty(), dlqRow(id), "the DLQ row is gone after the retry");
    }

    @Test
    void aStepBodyAboveTheBoundWritesNoRowAndLeavesTheItemQueued() {
        NodeStores node = new NodeStores();
        String id = deliver(node, "dlq-above", ABOVE);
        BlobKey stepBody = BlobKey.body(id, 1);
        partner.failNext(2, DlqBodiesIT::refused);
        FlowWorker worker = node.worker(5);

        assertEquals(1, worker.poll().await(), "claimed and run");
        assertEquals(1, partner.sent().size(), "fixture: the run reached the partner, which refused");
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), where.get(stepBody), "fixture: the step body was spilled");
        assertEquals(0, count("dlq"), "no DLQ row: the parked body is above the durable bound");
        assertEquals(List.of(), bodyCommittedAtDlqInsert(), "no DLQ insert was even tried");
        assertFalse(hasRef(stepBody), "and no ref for it");
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), node.blobs.location(stepBody), "the step body stays where it was");
        assertEquals(1, count("work where id = '" + id + "' and held_at is null and attempts = 1"), "the item stays queued, not held");
        assertEquals(0, worker.poll().await(), "left to its lease: nobody claims it meanwhile");

        expireLease(id);
        assertEquals(1, worker.poll().await(), "once its lease ran out it is claimed again");
        assertEquals(2, partner.sent().size(), "and runs again");
        assertEquals(0, count("dlq"));
        assertEquals(1, count("work"));
    }

    /**
     * FIX-35 (D-061 amended), the done-when witness on Postgres: the parked body's key already holds other durable bytes — a
     * kept ref an earlier run left whose DLQ row was lost, naming a record of other bytes of the same size — so the park's
     * persist, which reads that record back, fails: no DLQ row, the ref unchanged, the step body local, the item left to its
     * lease; past the attempts bound the message parks its ingress, durable since its delivery. (CORE-4c2: the earlier bytes
     * were a {@code bodies} row; now a record persisted through a second store over the node's journal, its index the node
     * store's own.)
     */
    @Test
    void aStepBodyWhoseKeyHoldsOtherDurableBytesEndsInTheIngressPark() {
        int maxAttempts = 2;
        NodeStores node = new NodeStores();
        String id = deliver(node, "dlq-conflict", SMALL);
        BlobKey stepBody = BlobKey.body(id, 1);
        Buffer earlier = bytes(SMALL, id.hashCode() + 1);   // the same size as this run's step body, other bytes
        assertFalse(bytes(SMALL, id.hashCode()).equals(earlier), "fixture: other bytes than every run of rewrite writes");
        TieredBlobStore earlierRun = new TieredBlobStore(node.config, Hasher.sha256(), node.journal.journal(), node.journal.tenant(), node.pool);
        earlierRun.write(stepBody, earlier, MIB).await();
        earlierRun.persist(stepBody).await();
        assertEquals(Optional.of(earlier), node.kept(stepBody), "fixture: the key's ref names a record of the earlier bytes");
        partner.failNext(maxAttempts, DlqBodiesIT::refused);
        FlowWorker worker = node.worker(maxAttempts);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            assertEquals(1, worker.poll().await(), "attempt " + attempt + " claimed");
            assertEquals(attempt, partner.sent().size(), "attempt " + attempt + " ran and the partner refused");
            assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), where.get(stepBody), "fixture: attempt " + attempt + "'s step body was held in memory");
            assertEquals(0, count("dlq"), "attempt " + attempt + ": no DLQ row, the parked body's key holds other durable bytes");
            assertEquals(List.of(), bodyCommittedAtDlqInsert(), "attempt " + attempt + ": no DLQ insert was even tried");
            assertEquals(Optional.of(earlier), node.kept(stepBody), "attempt " + attempt + ": the ref keeps naming the earlier bytes");
            assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), node.blobs.location(stepBody), "attempt " + attempt + ": the step body stays local");
            assertEquals(written.get(stepBody), node.blobs.read(stepBody).await(), "attempt " + attempt + ": and reads this run's bytes");
            assertEquals(0, worker.poll().await(), "attempt " + attempt + ": the item is left to its lease");
            expireLease(id);
        }
        assertEquals(1, worker.poll().await(), "the claim past the bound");
        assertEquals(maxAttempts, partner.sent().size(), "past the bound nothing runs");
        Parked parked = dlqRow(id).orElseThrow(() -> new AssertionError("no DLQ row for " + id + " past the bound"));
        assertEquals(1, parked.step(), "parked at step 1");
        assertEquals(BlobKey.body(id, 0), parked.bodyKey(), "by its ingress");
        assertTrue(parked.error().contains("AttemptsExhaustedException"), parked.error());
        assertEquals(Optional.of(ORDER), node.kept(BlobKey.body(id, 0)), "the ingress is durable, since its delivery");
        assertEquals(List.of(true), bodyCommittedAtDlqInsert(), "one DLQ insert, by the ingress, its ref committed");
        assertEquals(Optional.of(earlier), node.kept(stepBody), "the step body's ref never changed");
        assertEquals(0, count("bodies"), "and no bodies row was written");
        assertEquals(0, count("work"), "parked: the item completed");
    }
}
