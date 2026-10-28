package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.api.Runtime;
import io.flottila.components.http.HttpComponent;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.flow.Delivery;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Snapshot;
import io.flottila.core.record.Trail;
import io.flottila.runtime.blob.PgBodies;
import io.flottila.runtime.blob.TieredBlobStore;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.runtime.store.PgMessageStore;
import io.flottila.runtime.time.VertxTimer;
import io.flottila.steps.validate.XsdValidate;
import io.flottila.steps.xslt.SaxonXml;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.RecordingComponent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
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

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
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
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-08 against Postgres: no 202 without a committed ingress row and queue item (the invariant behind the kill -9 ledger,
 * which is P1-10's measurement), and the worker drains the durable queue. P1-09h (D-058, F-6): the bodies live in the
 * runtime's tiered store with its Postgres tier, as on a node — no 202 before the body's {@code bodies} row is committed, and a
 * worker over a fresh store (new spool directory and key, new pool: a restart) reads the queued body from Postgres.
 */
class DeliveryModesIT {

    /** The demo package's root: every step file reference is a path from it (D-078). */
    static final Path DEMO = Path.of("../packages/demo");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final long MIB = 1L << 20;
    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool pool;
    static HttpClientAgent caller;

    final FakeRuntime rt = FakeRuntime.create();
    final RecordingComponent partner = new RecordingComponent("mock");
    final Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", URI.create("mock://partner"), Map.of()));
    /** The consumer's ids: the first message of a case is {@code dm-1}. */
    final FakeIds ids = new FakeIds("dm");
    @TempDir Path spool;
    TieredBlobStore blobs;
    PgMessageStore store;
    PgWorkQueue queue;
    PgDlq dlq;
    HttpComponent.HttpConsumer consumer;
    FlowDefinition def;
    FlowWorker worker;

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
        caller = vertx.createHttpClient();
        PgConnectOptions connect = new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()).setCachePreparedStatements(true);
        pool = PgBuilder.pool().connectingTo(connect).with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    static void stop() {
        caller.close().await();
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void open() {
        pool.query("truncate message_keys, messages, payloads, dlq, work, bodies").execute().await();
        store = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        queue = new PgWorkQueue(pool);
        dlq = new PgDlq(pool);
        rt.withComponent(partner);
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "http-in"));
        doc.put("delivery", Map.of("mode", "at-least-once"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536));
        doc.put("steps", List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        def = FlowDefinition.from(doc);
        blobs = tiered(spool.resolve("node"), pool);
        FlowBuilder builder = builder(blobs, store, queue, dlq);
        Function<Envelope, Future<Ack>> delivery = builder.delivery(def, DEMO);
        worker = builder.worker(def, DEMO, "w1");
        HttpComponent http = new HttpComponent(vertx, blobs, ids);
        EndpointConfig in = new EndpointConfig("http-in", URI.create("http://127.0.0.1:0/in"),
                Map.of("host", "127.0.0.1", "port", 0, "path", "/in", "flow", def.id(), "maxBodyBytes", def.maxBodyBytes()));
        consumer = (HttpComponent.HttpConsumer) http.consumer(in, delivery);
        consumer.start().await();
    }

    @AfterEach
    void close() {
        consumer.stop().await();
        store.close();
    }

    /** The runtime's store as a node builds it: memory and spool (a fresh key per start) and the Postgres tier over {@code p}. */
    static TieredBlobStore tiered(Path spoolDir, Pool p) {
        return new TieredBlobStore(new TieredBlobStore.Config(MIB, 64 * 1024, 16 * MIB, spoolDir, freshKey(), null, false), io.flottila.api.Hasher.sha256(),
                new PgBodies(p, MIB));
    }

    static SecretKey freshKey() {
        try {
            KeyGenerator g = KeyGenerator.getInstance("AES");
            g.init(256);
            return g.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A builder over {@code blobs} — the runtime's store — and the given Postgres stores, the fake clock and timer, the mock partner. */
    FlowBuilder builder(TieredBlobStore blobs, PgMessageStore messages, PgWorkQueue work, PgDlq parked) {
        SaxonXml xml = new SaxonXml();
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), blobs, rt.hasher(), Map.of(partner.scheme(), partner));
        return new FlowBuilder(runtime, Stream.concat(new XsdValidate().steps().stream(), xml.steps().stream()).toList(), xml.languages(), endpoints)
                .recording(messages, Meter.system()).queue(work).dlq(parked);
    }

    record Answer(int status, String messageId) {}

    /** Posts the order; the answer, whenever it comes. */
    Future<Answer> post() {
        return caller.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + consumer.port() + "/in"))
                .compose(req -> { req.putHeader("content-type", "application/xml"); return req.send(ORDER); })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.getHeader(HttpComponent.H_MESSAGE_ID))));
    }

    /** Posts the order; the 202's message id. */
    String accepted() {
        Answer a = post().await();
        assertEquals(202, a.status());
        assertNotNull(a.messageId());
        return a.messageId();
    }

    /** The bytes of the message's ingress body row, as a session of its own sees them, or empty. */
    static Optional<Buffer> bodiesRow(String messageId) {
        PgConnection reader = PgConnection.connect(vertx, connectOptions()).await();
        try {
            for (Row r : reader.preparedQuery("select bytes from bodies where message_id = $1 and step = 0 and part = 'body'").execute(Tuple.of(messageId)).await()) {
                return Optional.of(r.getBuffer(0));
            }
            return Optional.empty();
        } finally {
            reader.close().await();
        }
    }

    static PgConnectOptions connectOptions() {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    static long count(String table) {
        return pool.query("select count(*) from " + table).execute().await().iterator().next().getLong(0);
    }

    /** Backends of the database waiting on a lock. */
    static long lockWaiters() {
        return pool.preparedQuery("select count(*) from pg_stat_activity where datname = $1 and wait_event_type = 'Lock'")
                .execute(Tuple.of(pg.getDatabaseName())).await().iterator().next().getLong(0);
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
    void theIngressRowAndTheQueueItemAreCommittedWhenThe202Arrives() {
        String id = accepted();
        // no flush: what the 202 promised is already in the database
        List<Snapshot> timeline = store.timeline(id).await();
        assertEquals(List.of("ingress"), timeline.stream().map(Snapshot::stepName).toList());
        assertTrue(timeline.get(0).bodyKey().isPresent(), "the ingress row keeps the payload's key");
        List<WorkQueue.Item> claimed = queue.claim("probe", def.id(), 10, Duration.ofSeconds(30)).await();
        assertEquals(1, claimed.size());
        assertEquals(id, claimed.get(0).messageId());
        assertEquals(List.of(), partner.sent(), "nothing ran before the worker");
    }

    @Test
    void theWorkerProcessesTheQueuedMessageAndCompletesIt() {
        String id = accepted();
        assertEquals(1, worker.poll().await());
        List<Envelope> got = partner.sentTo("partner");
        assertEquals(1, got.size());
        assertEquals(id, got.get(0).id());
        assertEquals(ORDER.toString(), got.get(0).body().whole().toString());
        assertEquals(List.of(), queue.claim("probe", def.id(), 10, Duration.ofSeconds(30)).await(), "the item is completed");
        store.flush().await();
        assertEquals(Optional.of("partner"), store.timeline(id).await().stream().map(Snapshot::stepName).filter("partner"::equals).findFirst());
    }

    /**
     * Another session holds an uncommitted {@code bodies} row under the message's key: the persist's insert waits for it, and no
     * 202 leaves while it waits — though the ingress snapshot is already committed. Once that session rolls back, the insert
     * commits and the 202 arrives; at that moment another session sees the row with the posted bytes.
     */
    @Test
    void the202ArrivesOnlyWithTheBodiesRowCommitted() {
        String id = "dm-1";   // the consumer's first id
        PgConnection holder = PgConnection.connect(vertx, connectOptions()).await();
        Future<Answer> answer;
        try {
            Transaction held = holder.begin().await();
            holder.preparedQuery("insert into bodies(message_id, step, part, bytes) values ($1, 0, 'body', $2)")
                    .execute(Tuple.of(id, Buffer.buffer("another session's, rolled back"))).await();
            answer = post();
            until(() -> lockWaiters() == 1, "the body's insert waits on the other session's uncommitted row");
            until(() -> store.timeline(id).await().size() == 1, "the ingress snapshot is committed meanwhile");
            assertFalse(answer.isComplete(), "no answer while the body's row is not committed: " + answer);
            assertEquals(0, count("work"), "nothing enqueued while the body is not durable");
            held.rollback().await();
        } finally {
            holder.close().await();
        }
        Answer a = answer.await();
        assertEquals(202, a.status());
        assertEquals(id, a.messageId());
        assertEquals(Optional.of(ORDER), bodiesRow(id), "at the 202, another session sees the body's row with the posted bytes");
        List<WorkQueue.Item> claimed = queue.claim("probe", def.id(), 10, Duration.ofSeconds(30)).await();
        assertEquals(List.of(id), claimed.stream().map(WorkQueue.Item::messageId).toList(), "and the item");
    }

    /**
     * A restart between the 202 and the worker: a new store over a new spool directory and key and a new pool, a new builder
     * and worker. The queued message's body is read from Postgres by its key; nothing of the first store is used.
     */
    @Test
    void aQueuedBodyIsReadFromPostgresByAFreshStoreAfterARestart() {
        String id = accepted();
        Pool fresh = PgBuilder.pool().connectingTo(connectOptions()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
        PgMessageStore restartedStore = new PgMessageStore(fresh, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        try {
            TieredBlobStore restarted = tiered(spool.resolve("after-restart"), fresh);
            assertEquals(Optional.empty(), restarted.location(io.flottila.api.BlobKey.body(id, 0)), "the fresh store holds nothing locally");
            FlowWorker after = builder(restarted, restartedStore, new PgWorkQueue(fresh), new PgDlq(fresh)).worker(def, DEMO, "w-restarted");
            assertEquals(1, after.poll().await(), "the restarted worker processes the queued message");
            List<Envelope> got = partner.sentTo("partner");
            assertEquals(1, got.size());
            assertEquals(id, got.get(0).id());
            assertEquals(ORDER.toString(), got.get(0).body().whole().toString(), "the body, read from Postgres by its key");
            assertEquals(List.of(), queue.claim("probe", def.id(), 10, Duration.ofSeconds(30)).await(), "the item is completed");
        } finally {
            restartedStore.close();
            fresh.close().await();
        }
    }

    // --- FIX-66 (F-13, D-071): a sync keep flow past the node writer's backlog loses no row of an answered message

    /** A strong reference: JUL holds loggers weakly. */
    static final Logger TRAIL = Logger.getLogger(Trail.class.getName());

    /** A sync flow {@code orders} with the one partner step, recorded at the default level (every step, payload kept), 32 in flight. */
    static FlowDefinition syncKeep() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "http-in"));
        doc.put("delivery", Map.of("mode", "sync"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 32));
        doc.put("recording", Map.of("level", "all", "payload", "keep"));
        doc.put("steps", List.of(Map.of("type", "to", "name", "partner", "endpoint", "partner")));
        return FlowDefinition.from(doc);
    }

    /**
     * The node's message store with a writer backlog of two (one commit in flight, the default maxWait), its first commit held
     * on the rows' payloads hash another session keeps uncommitted while 16 virtual threads send 200 requests: the runs' rows
     * overflow the backlog at once. A thread that is refused waits until the holder rolled back, then goes on. Every request
     * answered with a Reply has its ingress and partner rows; every other answer is a retryable Nack and left no row; no row
     * write failed and the trail logged no WARNING (the capture's witness: a row to a closed store does log one).
     */
    @Test
    void aSyncKeepFlowPastTheWriterBacklogLosesNoRowOfAnAnsweredMessage() throws Exception {
        PgMessageStore small = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(2, Duration.ZERO, 1, 2));
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        TRAIL.addHandler(capture);
        try {
            Delivery delivery = builder(blobs, small, queue, dlq).delivery(syncKeep(), DEMO);
            Body probe = blobs.write(BlobKey.body("sk-probe", 0), ORDER, MIB).await();   // every row of the run shows this body's hash
            Map<String, Future<Ack>> answers = new ConcurrentHashMap<>();
            List<Throwable> escaped = new CopyOnWriteArrayList<>();
            AtomicInteger issued = new AtomicInteger();
            AtomicInteger nacks = new AtomicInteger();
            CountDownLatch unplugged = new CountDownLatch(1);
            List<Thread> senders = new ArrayList<>();
            PgConnection holder = PgConnection.connect(vertx, connectOptions()).await();
            try {
                Transaction held = holder.begin().await();
                holder.preparedQuery("insert into payloads(hash, size, algorithm) values ($1, $2, 'sha256-64')")
                        .execute(Tuple.of(Buffer.buffer(ByteBuffer.allocate(Long.BYTES).putLong(probe.hash().value()).array()), probe.size())).await();
                for (int t = 0; t < 16; t++) {
                    senders.add(Thread.ofVirtual().name("sender-" + t).start(() -> {
                        try {
                            for (int n = issued.getAndIncrement(); n < 200; n = issued.getAndIncrement()) {
                                String id = String.format("sk-%03d", n);
                                Body body = blobs.write(BlobKey.body(id, 0), ORDER, MIB).await();
                                Future<Ack> ack = delivery.apply(Envelope.ingress(id, "orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body));
                                answers.put(id, ack);
                                if (ack.isComplete() && ack.result() instanceof Ack.Nack) {
                                    nacks.incrementAndGet();
                                    unplugged.await();   // refused while the writer is held: wait for the holder to let go, then go on
                                }
                            }
                        } catch (Throwable t2) {
                            escaped.add(t2);
                        }
                    }));
                }
                until(() -> nacks.get() > 0 || issued.get() >= 200 || !escaped.isEmpty(),
                        "a request refused while the runs' rows wait (lock waiters " + lockWaiters() + ")");
                held.rollback().await();
            } finally {
                unplugged.countDown();
                holder.close().await();
            }
            for (Thread t : senders) {
                if (!t.join(Duration.ofSeconds(60))) fail(t.getName() + " did not end; it sits at " + List.of(t.getStackTrace()));
            }
            assertEquals(List.of(), escaped, "no sender failed");
            delivery.stop(Duration.ofHours(1)).await(60, TimeUnit.SECONDS);   // every flight landed: every trail closed, its row writes settled

            Map<String, String> rows = new HashMap<>();
            for (Row r : pool.query("select id, string_agg(step_name, ',' order by step) from messages group by id").execute().await()) {
                rows.put(r.getString(0), r.getString(1));
            }
            List<String> replied = new ArrayList<>();
            List<String> refused = new ArrayList<>();
            List<String> other = new ArrayList<>();
            answers.forEach((id, ack) -> {
                if (ack.succeeded() && ack.result() instanceof Ack.Reply) replied.add(id);
                else if (ack.succeeded() && ack.result() instanceof Ack.Nack nack && nack.retryable()) refused.add(id);
                else other.add(id + ": " + ack);
            });
            assertEquals(200, answers.size(), "every request answered");
            assertEquals(List.of(), other, "every answer is a Reply or a retryable Nack");
            List<String> repliedWithoutTimeline = replied.stream().filter(id -> !"ingress,partner".equals(rows.get(id))).sorted()
                    .map(id -> id + "=" + rows.get(id)).toList();
            assertEquals(List.of(), repliedWithoutTimeline, "every answered message has its ingress and partner rows (" + replied.size() + " replied)");
            assertEquals(0, small.failed(), "no row write failed");
            assertEquals(List.of(), refused.stream().filter(rows::containsKey).toList(), "a refused request left no row");
            assertTrue(nacks.get() > 0, "fixture: admission refused while the rows waited for room, the permits held: replies " + replied.size());
            assertEquals(List.of(), warnings.stream().map(LogRecord::getMessage).toList(), "no row was given up: the trail logged no WARNING");

            PgMessageStore closed = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(2, Duration.ZERO, 1, 2));
            closed.close();
            Trail witness = new Trail(List.of());
            witness.add(closed, new Snapshot("sk-witness", "orders", 1, "partner", Snapshot.Outcome.OK, Optional.empty(), Instant.EPOCH, 0, 0, Headers.empty(),
                    Props.empty(), Keys.empty(), probe.hash(), probe.size(), Optional.empty(), Map.of()), probe);
            witness.close().await(20, TimeUnit.SECONDS);
            assertEquals(1, warnings.size(), "the capture sees a trail row WARNING: " + warnings.stream().map(LogRecord::getMessage).toList());
            assertTrue(warnings.getFirst().getMessage().contains("message sk-witness "), warnings.getFirst().getMessage());
        } finally {
            TRAIL.removeHandler(capture);
            small.close();
        }
    }
}
