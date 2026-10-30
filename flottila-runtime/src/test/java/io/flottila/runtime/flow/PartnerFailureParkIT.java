package io.flottila.runtime.flow;

import io.flottila.api.Ack;
import io.flottila.api.BlobKey;
import io.flottila.api.Body;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.api.Timer;
import io.flottila.components.http.HttpComponent;
import io.flottila.core.batch.BatchingWriter;
import io.flottila.core.dlq.Parked;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-107 (GATE run 1, check 7): an at-least-once flow on the Postgres work queue whose {@code to} step sends through the real
 * {@link HttpComponent} producer. A partner that refuses the connection (a loopback port bound, then closed: nothing listens)
 * is the partner's failure like a 503 is: the step is retried by the flow's policy, then the message is parked at that step —
 * one DLQ row, stage = the step's name, step = its index, body = the step's input — and its work item is completed by the
 * claim that ran it, with no WARNING "left to its lease". The 503 partner is the control: it behaves so today.
 *
 * <p>Flow {@code orders}: {@code stamp} (hands on a new body written into the runtime's store, so the {@code to} step's input
 * is not the ingress), then {@code to partner}, retryable, retry 3 × from 100 ms. The fake clock and timer drive the retry
 * delays: the test advances the clock by the delay each retry scheduled, never waits on wall time but the spins' bounds. The
 * partner component is the real http component behind a decorator at the SPI boundary that records each send's failure.
 */
class PartnerFailureParkIT {

    static final Path NO_FILES = Path.of("");
    static final Buffer ORDER = Buffer.buffer("<order id=\"1\"/>");
    static final long MIB = 1L << 20;
    static final Duration LEASE = Duration.ofSeconds(30);
    static final int MAX_ATTEMPTS = 5;
    static final int RETRIES = 3;
    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static Vertx vertx;
    static Pool admin;   // the test's own sessions: truncate, counts, rows as another session sees them

