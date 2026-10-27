package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.flow.Delivery;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.Replay;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.record.Meter;
import io.flottila.runtime.blob.PgBodies;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09k2 (D-064, D-066, F-10) against Postgres: every kept step body of a run is made durable — its {@code bodies} row
 * committed — when the run ends and before its row is written, so a replay from any step works after a restart, on a fresh
 * node: a new tiered store (fresh spool directory and key), a new pool, a new message store. A row's {@code body_key} always
 * names a durable body; a body above the durable bound is recorded metadata-only; a persisted body's local copy is gone once
 * the run ended.
 *
 * <p>Flow {@code orders}: {@code one} (hands on a new body of {@value #SMALL} bytes, held in memory), {@code two} (a new body
 * of {@value #LARGE} bytes, spooled), {@code three} (notes its input, hands it on). Each writer writes under the envelope's
 * {@link Envelope#nextKey()} into the runtime's tiered store — memory up to {@value #MEMORY_TIER} bytes, spool above,
 * Postgres tier bound {@value #DURABLE_BOUND}. Nothing waits on wall time but the bounds of the spins.
 */
class StepBodiesIT {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final long MIB = 1L << 20;
    /** Bodies up to this many bytes stay in memory; larger ones spool. */
    static final long MEMORY_TIER = 1024;
    /** The Postgres tier's bound: a larger body cannot be made durable. */
    static final long DURABLE_BOUND = 4096;
    static final int SMALL = 300;    // memory
    static final int LARGE = 3000;   // spool, under the durable bound
    static final int ABOVE = 5000;   // spool, above the durable bound
    static final Duration LEASE = Duration.ofSeconds(30);
    static final Duration STOP_BOUND = Duration.ofHours(1);   // on the fake timer: never reached, a stop waits for its flights
    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
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
    @TempDir Path spool;

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
        admin.query("truncate message_keys, messages, payloads, dlq, work, bodies").execute().await();
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
     * One node's stores over a pool of its own: the runtime's tiered store (memory, a spool directory under a fresh key, the
     * Postgres tier), the Postgres message store, queue and DLQ, and a builder over them with the fake clock and timer.
     */
    final class NodeStores {
        final Pool pool;
        final Path spoolDir;
        final TieredBlobStore blobs;
        final PgMessageStore messages;
        final FlowBuilder builder;
        private boolean closed;

        NodeStores(String name) {
            pool = PgBuilder.pool().connectingTo(connectOptions()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
            spoolDir = spool.resolve(name);
            blobs = new TieredBlobStore(new TieredBlobStore.Config(MIB, MEMORY_TIER, 16 * MIB, spoolDir, DeliveryModesIT.freshKey(), null, false),
                    Hasher.sha256(), new PgBodies(pool, DURABLE_BOUND));
            messages = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
            Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), blobs, rt.hasher(), Map.of(partner.scheme(), partner));
            builder = new FlowBuilder(runtime, steps(), List.of(), endpoints)
                    .recording(messages, Meter.system()).queue(new PgWorkQueue(pool)).dlq(new PgDlq(pool));
            nodes.add(this);
        }

        FlowWorker worker(FlowDefinition def, int maxAttempts) {
            FlowWorker w = builder.worker(def, NO_FILES, "w-" + System.identityHashCode(this), new WorkerPolicy(LEASE, maxAttempts, Duration.ofSeconds(1)));
            workers.add(w);
            return w;
        }

        /** Its process is gone: the message store and the pool close; its memory, spool key and directory are never used again. */
        void close() {
            if (closed) return;
            closed = true;
            messages.close();
            pool.close().await();
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
        until(() -> count("messages where id = '" + id + "'") == n, "fixture: " + n + " rows of " + id);
    }

    /** The bytes of {@code key}'s {@code bodies} row as another session sees them, or empty. */
    static Optional<Buffer> bodiesRow(BlobKey key) {
        for (Row r : admin.preparedQuery("select bytes from bodies where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(key.messageId(), key.step(), key.part())).await()) {
            return Optional.of(r.getBuffer(0));
        }
        return Optional.empty();
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

    /** Rows whose {@code body_key} names no {@code bodies} row: a row that promises bytes nobody can read. */
    static long rowsWithoutTheirBody() {
        return count("messages m where m.body_key is not null and not exists ("
                + "select 1 from bodies b where b.message_id || '/' || b.step || '/' || b.part = m.body_key)");
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
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /**
     * The done-when of P1-09k: a message ran through {@code one} (memory), {@code two} (spool) and {@code three} on node A; node
     * A is gone. On a fresh node B — new tiered store with a fresh spool directory and key, new pool, new message store — a
     * replay from step 3 gives step 3 the bytes the original run gave it, read from Postgres by the key step 2's row names; a
     * replay from step 2 does the same for the body that was held in memory.
     */
    @ParameterizedTest
    @ValueSource(strings = {"at-least-once", "sync"})
    void aReplayFromStepThreeOnAFreshNodeAfterARestartReproducesTheStepThreeInputByteForByte(String mode) {
        FlowDefinition def = flow(mode, List.of(ONE, TWO, THREE));
        NodeStores a = new NodeStores("a");
        String id = "sb-" + mode;
        run(a, def, id);
        BlobKey stepOne = BlobKey.body(id, 1);
        BlobKey stepTwo = BlobKey.body(id, 2);
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), where.get(stepOne), "fixture: step 1's body was held in memory");
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), where.get(stepTwo), "fixture: step 2's body was spooled");
        List<Buffer> original = inputsOf("three", id);
        assertEquals(List.of(written.get(stepTwo)), original, "fixture: step 3 ran once, on step 2's output");
        assertEquals(Optional.of(stepTwo.messageId() + "/2/body"), rowBodyKey(id, 2), "fixture: step 2's row keeps its body by key");
        a.close();   // node A is gone: its memory, its spool key, its pool

        NodeStores b = new NodeStores("b");
        assertEquals(Optional.empty(), b.blobs.location(stepTwo), "fixture: the fresh store holds nothing locally");
        Replay replay = b.builder.replay(def, NO_FILES);
        Envelope fromThree = assertDoesNotThrow(() -> replay.from(id, 3), "a replay from step 3 on the fresh node");
        assertEquals(Optional.of(id), fromThree.get(Envelope.REPLAY_OF));
        List<Buffer> replayed = inputsOf("three", fromThree.id());
        assertEquals(1, replayed.size(), "step 3 ran once in the replay");
        assertEquals(original.get(0), replayed.get(0), "the replayed step-3 input is the original step-3 input, byte for byte");

        Envelope fromTwo = assertDoesNotThrow(() -> replay.from(id, 2), "a replay from step 2 on the fresh node");
        assertEquals(List.of(written.get(stepOne)), inputsOf("two", fromTwo.id()), "step 2 replayed on step 1's in-memory body, read from Postgres");
    }

    /**
     * D-066 with the trail: the first run of a message whose {@code stamp} step is not deterministic crashes after its stamp,
     * so its item is claimed again. The first run's trail made its stamp body durable, under the plain key; the rerun stamps
     * other bytes under a key of its own ({@code body~2}). The stamp row is the first run's (the first row wins), and on a
     * fresh node a replay from step 2 reads the first run's bytes — the bytes that row describes.
     */
    @Test
    void aRerunOfAStampingStepKeepsTheFirstRowsBytesReplayableOnAFreshNode() {
        FlowDefinition def = flow("at-least-once", List.of(STAMP, CRASH_FIRST, THREE));
        NodeStores a = new NodeStores("a");
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
        rowsOf(id, 4);   // ingress, stamp (attempt 1's), crash-first and three (attempt 2's)
        assertEquals(stamped.get(1), written.get(new BlobKey(id, 1, "body~2")), "fixture: the rerun wrote under its attempt's key");
        assertEquals(Optional.of(id + "/1/body"), rowBodyKey(id, 1), "the stamp row is the first run's, under the plain key");
        assertEquals(Optional.of(stamped.get(0)), bodiesRow(first), "the first run's stamp body is durable with the first run's bytes");
        a.close();

        NodeStores b = new NodeStores("b");
        Envelope replayed = assertDoesNotThrow(() -> b.builder.replay(def, NO_FILES).from(id, 2), "a replay from step 2 on a fresh node");
        assertEquals(List.of(stamped.get(0)), inputsOf("three", replayed.id()), "step 3 of the replay read the bytes the stamp row describes");
    }

    /** The invariant behind it all: after runs of both modes over memory and spool bodies, every row's {@code body_key} has its {@code bodies} row. */
    @Test
    void everyKeptBodyKeyHasItsBodiesRow() {
        NodeStores a = new NodeStores("a");
        for (String mode : List.of("at-least-once", "sync")) {
            FlowDefinition def = flow(mode, List.of(ONE, TWO, THREE));
            for (int i = 1; i <= 3; i++) run(a, def, "sb-" + mode + "-" + i);
        }
        assertEquals(6 * 4, count("messages where body_key is not null"), "witness: every row of the six messages (ingress and three steps) keeps its body by key");
        assertEquals(0, rowsWithoutTheirBody(), "no row names a body key without a durable body behind it");
    }

    /**
     * Above the durable bound a step body cannot be made durable: its row is recorded metadata-only (hash and size, no key),
     * the rows around it keep theirs, the message is done with as usual, and a replay that would need that body is refused as
     * "recorded metadata-only".
     */
    @ParameterizedTest
    @ValueSource(strings = {"at-least-once", "sync"})
    void aBodyAboveTheBoundIsRecordedMetadataOnly(String mode) {
        FlowDefinition def = flow(mode, List.of(ONE, HUGE, THREE));
        NodeStores a = new NodeStores("a");
        String id = "sb-huge-" + mode;
        run(a, def, id);
        BlobKey huge = BlobKey.body(id, 2);
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), where.get(huge), "fixture: the huge body was spooled");
        assertEquals(Optional.empty(), rowBodyKey(id, 2), "the row of the step whose body is above the bound has no body key");
        long size = admin.preparedQuery("select body_size from messages where id = $1 and step = 2").execute(Tuple.of(id)).await().iterator().next().getLong(0);
        assertEquals(ABOVE, size, "its size is still recorded");
        assertEquals(Optional.empty(), bodiesRow(huge), "nothing was written for it");
        assertEquals(Optional.of(id + "/1/body"), rowBodyKey(id, 1), "the row before it keeps its key");
        assertEquals(Optional.empty(), rowBodyKey(id, 3), "step 3 handed the huge body on unchanged: its row names no key either");
        assertEquals(0, rowsWithoutTheirBody(), "no row names a body that is not durable");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> a.builder.replay(def, NO_FILES).from(id, 3));
        assertTrue(refused.getMessage().contains("metadata-only"), "a replay that needs the body names why it cannot: " + refused.getMessage());
    }

    /** A run's step bodies leave the node's memory and spool once its trail made them durable: nothing of the run stays local. */
    @ParameterizedTest
    @ValueSource(strings = {"at-least-once", "sync"})
    void aRunsLocalCopiesAreReleasedWhenItEnds(String mode) {
        FlowDefinition def = flow(mode, List.of(ONE, TWO, THREE));
        NodeStores a = new NodeStores("a");
        String id = "sb-local-" + mode;
        run(a, def, id);
        BlobKey stepOne = BlobKey.body(id, 1);
        BlobKey stepTwo = BlobKey.body(id, 2);
        assertEquals(Optional.of(TieredBlobStore.Location.MEMORY), where.get(stepOne), "fixture: step 1's body was held in memory");
        assertEquals(Optional.of(TieredBlobStore.Location.SPOOL), where.get(stepTwo), "fixture: step 2's body was spooled");
        for (BlobKey k : List.of(BlobKey.body(id, 0), stepOne, stepTwo)) {
            assertEquals(Optional.empty(), a.blobs.location(k), "no local copy left of " + k);
            assertTrue(bodiesRow(k).isPresent(), "durable instead: " + k);
        }
        assertEquals(0, a.blobs.memoryInUse(), "the memory budget is back");
        until(() -> filesUnder(a.spoolDir) == 0, "the spool file of step 2's body unlinked");
    }
}
