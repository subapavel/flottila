package dev.rail8.runtime.admin;

import dev.rail8.api.Ack;
import dev.rail8.api.BlobKey;
import dev.rail8.api.Body;
import dev.rail8.api.Envelope;
import dev.rail8.api.Hasher;
import dev.rail8.api.Headers;
import dev.rail8.api.Runtime;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.core.batch.BatchingWriter;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.flow.Delivery;
import dev.rail8.core.flow.FlowBuilder;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.FlowWorker;
import dev.rail8.core.flow.RetryPolicy;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.WorkerPolicy;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.Meter;
import dev.rail8.runtime.blob.TieredBlobStore;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Doors;
import dev.rail8.runtime.deploy.PackageDraft;
import dev.rail8.runtime.journal.TestJournals;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.queue.PgFailedMessages;
import dev.rail8.runtime.queue.PgWorkQueue;
import dev.rail8.runtime.store.PgDlq;
import dev.rail8.runtime.store.PgMessageStore;
import dev.rail8.runtime.time.VertxTimer;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-263 fixture (the wiring {@code RetentionRetryIT} and {@code LostBodyAdminIT} share, D-156: a failed message is retried under
 * its own id): one node {@value #NODE} over a Postgres pool — its journal, the {@link TieredBlobStore} over it, the
 * {@link PgMessageStore}, the {@link PgDlq} and the {@link PgWorkQueue} — with a {@link FlowBuilder} over them whose at-least-once
 * delivery saves and queues a message of {@value #FLOW} and whose worker (one hand-out: the first failure parks the ingress at
 * step 1 with attempt 1, CORE-14a P6) runs only when the test polls it. The {@link AdminServer} (token {@value #TOKEN}) is served
 * over the same stores and a real {@link Deployments} of package {@code shop}, where {@value #FLOW} is at-least-once — its own
 * workers run over an in-memory queue of their own, so they never see a Postgres row.
 *
 * <p>The flow's one step, {@code probe}, fails with "partner down (test)" while {@link #down} is set and otherwise notes each run
 * as {@code <id>@<attempt> <body>} in {@link #runs}, the body read through the node's store.
 */
final class AdminPgFixture implements AutoCloseable {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final Duration BOUND = Duration.ofSeconds(20);
    static final long MIB = 1L << 20;
    static final String NODE = "node-1";
    static final String FLOW = "shop/orders";
    static final String ORDER = "<order/>";
    static final Path NO_FILES = Path.of("");

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final AtomicBoolean down = new AtomicBoolean();
    final List<String> runs = new CopyOnWriteArrayList<>();
    final Pool pool;
    final HttpClientAgent client;
    final TestJournals.Opened journal;
    final TieredBlobStore blobs;
    final PgMessageStore messages;
    final PgDlq dlq;
    final PgWorkQueue queue;
    final Delivery delivery;
    final FlowWorker worker;
    final Deployments deployments;
    final AdminServer server;
    final int port;

    AdminPgFixture(Vertx vertx, Pool pool, HttpClientAgent client, Path tmp) throws Exception {
        this.pool = pool;
        this.client = client;
        journal = TestJournals.open(pool, Files.createDirectories(tmp.resolve("journal")), NODE);
        blobs = new TieredBlobStore(new TieredBlobStore.Config(MIB, 64 * 1024, 16 * MIB, MIB), Hasher.sha256(), journal.journal(), journal.tenant(), pool);
        messages = new PgMessageStore(pool, new VertxTimer(vertx), new BatchingWriter.Options(100, Duration.ofMillis(10), 2, 1000));
        dlq = new PgDlq(pool);
        queue = new PgWorkQueue(pool, NODE);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), blobs, rt.hasher(), Map.of(Doors.SCHEME, doors));
        FlowBuilder builder = new FlowBuilder(runtime, List.of(probe()), List.of(), Map.of()).recording(messages, Meter.system()).queue(queue).dlq(dlq)
                .retries(new RetryPolicy(0, Duration.ofHours(1), Duration.ofHours(1), 1.0));
        delivery = builder.delivery(flow(), NO_FILES);
        worker = builder.worker(flow(), NO_FILES, "w-" + NODE, new WorkerPolicy(Duration.ofSeconds(30), Duration.ofSeconds(1)));
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", NODE);
        env.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(probe()), List.of(),
                messages, new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L);
        PackageDraft shop = PackageDraft.of("shop", "1.0.0");
        shop.endpoint("orders-in", "in", new JsonObject().put("path", "/orders"))
                .flow("orders", "1.0.0", "at-least-once", "orders-in", 65_536, new JsonArray().add(new JsonObject().put("type", "probe").put("name", "probe")));
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), shop.zipTo(tmp.resolve("archives"))));
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, blobs,
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments), queue, new PgFailedMessages(pool, queue), Optional.empty(),
                new Workers(), WorkSignals.NONE);
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        worker.stop();
        doors.releaseAll();
        server.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
        messages.close();
        journal.close();
    }

    /** {@value #FLOW} as the worker runs it: at-least-once, the one {@code probe}. */
    static FlowDefinition flow() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", FLOW);
        doc.put("version", "1.0.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("steps", List.of(Map.of("type", "probe", "name", "probe")));
        return FlowDefinition.from(doc);
    }

    StepFactory probe() {
        return new StepFactory() {
            @Override public String type() { return "probe"; }
            @Override public String configSchema() { return "{}"; }
            @Override public Stage create(StepConfig step, StepResources res) {
                return Stage.of(e -> {
                    if (down.get()) throw new IllegalStateException("partner down (test)");
                    runs.add(e.id() + "@" + e.get(Envelope.ATTEMPT).orElse(1) + " " + e.body().whole());
                    return e;
                });
            }
        };
    }

    /** A message of {@value #FLOW} through its at-least-once delivery: its ingress body written to the node's store, saved and queued; nothing run. */
    String accepted() throws Exception {
        String id = rt.ids().next();
        Body body = blobs.write(BlobKey.body(id, 0), Buffer.buffer(ORDER), 65_536).await();
        Envelope in = Envelope.ingress(id, FLOW, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), body);
        assertEquals(new Ack.Accepted(id), delivery.apply(in).await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "fixture: accepted");
        return id;
    }

    /** A message its worker parked: its run failed, the ingress parked at step 1 with attempt 1 (CORE-14a P6). Returns its id. */
    String parked() throws Exception {
        String id = accepted();
        down.set(true);
        try {
            assertEquals(1, worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "fixture: the worker claims " + id);
        } finally {
            down.set(false);
        }
        Parked row = dlq.get(id).await().orElseThrow(() -> new AssertionError("fixture: " + id + " parked by its worker"));
        assertEquals(1, row.step(), "fixture: parked by its ingress, step 1");
        assertEquals(Optional.of(1), row.props().get(Envelope.ATTEMPT), "fixture: after attempt 1");
        assertEquals(0, count("select count(*) from work where id = $1", id), "fixture: its work row completed by the park");
        return id;
    }

    /** A message its worker delivered: run once, its work row completed, no DLQ row. Returns its id. */
    String delivered() throws Exception {
        String id = accepted();
        assertEquals(1, worker.poll().await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "fixture: the worker claims " + id);
        assertEquals(id + "@1 " + ORDER, runs.getLast(), "fixture: " + id + " ran once");
        assertEquals(0, count("select count(*) from work where id = $1", id), "fixture: its work row completed");
        assertEquals(Optional.empty(), dlq.get(id).await(), "fixture: not parked");
        return id;
    }

    record Answer(int status, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    /** {@code method path} on the admin port with the token; the response paused in its head callback until its body is asked for. */
    Answer send(HttpMethod method, String path) throws Exception {
        return client.request(new RequestOptions().setMethod(method).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    req.putHeader("Authorization", AUTH);
                    req.response().onSuccess(HttpClientResponse::pause);
                    return req.send();
                })
                .compose(res -> {
                    var body = res.body();
                    res.resume();
                    return body.map(b -> new Answer(res.statusCode(), b));
                })
                .await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** {@code POST /admin/dlq/{id}/retry}: the same-id restart. */
    Answer retry(String id) throws Exception {
        return send(HttpMethod.POST, "/admin/dlq/" + id + "/retry");
    }

    long count(String sql, String param) {
        return pool.preparedQuery(sql).execute(Tuple.of(param)).await().iterator().next().getLong(0);
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; fails after {@link #BOUND} naming what never happened. */
    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within " + BOUND + ": " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }
}
