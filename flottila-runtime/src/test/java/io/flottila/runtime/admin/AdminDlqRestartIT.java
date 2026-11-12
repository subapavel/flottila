package io.flottila.runtime.admin;

import io.flottila.api.Envelope;
import io.flottila.api.Expressions;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.deploy.PackageDraft;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.vertx.core.json.JsonArray;
import org.junit.jupiter.api.io.TempDir;
import java.util.function.Function;
import java.util.function.Predicate;
import io.flottila.api.Headers;
import io.flottila.api.Props;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.Parked;
import io.flottila.core.flow.FlowBuilder;
import io.flottila.core.flow.FlowDefinition;
import io.flottila.core.flow.FlowWorker;
import io.flottila.core.flow.WorkSignals;
import io.flottila.core.flow.WorkerPolicy;
import io.flottila.core.flow.Workers;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Meter;
import io.flottila.core.record.Recorder;
import io.flottila.core.record.RecordingPolicy;
import io.flottila.runtime.db.Migrations;
import io.flottila.runtime.queue.ParkedRows;
import io.flottila.runtime.queue.PgWorkQueue;
import io.flottila.runtime.store.PgDlq;
import io.flottila.testkit.FakeRuntime;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-14b P8 (D-135) over the admin API and a real Postgres: {@code POST /admin/dlq/{id}/restart} of a row the worker parked
 * (step 1, prop {@code attempt} k, no {@code replayOf}) answers 202 {@code {id, attempt: k + 1}}, its DLQ row gone and one work row
 * of the same id at step 1 with {@code max_attempts} k + 1; a second restart is 404. No DLQ row: 404. A row the worker did not park —
 * at a step other than 1 (a pre-D-135 park or a sync flow's), or a replay's ({@code replayOf}) — is 422, its DLQ row kept, nothing
 * written. The restarted item runs once, as attempt k + 1 (here 6, past the flow's bound of 4: only the row's own bound runs it),
 * on the node whose journal holds its body (node-a) — though the admin call landed on node-b, whose worker claims nothing of it
 * (O6: until CORE-5 a node claims only rows whose body its journal holds).
 *
 * <p>O7 (the DLQ row keeps the partition key, V17 {@code dlq.partition_key}): a restart enqueues under the kept key; a row of a keyed
 * flow ({@code partitionBy} set, as deployed here) whose key is empty — parked before V17 — is 422 "parked before its key was kept;
 * use /retry", its DLQ row kept, nothing written; an unkeyed flow's empty key restarts (the first case).
 *
 * <p>Seams: the {@link AdminServer} over the {@link PgDlq}, node-b's {@link PgWorkQueue} and a real {@code Deployments} of package
 * {@code shop} ({@code shop/orders} unkeyed, {@code shop/keyed} by {@code customer}; its own workers run over an in-memory queue of
 * their own, so they never see a Postgres row);
 * the workers of node-a and node-b over their own queues, never started and in no {@link Workers} of the server — each round is
 * the test's {@link FlowWorker#poll}, whose future completes once its runs settled; the DLQ rows and kept refs from
 * {@link ParkedRows}.
 *
 * <p>The API assumed (test-author): the route is served with the held-work routes and the node's {@code Deployments}, from which it
 * tells whether the row's flow is keyed; {@code Optional<String> Parked.partitionKey()} (see {@code ParkedRows}); 202
 * {@code {id, attempt}} exactly; 404 and 422 {@code {error}} naming the message; the store method {@code WorkQueue.restart}
 * (see {@code WorkQueueContract}, {@code DlqRestartIT}).
 */
@Timeout(120)
class AdminDlqRestartIT {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final Duration LEASE = Duration.ofSeconds(30);

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;
    static Pool pool;
    static HttpClientAgent client;

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    /** Every run of the {@code probe} step: {@code <id>@<attempt>}. */
    final List<String> runs = new CopyOnWriteArrayList<>();
    final StepFactory probe = new StepFactory() {
        @Override public String type() { return "probe"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Stage create(StepConfig step, StepResources resources) {
            return Stage.of(e -> {
                runs.add(e.id() + "@" + e.get(Envelope.ATTEMPT).orElse(1));
                return e;
            });
        }
    };
    final List<FlowWorker> workers = new CopyOnWriteArrayList<>();
    final Doors doors = new Doors();
    @TempDir Path tmp;
    Deployments deployments;
    AdminServer server;
    int port;

    @BeforeAll
    static void up() {
        pg.start();
        vertx = Vertx.vertx();
        Migrations.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        pool = PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(pg.getHost()).setPort(pg.getMappedPort(5432))
                .setDatabase(pg.getDatabaseName()).setUser(pg.getUsername()).setPassword(pg.getPassword()))
                .with(new PoolOptions().setMaxSize(8)).using(vertx).build();
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void down() {
        client.close().await();
        pool.close().await();
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void wire() throws Exception {
        pool.query("truncate work, dlq, body_refs, journal_segments").execute().await();
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-b");
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        rt.withComponent(doors);
        deployments = new Deployments(NodeConfig.from(env), rt.runtime(), Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(probe), List.of(HEADER),
                store, new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L);
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), shop().zipTo(tmp.resolve("archives"))));
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), store, new PgDlq(pool), rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments), new PgWorkQueue(pool, "node-b"), new Workers(), WorkSignals.NONE);
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        workers.forEach(FlowWorker::stop);
        server.stop().await(10, TimeUnit.SECONDS);
        doors.releaseAll();
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    /** The test's {@code header} language: an expression is a header name, its value the header's (core's BuiltInStepsTest.HEADERS). */
    static final Expressions HEADER = new Expressions() {
        @Override public String language() { return "header"; }
        @Override public Predicate<Envelope> predicate(String expression, StepResources resources) {
            throw new UnsupportedOperationException("no conditions in this test");
        }
        @Override public Function<Envelope, Optional<String>> value(String expression, StepResources resources) {
            return e -> e.header(expression);
        }
    };

    /** {@code shop} 1.0.0: at-least-once {@code orders} (one probe, no key) and {@code keyed} (extractKey customer, probe; partitionBy customer). */
    static PackageDraft shop() {
        PackageDraft p = PackageDraft.of("shop", "1.0.0");
        p.endpoint("orders-in", "in", new JsonObject().put("path", "/orders"))
                .flow("orders", "1.0.0", "at-least-once", "orders-in", 65_536, new JsonArray().add(new JsonObject().put("type", "probe").put("name", "probe")));
        p.endpoint("keyed-in", "in", new JsonObject().put("path", "/keyed")).flow("keyed", "1.0.0", "at-least-once", "keyed-in", 65_536, new JsonArray()
                .add(new JsonObject().put("type", "extractKey").put("name", "key").put("language", "header").put("key", "customer").put("xpath", "x-customer"))
                .add(new JsonObject().put("type", "probe").put("name", "probe")));
        p.flowDoc("keyed").put("partitionBy", "customer").put("onFailure", "park");
        return p;
    }

    /** At-least-once {@code shop/orders} as deployed: one {@code probe}; no {@code delivery.retries}: the builder's default 3, a bound of 4. */
    static FlowDefinition flow() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "shop/orders");
        doc.put("version", "0.1.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("steps", List.of(Map.of("type", "probe", "name", "probe")));
        return FlowDefinition.from(doc);
    }

    /** {@code node}'s worker of {@code orders} over its own queue (a node claims the rows whose body its journal holds). */
    FlowWorker worker(String node) {
        FlowWorker w = new FlowBuilder(rt.runtime(), List.of(probe), List.of(), Map.of()).recording(store, Meter.system())
                .queue(new PgWorkQueue(pool, node)).dlq(new PgDlq(pool)).worker(flow(), Path.of(""), node, new WorkerPolicy(LEASE, Duration.ofDays(7)));
        workers.add(w);
        return w;
    }

    /** A message of {@code shop/orders} whose ingress is recorded; nothing queued. */
    Envelope recorded() {
        return recorded("shop/orders");
    }

    /** A message of {@code flow} whose ingress is recorded; nothing queued. */
    Envelope recorded(String flow) {
        Envelope in = rt.ingress(flow, Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        return in;
    }

    record Answer(int status, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    Answer restart(String id) {
        return client.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + "/admin/dlq/" + id + "/restart"))
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
                .await();
    }

    long count(String sql, Object... params) {
        return ParkedRows.count(pool, sql, params);
    }

    Optional<Parked> dlqRow(String id) {
        return new PgDlq(pool).get(id).await();
    }

    void refusedNaming(Answer a, int status, String id, String what) {
        assertEquals(status, a.status(), what + ": " + a.body());
        String error = a.json().getString("error");
        assertTrue(error != null && error.contains(id), what + ": the error names the message: " + a.body());
    }

    @Test
    void restartAnswers202AndRunsTheItemOnceOnTheNodeHoldingItsBody() throws Exception {
        Envelope in = recorded();
        ParkedRows.park(pool, ParkedRows.byTheWorker(in.id(), "shop/orders", 5), "node-a");
        FlowWorker a = worker("node-a");
        FlowWorker b = worker("node-b");

        Answer answer = restart(in.id());
        assertEquals(202, answer.status(), "restarted: " + answer.body());
        assertEquals(new JsonObject().put("id", in.id()).put("attempt", 6), answer.json(), "the same id, its next attempt k + 1");
        assertEquals(Optional.empty(), dlqRow(in.id()), "its DLQ row is gone");
        Row row = pool.query("select id, step, max_attempts from work").execute().await().iterator().next();
        assertEquals(Arrays.asList(in.id(), 1, 6), Arrays.asList(row.getString(0), row.getInteger(1), row.getInteger(2)),
                "one work row: same id, step 1, max_attempts k + 1");
        assertEquals(1, count("select count(*) from work"));
        refusedNaming(restart(in.id()), 404, in.id(), "a second restart: no DLQ row any more");
        assertEquals(1, count("select count(*) from work"), "the second restart wrote nothing");

        assertEquals(0, b.poll().await(20, TimeUnit.SECONDS), "node-b, where the call landed, does not hold its body: it claims nothing");
        assertEquals(List.of(), runs);
        assertEquals(1, a.poll().await(20, TimeUnit.SECONDS), "node-a, whose journal holds its body, claims it");
        assertEquals(List.of(in.id() + "@6"), runs, "run once, under its id, as attempt 6 — past the flow's bound of 4");
        assertEquals(0, count("select count(*) from work"), "completed");
        assertEquals(Optional.empty(), dlqRow(in.id()), "not parked again");
    }

    @Test
    void noDlqRowIs404() {
        refusedNaming(restart("m-404"), 404, "m-404", "no DLQ row");
        assertEquals(0, count("select count(*) from work"), "nothing written");
    }

    @Test
    void aRowTheWorkerDidNotParkIs422() {
        Envelope atStep2 = recorded();
        Parked step2 = ParkedRows.row(atStep2.id(), "shop/orders", 2, Props.empty().with(Envelope.ATTEMPT, 1));
        ParkedRows.park(pool, step2, "node-a");
        Envelope replayed = recorded();
        Parked replay = ParkedRows.row(replayed.id(), "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 1).with(Envelope.REPLAY_OF, "m-original"));
        ParkedRows.park(pool, replay, "node-a");

        refusedNaming(restart(atStep2.id()), 422, atStep2.id(), "parked at step 2, not by the worker");
        refusedNaming(restart(replayed.id()), 422, replayed.id(), "a replay's row (replayOf)");
        assertEquals(Optional.of(step2), dlqRow(atStep2.id()), "its DLQ row kept");
        assertEquals(Optional.of(replay), dlqRow(replayed.id()), "its DLQ row kept");
        assertEquals(0, count("select count(*) from work"), "nothing written");

        Envelope witness = recorded();
        ParkedRows.park(pool, ParkedRows.byTheWorker(witness.id(), "shop/orders", 1), "node-a");
        assertEquals(202, restart(witness.id()).status(), "witness: a row the worker parked, on the same wiring, restarts");
    }

    @Test
    void aKeyedFlowsRowParkedBeforeItsKeyWasKeptIs422AndAKeptKeyRestartsUnderIt() {
        Envelope old = recorded("shop/keyed");
        Parked before = ParkedRows.byTheWorker(old.id(), "shop/keyed", 1);   // no key: a row of a keyed flow parked before V17
        ParkedRows.park(pool, before, "node-a");
        Answer refused = restart(old.id());
        refusedNaming(refused, 422, old.id(), "a keyed flow's row without its key");
        String error = refused.json().getString("error");
        assertTrue(error.contains("parked before its key was kept") && error.contains("/retry"), "the error says why and what to use instead: " + error);
        assertEquals(Optional.of(before), dlqRow(old.id()), "its DLQ row kept");
        assertEquals(0, count("select count(*) from work"), "nothing written");

        Envelope kept = recorded("shop/keyed");
        ParkedRows.park(pool, ParkedRows.byTheWorker(kept.id(), "shop/keyed", 1, Optional.of("c1")), "node-a");
        Answer restarted = restart(kept.id());
        assertEquals(202, restarted.status(), "witness: the same flow's row with its key kept restarts: " + restarted.body());
        assertEquals(1, count("select count(*) from work where id = $1 and partition_key = 'c1'", kept.id()), "enqueued under the kept key c1");
    }
}
