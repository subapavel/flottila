package dev.rail8.runtime.flow;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Hasher;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.core.batch.BatchingWriter;
import dev.rail8.core.flow.Delivery;
import dev.rail8.core.flow.FlowBuilder;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.FlowWorker;
import dev.rail8.core.flow.WorkerPolicy;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.Snapshot;
import dev.rail8.runtime.blob.TieredBlobStore;
import dev.rail8.runtime.db.Migrations;
import dev.rail8.runtime.journal.JournalRef;
import dev.rail8.runtime.journal.TestJournals;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.runtime.store.PgMessageStore;
import dev.rail8.runtime.time.VertxTimer;
import dev.rail8.testkit.FakeRuntime;
import dev.rail8.testkit.PostgresImage;
import dev.rail8.testkit.RecordingComponent;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09k2 (D-064, D-066, F-10) against Postgres: every kept step body of a run is made durable — its record forced in the
 * node's journal and its {@code body_refs} row committed — when the run ends and before its row is written, so every step's body reads
 * after a restart: a new tiered store over the node's journal reopened, a new pool, a new message store. A
 * row's {@code body_key} always names a durable body; a body above the durable bound is recorded metadata-only; a persisted
 * body's local copy is gone once the run ended.
 *
 * <p>CORE-4c2 changed the expectations, each for one reason: a durable body is a journal record named by a {@code body_refs}
 * row, no {@code bodies} row (D-105; {@code bodies} is read only, pre-V12); the spool is the journal's spill (no spool
 * directory, no spool key); and "a fresh node" is now the same node restarted over its journal — a body is read on its owner
 * node only until CORE-5 adds peer reads (CORE-3 item 8), so a node with another id and journal could not read it.
 *
 * <p>Flow {@code orders}: {@code one} (hands on a new body of {@value #SMALL} bytes, held in memory), {@code two} (a new body
 * of {@value #LARGE} bytes, spilled), {@code three} (notes its input, hands it on). Each writer writes under the envelope's
 * {@link Envelope#nextKey()} into the runtime's tiered store — memory up to {@value #MEMORY_TIER} bytes, the journal's spill
 * above, durable bound {@value #DURABLE_BOUND}. Nothing waits on wall time but the bounds of the spins.
 */
class StepBodiesIT {

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
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration STOP_BOUND = Duration.ofHours(1);   // on the fake timer: never reached, a stop waits for its flights
    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool admin;   // the test's own sessions: truncate, counts, rows as another session sees them, lease expiry

    static final Map<String, Object> ONE = Map.of("type", "small", "name", "one");
    static final Map<String, Object> TWO = Map.of("type", "large", "name", "two");
    static final Map<String, Object> THREE = Map.of("type", "probe", "name", "three");
    static final Map<String, Object> HUGE = Map.of("type", "above", "name", "huge");
    static final Map<String, Object> STAMP = Map.of("type", "stamp", "name", "stamp");
    static final Map<String, Object> CRASH_FIRST = Map.of("type", "crash-first", "name", "crash-first");

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
    /** The bytes every writer run wrote, by key. */
    final Map<BlobKey, Buffer> written = new ConcurrentHashMap<>();
    /** Where the runtime's store held each body a writer wrote, right after the write. */
    final Map<BlobKey, Optional<TieredBlobStore.Location>> where = new ConcurrentHashMap<>();
    /** Every step input as the step read it whole, in run order: step name, message id, bytes. */
    final List<Input> inputs = new CopyOnWriteArrayList<>();
    /** The bytes each {@code stamp} run handed on, in run order. */
    final List<Buffer> stamped = new CopyOnWriteArrayList<>();
    final List<NodeStores> nodes = new CopyOnWriteArrayList<>();
    final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
    @TempDir Path data;

    record Input(String step, String messageId, Buffer bytes) {}

    /** What a crashed step throws: an {@link Error}, past every RuntimeException handler — the run neither completes nor parks. */
    static final class Crash extends Error {
        Crash(String message) { super(message); }
    }

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        admin = PgBuilder.pool().connectingTo(connectOptions()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
        admin.query("truncate message_keys, messages, dlq, work, bodies, body_refs, journal_segments, journal_nodes").execute().await();
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

    static FlowDefinition flow(String mode, List<Map<String, Object>> steps) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", mode));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 4));
        doc.put("steps", steps);
        return FlowDefinition.from(doc);
    }

    /** {@code size} printable bytes of a position-dependent pattern (an assertion message stays readable). */
    static Buffer bytes(int size, int seed) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) ('a' + Math.floorMod(i * 7 + seed, 26));
        return Buffer.buffer(b);
    }

    static StepFactory factory(String type, java.util.function.BiFunction<StepConfig, StepResources, Stage> make) {
        return new StepFactory() {
            @Override public String type() { return type; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step, res); }
        };
    }

    /** A writer: notes its input, hands on a new body of {@code size} bytes written under the envelope's next key, notes where it went. */
    StepFactory writer(String type, int size) {
        return factory(type, (step, res) -> Stage.of(e -> {
            inputs.add(new Input(step.name(), e.id(), e.body().whole()));
            Buffer out = bytes(size, (e.id() + step.name()).hashCode());
            Body body = res.blobs().write(e.nextKey(), out, res.maxBodyBytes()).await();
            written.put(body.key(), out);
            if (res.blobs() instanceof TieredBlobStore tiered) where.put(body.key(), tiered.location(body.key()));
            return e.next(body);
        }));
    }

    List<StepFactory> steps() {
        return List.of(writer("small", SMALL), writer("large", LARGE), writer("above", ABOVE),
                factory("probe", (step, res) -> Stage.of(e -> {
                    inputs.add(new Input(step.name(), e.id(), e.body().whole()));
                    return e;
                })),
                // not deterministic: each run hands on bytes of its own, in memory, under the envelope's next key
                factory("stamp", (step, res) -> Stage.of(e -> {
                    Buffer out = Buffer.buffer("<run n='" + (stamped.size() + 1) + "'/>");
                    stamped.add(out);
                    Body body = res.blobs().write(e.nextKey(), out, res.maxBodyBytes()).await();
                    written.put(body.key(), out);
                    return e.next(body);
                })),
                // the first run of a message crashes, a rerun (it carries its attempt) passes, and so does a replay (a new message, D-066)
                factory("crash-first", (step, res) -> Stage.of(e -> {
                    if (e.get(Envelope.ATTEMPT).isEmpty() && e.get(Envelope.REPLAY_OF).isEmpty()) throw new Crash("crashed at " + step.name() + " on the first run");
                    return e;
                })));
    }

    /**
     * One process of node {@code node-a} over a pool of its own: its journal under {@code <data>/journal} (reopened by the next
     * process), the runtime's tiered store over it (memory, the journal, its refs in Postgres), the Postgres message store,
     * queue and DLQ, and a builder over them with the fake clock and timer.
     */
    final class NodeStores {
        final Pool pool;
        final TestJournals.Opened journal;
        final Path journalDir;
        final TieredBlobStore blobs;
        final PgMessageStore messages;
        final FlowBuilder builder;
        private boolean closed;

        NodeStores() {
            pool = PgBuilder.pool().connectingTo(connectOptions()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
            journalDir = data.resolve("journal");
            try {
                journal = TestJournals.open(pool, Files.createDirectories(journalDir), "node-a");
            } catch (Exception e) {
                throw new IllegalStateException("fixture: the node's journal did not open", e);
            }
            blobs = new TieredBlobStore(new TieredBlobStore.Config(MIB, MEMORY_TIER, 16 * MIB, DURABLE_BOUND), Hasher.sha256(),
                    journal.journal(), journal.tenant(), pool);
            messages = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), blobs, rt.hasher(), Map.of(partner.scheme(), partner));
            builder = new FlowBuilder(runtime, steps(), List.of(), endpoints)
                    .recording(messages, Meter.system()).queue(new PgWorkQueue(pool, "node-a")).dlq(new PgDlq(pool));
            nodes.add(this);
        }

        FlowWorker worker(FlowDefinition def, int maxAttempts) {
            FlowWorker w = builder.retries(new dev.rail8.core.flow.RetryPolicy(maxAttempts - 1, Duration.ofHours(1), Duration.ofHours(1), 1.0))   // CORE-14a: the bound is count + 1
                    .worker(def, NO_FILES, "w-" + System.identityHashCode(this), new WorkerPolicy(LEASE, Duration.ofSeconds(1)));
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

    /** A stage-0 envelope {@code id} of flow {@code orders}, its body written into {@code node}'s store. */
    static Envelope ingress(NodeStores node, String id) {
        Body body = node.blobs.write(BlobKey.body(id, 0), ORDER, 65_536).await();
        return Envelope.ingress(id, "orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
    }

    /**
     * Message {@code id} through {@code def} on {@code node} and to its end: at-least-once — accepted, then claimed and run by
     * a worker (the poll completes once the item settled); sync — answered, then the delivery stopped, which completes once
     * its flight landed.
     */
    void run(NodeStores node, FlowDefinition def, String id) {
        Envelope in = ingress(node, id);
        Delivery delivery = node.builder.delivery(def, NO_FILES);
        if ("sync".equals(def.deliveryMode())) {
            assertInstanceOf(Ack.Reply.class, delivery.apply(in).await(), "fixture: the sync delivery replied");
            delivery.stop(STOP_BOUND).await();   // the flight lands once its run is over, its trail included
        } else {
            assertEquals(new Ack.Accepted(id), delivery.apply(in).await(), "fixture: accepted");
            assertEquals(1, node.worker(def, 5).poll().await(), "fixture: the item was claimed and run");
            assertEquals(0, count("work where id = '" + id + "'"), "fixture: the run ended and its item completed");
        }
        rowsOf(id, def.steps().size() + 1);
    }

    /** Spins until message {@code id} has {@code n} rows as another session sees them (a row write is never what a case is red for). */
    static void rowsOf(String id, int n) {
        until(() -> count("messages where id = '" + id + "'") == n, () -> "fixture: " + n + " rows of " + id + ", found " + stepRows(id));
    }

    /** Message {@code id}'s rows as another session sees them, one {@code step_name/attempt/outcome} each, by attempt, then step. */
    static List<String> stepRows(String id) {
        List<String> rows = new java.util.ArrayList<>();
        for (Row r : admin.preparedQuery("select step_name, attempt, outcome from messages where id = $1 order by attempt, step").execute(Tuple.of(id)).await()) {
            rows.add(r.getString(0) + "/" + r.getInteger(1) + "/" + r.getString(2));
        }
        return rows;
    }

    /** {@code bodies} rows: none for a new message (CORE-4c2: the table is read only, pre-V12). */
    static long bodiesRows() {
        return count("bodies");
    }

    /** The {@code body_key} of message {@code id}'s row at {@code step} as another session sees it; empty for metadata-only. */
    static Optional<String> rowBodyKey(String id, int step) {
        for (Row r : admin.preparedQuery("select body_key from messages where id = $1 and step = $2").execute(Tuple.of(id, step)).await()) {
            return Optional.ofNullable(r.getString(0));
        }
        return fail("no row for message " + id + " at step " + step);
    }

    static long count(String from) {
        return admin.query("select count(*) from " + from).execute().await().iterator().next().getLong(0);
    }

    /** Rows whose {@code body_key} names no {@code body_refs} row: a row that promises bytes nobody can read. */
    static long rowsWithoutTheirBody() {
        return count("messages m where m.body_key is not null and not exists ("
                + "select 1 from body_refs b where b.message_id || '/' || b.step || '/' || b.part = m.body_key)");
    }

    /** The lease of {@code messageId}'s item runs out now (the queue measures leases by the database clock). */
    static void expireLease(String messageId) {
        assertEquals(1, admin.preparedQuery("update work set locked_until = now() - interval '1 second' where id = $1").execute(Tuple.of(messageId)).await().rowCount(),
                "fixture: one item to expire");
    }

    List<Buffer> inputsOf(String step, String messageId) {
        return inputs.stream().filter(i -> i.step().equals(step) && i.messageId().equals(messageId)).map(Input::bytes).toList();
    }

    static long filesUnder(Path dir) {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after 20 s naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        until(condition, () -> what);
    }

    /** As {@link #until(BooleanSupplier, String)}; {@code what} is read at the failure, so it can name the state found then. */
    static void until(BooleanSupplier condition, java.util.function.Supplier<String> what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what.get());
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /**
     * The done-when of P1-09k: a message ran through {@code one} (memory), {@code two} (spill) and {@code three}; the node's
     * process is gone. In the next process — its journal reopened, a new tiered store, new pool, new message store — step 2's
     * and step 1's rows name their bodies by key, and the new store reads each from the journal by the ref Postgres names for
     * that key, byte for byte: step 2's is the input step 3 ran on, step 1's the body that was held in memory. (CORE-4c2: was
     * "on a fresh node", read from {@code bodies}; a body is read on its owner node only until CORE-5.)
     */
    @ParameterizedTest
    @ValueSource(strings = {"at-least-once", "sync"})
    void stepBodiesAreDurableAndReadByteForByteThroughTheirRowsAfterARestart(String mode) {
        FlowDefinition def = flow(mode, List.of(ONE, TWO, THREE));
        NodeStores a = new NodeStores();
        String id = "sb-" + mode;
        run(a, def, id);
        BlobKey stepOne = BlobKey.body(id, 1);
        BlobKey stepTwo = BlobKey.body(id, 2);
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), where.get(stepOne), "fixture: step 1's body was held in memory");
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), where.get(stepTwo), "fixture: step 2's body was spilled");
        List<Buffer> original = inputsOf("three", id);
        assertEquals(List.of(written.get(stepTwo)), original, "fixture: step 3 ran once, on step 2's output");
        assertEquals(Optional.of(stepTwo.messageId() + "/2/body"), rowBodyKey(id, 2), "fixture: step 2's row keeps its body by key");
        assertEquals(0, bodiesRows(), "no bodies row: the bytes are in the journal only");
        a.close();   // the process is gone: its memory, its index, its pool

        NodeStores b = new NodeStores();
        assertEquals(Optional.empty(), b.blobs.location(stepTwo), "fixture: the restarted store holds nothing locally");
        assertEquals(Optional.empty(), b.blobs.location(stepOne), "fixture: nor step 1's body");
        List<Snapshot> rows = b.messages.timeline(id).await();
        assertEquals(Optional.of(stepTwo), rowAt(rows, 2).bodyKey(), "the restarted message store reads step 2's row with its body key");
        assertEquals(original.get(0), b.blobs.read(stepTwo).await(), "the restarted store reads the original step-3 input, byte for byte, from the journal");
        assertEquals(Optional.of(stepOne), rowAt(rows, 1).bodyKey(), "and step 1's row with its key");
        assertEquals(written.get(stepOne), b.blobs.read(stepOne).await(), "and step 1's in-memory body, byte for byte, from the journal");
    }

    /** The one row at {@code step} in {@code rows}. */
    static Snapshot rowAt(List<Snapshot> rows, int step) {
        List<Snapshot> at = rows.stream().filter(s -> s.step() == step).toList();
        assertEquals(1, at.size(), "one row at step " + step + ": " + rows);
        return at.get(0);
    }

    /**
     * D-066 with the trail: the first run of a message whose {@code stamp} step is not deterministic crashes after its stamp,
     * so its item is claimed again. The first run's trail made its stamp body durable, under the plain key; the rerun stamps
     * other bytes under a key of its own ({@code body~2}). Each attempt keeps its own stamp row (FIX-175: was "the first row
     * wins"), each durable with its run's bytes, and after a restart the message store reads both rows and the new store reads
     * the bytes the latest attempt's row describes (FIX-175 P5: was the first run's). (CORE-4c2: was "on a fresh node", the
     * first run's bytes in its {@code bodies} row; now its kept ref, read back from the journal.)
     */
    @Test
    void aRerunOfAStampingStepKeepsEachAttemptsRowAndAfterARestartTheLatestRowReadsAttemptTwosBytes() {
        FlowDefinition def = flow("at-least-once", List.of(STAMP, CRASH_FIRST, THREE));
        NodeStores a = new NodeStores();
        String id = "sb-rerun";
        Envelope in = ingress(a, id);
        assertEquals(new Ack.Accepted(id), a.builder.delivery(def, NO_FILES).apply(in).await(), "fixture: accepted");
        FlowWorker worker = a.worker(def, 5);
        assertEquals(1, worker.poll().await(), "attempt 1 claimed");
        assertEquals(0, worker.poll().await(), "fixture: a barrier — joins the follow-up claim attempt 1's end started; nothing is due");
        assertEquals(1, count("work"), "fixture: attempt 1 crashed, its item is left to its lease");
        expireLease(id);
        assertEquals(1, worker.poll().await(), "attempt 2 claimed");
        assertEquals(0, count("work"), "fixture: attempt 2 went through, its item completed");
        assertEquals(2, stamped.size(), "fixture: stamp ran twice");
        assertNotEquals(stamped.get(0), stamped.get(1), "fixture: the rerun stamped other bytes");
        BlobKey first = BlobKey.body(id, 1);
        // FIX-175 (P2, P5) changed this expectation: each attempt keeps its own stamp row, each naming its own bytes.
        // FIX-212: the rows are six, not five — attempt 1's crash-first row too, outcome failed (a step that throws is recorded, D-140);
        // both trails closed before their polls returned, the flush is the writer's own signal that nothing is left queued: one read.
        a.messages.flush().await();
        assertEquals(List.of("ingress/1/ok", "stamp/1/ok", "crash-first/1/failed", "stamp/2/ok", "crash-first/2/ok", "three/2/ok"), stepRows(id),
                "attempt 1: ingress, stamp, crash-first (failed); attempt 2: stamp, crash-first, three — as (step/attempt/outcome)");
        BlobKey second = new BlobKey(id, 1, "body~2");
        assertEquals(stamped.get(1), written.get(second), "fixture: the rerun wrote under its attempt's key");
        assertEquals(List.of(id + "/1/body", id + "/1/body~2"), stampRowKeys(id), "attempt 1's stamp row under the plain key, attempt 2's under its own");
        assertEquals(Optional.of(stamped.get(0)), a.kept(first), "the first run's stamp body is durable with the first run's bytes");
        assertEquals(Optional.of(stamped.get(1)), a.kept(second), "the rerun's stamp body is durable with the rerun's bytes");
        a.close();

        NodeStores b = new NodeStores();
        List<Snapshot> stampRows = b.messages.timeline(id).await().stream().filter(s -> s.step() == 1)
                .sorted(java.util.Comparator.comparingInt(Snapshot::attempt)).toList();
        assertEquals(List.of(1, 2), stampRows.stream().map(Snapshot::attempt).toList(), "the restarted message store reads both attempts' stamp rows");
        assertEquals(Optional.of(second), stampRows.get(1).bodyKey(), "the latest attempt's row names the rerun's key");
        assertEquals(stamped.get(1), b.blobs.read(second).await(), "and the restarted store reads the bytes that row describes, the rerun's, from the journal");
        assertEquals(Optional.of(first), stampRows.get(0).bodyKey(), "attempt 1's row names the plain key");
        assertEquals(stamped.get(0), b.blobs.read(first).await(), "which still reads the first run's bytes");
    }

    /** The {@code body_key} of each stamp (step 1) row of {@code id}, by attempt. */
    static List<String> stampRowKeys(String id) {
        List<String> keys = new java.util.ArrayList<>();
        for (Row r : admin.preparedQuery("select body_key from messages where id = $1 and step = 1 order by attempt").execute(Tuple.of(id)).await()) keys.add(r.getString(0));
        return keys;
    }

    /**
     * The invariant behind it all: after runs of both modes over memory and spilled bodies, every row's {@code body_key} has its
     * {@code body_refs} row (CORE-4c2: was its {@code bodies} row) and no {@code bodies} row is written.
     */
    @Test
    void everyKeptBodyKeyHasItsBodyRef() {
        NodeStores a = new NodeStores();
        for (String mode : List.of("at-least-once", "sync")) {
            FlowDefinition def = flow(mode, List.of(ONE, TWO, THREE));
            for (int i = 1; i <= 3; i++) run(a, def, "sb-" + mode + "-" + i);
        }
        assertEquals(6 * 4, count("messages where body_key is not null"), "witness: every row of the six messages (ingress and three steps) keeps its body by key");
        assertEquals(0, rowsWithoutTheirBody(), "no row names a body key without a durable body behind it");
        assertEquals(0, bodiesRows(), "and no bodies row: every durable body is a journal record");
    }

    /**
     * Above the durable bound a step body cannot be made durable: its row is recorded metadata-only (hash and size, no key),
     * the rows around it keep theirs, the message is done with as usual, and the message store reads that row back
     * metadata-only.
     */
    @ParameterizedTest
    @ValueSource(strings = {"at-least-once", "sync"})
    void aBodyAboveTheBoundIsRecordedMetadataOnly(String mode) {
        FlowDefinition def = flow(mode, List.of(ONE, HUGE, THREE));
        NodeStores a = new NodeStores();
        String id = "sb-huge-" + mode;
        run(a, def, id);
        BlobKey huge = BlobKey.body(id, 2);
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), where.get(huge), "fixture: the huge body was spilled");
        assertEquals(Optional.empty(), rowBodyKey(id, 2), "the row of the step whose body is above the bound has no body key");
        long size = admin.preparedQuery("select body_size from messages where id = $1 and step = 2").execute(Tuple.of(id)).await().iterator().next().getLong(0);
        assertEquals(ABOVE, size, "its size is still recorded");
        assertEquals(Optional.empty(), a.kept(huge), "no ref was written for it");
        assertEquals(0, bodiesRows(), "nor a bodies row");
        assertEquals(Optional.of(id + "/1/body"), rowBodyKey(id, 1), "the row before it keeps its key");
        assertEquals(Optional.empty(), rowBodyKey(id, 3), "step 3 handed the huge body on unchanged: its row names no key either");
        assertEquals(0, rowsWithoutTheirBody(), "no row names a body that is not durable");
        Snapshot hugeRow = rowAt(a.messages.timeline(id).await(), 2);
        assertEquals(Optional.empty(), hugeRow.bodyKey(), "the message store reads that row back metadata-only: no body key");
        assertEquals(ABOVE, hugeRow.bodySize(), "and its size");
    }

    /**
     * A run's step bodies leave the node's memory and spill once its trail made them durable: nothing of the run stays local
     * (CORE-4c2: durable is a kept ref, no {@code bodies} row; the spill file moved into the journal's segments, no spool file).
     */
    @ParameterizedTest
    @ValueSource(strings = {"at-least-once", "sync"})
    void aRunsLocalCopiesAreReleasedWhenItEnds(String mode) {
        FlowDefinition def = flow(mode, List.of(ONE, TWO, THREE));
        NodeStores a = new NodeStores();
        String id = "sb-local-" + mode;
        run(a, def, id);
        BlobKey stepOne = BlobKey.body(id, 1);
        BlobKey stepTwo = BlobKey.body(id, 2);
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), where.get(stepOne), "fixture: step 1's body was held in memory");
        assertEquals(Optional.of(TieredBlobStore.Location.SPILL), where.get(stepTwo), "fixture: step 2's body was spilled");
        for (BlobKey k : List.of(BlobKey.body(id, 0), stepOne, stepTwo)) {
            assertEquals(Optional.empty(), a.blobs.location(k), "no local copy left of " + k);
            assertTrue(a.kept(k).isPresent(), "durable instead: " + k);
        }
        assertEquals(0, a.blobs.memoryInUse(), "the memory budget is back");
        until(() -> filesUnder(a.journalDir.resolve("spill")) == 0, "step 2's spill file moved into the journal's segments");
        assertTrue(filesUnder(a.journalDir.resolve("segments")) > 0, "witness: the journal's segments hold the run's records");
    }
}