    final FakeRuntime rt = FakeRuntime.create();
    /** Every task scheduled on the runtime's timer, in schedule order, with the thread that scheduled it. */
    final List<Scheduled> scheduled = new CopyOnWriteArrayList<>();
    final List<Pool> pools = new CopyOnWriteArrayList<>();
    final List<PgMessageStore> messageStores = new CopyOnWriteArrayList<>();
    final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    /** The bytes every {@code stamp} run handed on, in run order. */
    final List<Buffer> stamped = new CopyOnWriteArrayList<>();
    final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    final Logger core = Logger.getLogger("io.flottila.core");   // a strong reference: JUL holds loggers weakly
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r); }
        @Override public void flush() { }
        @Override public void close() { }
    };
    @TempDir Path spool;

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
        capture.setLevel(Level.ALL);
        core.addHandler(capture);
    }

    @AfterEach
    void close() {
        core.removeHandler(capture);
        workers.forEach(FlowWorker::stop);
        List<Future<Void>> closes = workers.stream().map(FlowWorker::close).toList();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!closes.stream().allMatch(Future::isComplete)) {   // a run a failed case left in a retry wait: the fake clock lets it end
            if (System.nanoTime() > deadline) fail("the workers did not close within 20 s; pending timer tasks " + rt.timer().pending());
            if (rt.timer().pending() > 0) rt.clock().advance(rt.timer().nextDelay());
            Thread.onSpinWait();
            Thread.yield();
        }
        messageStores.forEach(PgMessageStore::close);
        for (Pool p : pools) p.close().await();
        for (HttpServer s : servers) s.close().await();
    }

    // --- fixture

    static PgConnectOptions connectOptions() {
        return new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432)).setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername()).setPassword(pg.getPassword());
    }

    /** At-least-once flow {@code orders}: {@code stamp}, then {@code to partner}, retryable, retry {@value #RETRIES} × from 100 ms. */
    static FlowDefinition flow() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 4));
        doc.put("retry", Map.of("attempts", RETRIES, "initial", "100ms", "max", "2s"));
        doc.put("steps", List.of(Map.of("type", "stamp", "name", "stamp"),
                Map.of("type", "to", "name", "partner", "endpoint", "partner", "retryable", true)));
        return FlowDefinition.from(doc);
    }

    /** {@code stamp}: hands on a new body ({@code <stamped of='id'/>}) written into the runtime's store under the next step's key. */
    StepFactory stamp() {
        return new StepFactory() {
            @Override public String type() { return "stamp"; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) {
                return Stage.of(e -> {
                    Buffer bytes = Buffer.buffer("<stamped of='" + e.id() + "' n='" + (stamped.size() + 1) + "'/>");
                    Body body = res.blobs().write(BlobKey.body(e.id(), e.step() + 1), bytes, res.maxBodyBytes()).await();
                    stamped.add(bytes);
                    return e.next(body);
                });
            }
        };
    }

    record Scheduled(String thread, Duration delay) {}

    /** The runtime's timer, every task scheduled on it recorded with its delay and the scheduling thread. */
    Timer recordingTimer() {
        Timer inner = rt.timer();
        return (delay, task) -> {
            Timer.Cancellable pending = inner.schedule(delay, task);
            scheduled.add(new Scheduled(Thread.currentThread().getName(), delay));   // recorded once pending: a test that sees it can fire it
            return pending;
        };
    }

    /**
     * The delays a run scheduled on its own thread — a retry's wait (the worker names a run's virtual thread
     * {@code flottila-worker-<worker>-<seq>}); the lease's renewals and the delivery's save deadline are scheduled elsewhere.
     */
    List<Duration> retryDelays() {
        return scheduled.stream().filter(s -> s.thread().startsWith("flottila-worker-")).map(Scheduled::delay).toList();
    }

    /** The real http component behind a decorator at the SPI boundary: each send counted, each failure recorded as the future carried it. */
    static final class Watched implements Component {
        final Component inner;
        final AtomicInteger sends = new AtomicInteger();
        final List<Throwable> failures = new CopyOnWriteArrayList<>();

        Watched(Component inner) { this.inner = inner; }

        @Override public String scheme() { return inner.scheme(); }
        @Override public String configSchema() { return inner.configSchema(); }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return inner.consumer(cfg, handler); }
        @Override public Future<Health> health() { return inner.health(); }

        @Override public Producer producer(EndpointConfig cfg) {
            Producer p = inner.producer(cfg);
            return new Producer() {
                @Override public Future<Envelope> send(Envelope e) {
                    sends.incrementAndGet();
                    return p.send(e).onFailure(failures::add);
                }

                @Override public Future<Void> close() { return p.close(); }
            };
        }

        String describe() {
            return sends.get() + " sends, failures " + failures.stream().map(t -> t.getClass().getName() + ": " + t.getMessage()
                    + " (RuntimeException: " + (t instanceof RuntimeException) + ")").toList();
        }
    }

    /** One node's stores over a pool of its own, the http partner at {@code partnerUri}, and a worker of flow {@code orders}. */
    final class Node {
        final TieredBlobStore blobs;
        final Watched http;
        final FlowBuilder builder;
        final FlowDefinition def = flow();
        final FlowWorker worker;

        Node(URI partnerUri) {
            Pool pool = PgBuilder.pool().connectingTo(connectOptions()).with(new PoolOptions().setMaxSize(4)).using(vertx).build();
            pools.add(pool);
            blobs = new TieredBlobStore(new TieredBlobStore.Config(MIB, 1024, 16 * MIB, spool.resolve("node"), DeliveryModesIT.freshKey(), null, false),
                    Hasher.sha256(), new PgBodies(pool, 4096));
            PgMessageStore messages = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
            messageStores.add(messages);
            http = new Watched(new HttpComponent(vertx, blobs, rt.ids()));
            Runtime runtime = new Runtime(rt.clock(), recordingTimer(), rt.ids(), blobs, rt.hasher(), Map.of(http.scheme(), http));
            Map<String, EndpointConfig> endpoints = Map.of("partner", new EndpointConfig("partner", partnerUri, Map.of("maxBodyBytes", 65_536L)));
            builder = new FlowBuilder(runtime, List.of(stamp()), List.of(), endpoints)
                    .recording(messages, Meter.system()).queue(new PgWorkQueue(pool)).dlq(new PgDlq(pool));
            worker = builder.worker(def, NO_FILES, "w-1", new WorkerPolicy(LEASE, MAX_ATTEMPTS, Duration.ofSeconds(1)));
            workers.add(worker);
        }

        /** Message {@code id} through the flow's at-least-once delivery: its ingress persisted, saved and queued; nothing run. */
        String deliver(String id) {
            Body body = blobs.write(BlobKey.body(id, 0), ORDER, def.maxBodyBytes()).await();
            Envelope in = Envelope.ingress(id, def.id(), Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
            assertEquals(new Ack.Accepted(id), builder.delivery(def, NO_FILES).apply(in).await(), "fixture: accepted");
            return id;
        }

        /**
         * One poll of the worker, its run driven to its end: each retry wait the run schedules is fired by advancing the fake
         * clock by exactly its delay (the earliest task: the lease renews after {@code LEASE / 3}, longer than every retry
         * delay together). Returns the poll's count.
         */
        int pollDrivingRetries() {
            Future<Integer> round = worker.poll();
            int fired = 0;
            while (true) {
                int done = fired;
                until(() -> round.isComplete() || retryDelays().size() > done, "the run ends or schedules a retry (" + done + " fired so far; scheduled " + scheduled + ")");
                if (retryDelays().size() > fired) {
                    if (fired >= RETRIES + 2) fail("more retries than the policy's " + RETRIES + ": " + scheduled);
                    Duration delay = retryDelays().get(fired);
                    assertEquals(delay, rt.timer().nextDelay(), "fixture: the retry's wait is the earliest task; scheduled " + scheduled);
                    rt.clock().advance(delay);
                    fired++;
                    continue;
                }
                return round.await();
            }
        }
    }

    /** A loopback port that was bound and is closed again: a connection to it is refused. */
    static int refusingPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** A partner that answers every request 503, recording each request's body. */
    HttpServer unavailablePartner(List<String> saw) {
        HttpServer server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            saw.add(b.toString(StandardCharsets.UTF_8));
            req.response().setStatusCode(503).end("partner down");
        })).listen(0, "127.0.0.1").await();
        servers.add(server);
        return server;
    }

    /** The bytes of {@code key}'s {@code bodies} row as another session sees them, or empty. */
    static Optional<Buffer> bodiesRow(BlobKey key) {
        for (Row r : admin.preparedQuery("select bytes from bodies where message_id = $1 and step = $2 and part = $3")
                .execute(Tuple.of(key.messageId(), key.step(), key.part())).await()) {
            return Optional.of(r.getBuffer(0));
        }
        return Optional.empty();
    }

    static List<Parked> dlqRows() {
        List<Parked> rows = new ArrayList<>();
        for (Row r : admin.query("select id from dlq order by id").execute().await()) rows.add(new PgDlq(admin).get(r.getString(0)).await().orElseThrow());
        return rows;
    }

    static long count(String from) {
        return admin.query("select count(*) from " + from).execute().await().iterator().next().getLong(0);
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

    /** The WARNING (and above) records under io.flottila.core: message, then each thrown's class and stack top. */
    String describeWarnings() {
        List<String> out = new ArrayList<>();
        for (LogRecord r : warnings) {
            StringWriter trace = new StringWriter();
            if (r.getThrown() != null) r.getThrown().printStackTrace(new PrintWriter(trace));
            String[] lines = trace.toString().split("\n");
            out.add(r.getLevel() + " " + r.getLoggerName() + ": " + r.getMessage() + (lines.length > 1 ? " | " + String.join(" | ", List.of(lines).subList(0, Math.min(lines.length, 12))) : ""));
        }
        return out.toString();
    }

    /**
     * What both partners' failures end in: after the retries, one DLQ row at {@code partner} (step 2) by the step's input — the
     * body {@code stamp} wrote, durable in Postgres —, the item completed by the one claim that ran it, no WARNING logged.
     */
    void assertParkedAtThePartnerStep(Node node, String id, int polled) {
        String seen = node.http.describe() + "; scheduled " + scheduled + "; warnings " + describeWarnings();
        List<Parked> rows = dlqRows();
        assertEquals(1, rows.size(), "one DLQ row after the retries: " + rows + "; " + seen);
        Parked parked = rows.get(0);
        assertEquals(id, parked.messageId(), seen);
        assertEquals("partner", parked.stepName(), "parked at the to step, by its name: " + parked + "; " + seen);
        assertEquals(2, parked.step(), "parked at the to step, by its index: " + parked + "; " + seen);
        assertFalse(parked.error().contains("AttemptsExhaustedException"), "the partner's failure, not the attempts bound: " + parked.error());
        assertEquals(BlobKey.body(id, 1), parked.bodyKey(), "by the to step's input, the body stamp handed on: " + parked);
        assertEquals(List.of(stamped.get(0)), stamped, "stamp ran once: the retries re-ran only the to step");
        assertEquals(Optional.of(stamped.get(0)), bodiesRow(BlobKey.body(id, 1)), "the parked input is durable, byte-equal to what stamp handed on");
        assertEquals(1, polled, "one claim ran it");
        assertEquals(1 + RETRIES, node.http.sends.get(), "the partner was tried once and retried " + RETRIES + " times: " + seen);
        assertEquals(List.of(node.def.retry().delayBefore(1), node.def.retry().delayBefore(2), node.def.retry().delayBefore(3)),
                retryDelays(), "the retries waited the policy's delays, on the runtime's timer");
        assertEquals(0, count("work"), "parked: the item was completed by the claim that ran it");
        assertEquals(List.of(), warnings.stream().map(LogRecord::getMessage).toList(), "nothing was left to its lease: no WARNING; " + seen);
        assertEquals(0, node.worker.poll().await(), "nothing left to claim");
        assertEquals(1 + RETRIES, node.http.sends.get(), "and nothing sent again");
    }

    // --- cases

    @Test
    void aRefusedPartnerParksAtItsStepAfterTheRetries() throws IOException {
        Node node = new Node(URI.create("http://127.0.0.1:" + refusingPort() + "/partner"));
        String id = node.deliver("refused-1");

        int polled = node.pollDrivingRetries();

        assertParkedAtThePartnerStep(node, id, polled);
        assertTrue(node.http.failures.stream().allMatch(f -> String.valueOf(f.getMessage()).contains("Connection refused")),
                "fixture: every send was refused at the connection: " + node.http.describe());
    }

    @Test
    void a503PartnerStillParksAtItsStep() {
        List<String> saw = new CopyOnWriteArrayList<>();
        HttpServer partner = unavailablePartner(saw);
        Node node = new Node(URI.create("http://127.0.0.1:" + partner.actualPort() + "/partner"));
        String id = node.deliver("unavailable-1");

        int polled = node.pollDrivingRetries();

        assertParkedAtThePartnerStep(node, id, polled);
        assertEquals(1 + RETRIES, saw.size(), "the partner saw each try: " + saw);
        assertTrue(saw.stream().allMatch(b -> b.equals(stamped.get(0).toString(StandardCharsets.UTF_8))), "each try sent the step's input: " + saw);
        assertTrue(dlqRows().get(0).error().contains("503"), "the row names the partner's answer: " + dlqRows().get(0).error());
    }
}
