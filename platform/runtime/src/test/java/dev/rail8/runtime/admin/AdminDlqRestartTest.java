package dev.rail8.runtime.admin;

import dev.rail8.api.BlobKey;
import dev.rail8.api.BlobNotFoundException;
import dev.rail8.api.BodyLostException;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Props;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.flow.FlowBuilder;
import dev.rail8.core.flow.FlowDefinition;
import dev.rail8.core.flow.FlowWorker;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.WorkerPolicy;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Meter;
import dev.rail8.core.record.Page;
import dev.rail8.core.record.Recorder;
import dev.rail8.core.record.RecordingPolicy;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Doors;
import dev.rail8.runtime.deploy.PackageDraft;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.queue.ParkedRows;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FIX-138a moved these to {@code POST /admin/dlq/{id}/retry} (the same-id restart). FIX-263 (D-156: replay is not part of
 * Rail8): each 422 says why the row is not restarted and names no other route; the route needs the token and runs on a virtual
 * thread, never the event loop.
 *
 * <p>CORE-14b P8 / O8 (implementer's cases) of {@code POST /admin/dlq/{id}/retry} beyond {@code AdminDlqRestartIT}, over in-memory
 * stores: a row whose flow is not deployed here is 409 "flow f is not deployed"; a row of a sync flow, and a row at step 1 without an
 * {@code attempt} of at least 1, are 422 — none of them changes anything; a restart the queue answers false (the row went meanwhile)
 * is 404, one it fails because the ingress body is no longer kept is 409, both with nothing woken or announced. A restart that went
 * through wakes its flow's worker here — which claims and runs it at once, as attempt k + 1, though no timer fired — and announces
 * the flow to every node.
 *
 * <p>Seams: a real {@code Deployments} of package {@code shop} ({@code orders} at-least-once, {@code pay} sync, {@code keyed} at-least-once
 * by {@code customer}; its own workers over a
 * queue of their own); the {@link AdminServer} over an {@link InMemoryDlq}, the test's queue (the in-memory one, its restart answer
 * replaceable) and a {@link Workers} registry holding the test's worker of {@code shop/orders} over that queue; the signals record
 * what they are told (the SPI boundary).
 */
@Timeout(60)
class AdminDlqRestartTest {

    static Vertx vertx;
    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    final Workers workers = new Workers();
    final List<String> announced = new CopyOnWriteArrayList<>();
    final List<String> runs = new CopyOnWriteArrayList<>();
    final Doors doors = new Doors();
    /** What the queue's restart answers instead of the in-memory one's; null: the in-memory one. */
    volatile Supplier<Future<Boolean>> restartAnswer;
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
    @TempDir Path tmp;
    Deployments deployments;
    FlowWorker worker;
    AdminServer admin;
    int port;

    @BeforeAll
    static void up() { vertx = Vertx.vertx(); }

    @AfterAll
    static void down() { vertx.close().await(); }

    @BeforeEach
    void wire() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", "node-a");
        env.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        rt.withComponent(doors);
        deployments = new Deployments(NodeConfig.from(env), rt.runtime(), Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(probe),
                List.of(AdminDlqRestartIT.HEADER), store, new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L);
        PackageDraft shop = PackageDraft.of("shop", "1.0.0");
        JsonArray steps = new JsonArray().add(new JsonObject().put("type", "probe").put("name", "probe"));
        shop.endpoint("orders-in", "in", new JsonObject().put("path", "/orders")).flow("orders", "1.0.0", "at-least-once", "orders-in", 65_536, steps);
        shop.endpoint("pay-in", "in", new JsonObject().put("path", "/pay")).flow("pay", "1.0.0", "sync", "pay-in", 65_536, steps);
        shop.endpoint("keyed-in", "in", new JsonObject().put("path", "/keyed")).flow("keyed", "1.0.0", "at-least-once", "keyed-in", 65_536, new JsonArray()
                .add(new JsonObject().put("type", "extractKey").put("name", "key").put("language", "header").put("key", "customer").put("xpath", "x-customer"))
                .add(new JsonObject().put("type", "probe").put("name", "probe")));
        shop.flowDoc("keyed").put("partitionBy", "customer").put("onFailure", "park");
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), shop.zipTo(tmp.resolve("archives"))));
        worker = new FlowBuilder(rt.runtime(), List.of(probe), List.of(), Map.of()).recording(store, Meter.system()).queue(queue).dlq(dlq).workers(workers)
                .worker(orders(), Path.of(""), "node-a", new WorkerPolicy(Duration.ofSeconds(30), Duration.ofDays(7)));
        worker.start();
        WorkSignals signals = (target, flow) -> announced.add(target + " " + flow);
        admin = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.empty()), store, dlq, rt.blobs(), new AdminServer.HealthProbe(Map.of(), () -> 0L),
                Optional.of(deployments), queue, workers, signals);
        port = admin.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        worker.stop();
        admin.stop().await(10, TimeUnit.SECONDS);
        doors.releaseAll();
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    /** {@code shop/orders} as the test's worker runs it: one probe, the builder's default retries (a bound of 4). */
    static FlowDefinition orders() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("kind", "flow");
        doc.put("id", "shop/orders");
        doc.put("version", "1.0.0");
        doc.put("from", Map.of("endpoint", "in"));
        doc.put("delivery", Map.of("mode", "at-least-once", "timeout", "1h"));
        doc.put("limits", Map.of("maxBodyBytes", 65_536, "maxInFlight", 2));
        doc.put("steps", List.of(Map.of("type", "probe", "name", "probe")));
        return FlowDefinition.from(doc);
    }

    /** The in-memory queue, its restart answered by {@link #restartAnswer} when set. */
    final WorkQueue queue = new WorkQueue() {
        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return rows.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return rows.claim(workerId, flow, max, lease); }
        @Override public Future<Boolean> complete(long seq, int attempts) { return rows.complete(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            return rows.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return rows.renew(seq, attempts, lease); }
        @Override public Future<Boolean> defer(long seq, int attempts, Duration delay) { return rows.defer(seq, attempts, delay); }
        @Override public Future<Boolean> restart(String messageId, String flow, String partitionKey, int attempt) {
            Supplier<Future<Boolean>> answer = restartAnswer;
            return answer == null ? rows.restart(messageId, flow, partitionKey, attempt) : answer.get();
        }
        @Override public Future<Boolean> hold(long seq, int attempts) { return rows.hold(seq, attempts); }
        @Override public Future<Page<Held>> held(Optional<String> after, int limit) { return rows.held(after, limit); }
        @Override public Future<Boolean> release(long seq) { return rows.release(seq); }
        @Override public Future<Boolean> hasItems(String flow) { return rows.hasItems(flow); }
    };

    record Answer(int status, JsonObject json) {}

    Answer retry(String id) throws Exception {
        HttpResponse<String> res = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/dlq/" + id + "/retry")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Answer(res.statusCode(), new JsonObject(res.body()));
    }

    /** A message of {@code shop/orders} whose ingress is recorded (what a restarted run reads); nothing queued. Returns its id. */
    String recorded() {
        Envelope in = rt.ingress("shop/orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        return in.id();
    }

    /** {@code id} of {@code flow} parked at {@code step} with {@code props}. */
    Parked parked(String id, String flow, int step, Props props) {
        Parked p = ParkedRows.row(id, flow, step, props);
        dlq.park(p).await();
        return p;
    }

    Answer refused(String id, Parked kept, int status, String says) throws Exception {
        Answer a = retry(id);
        assertEquals(status, a.status(), says + ": " + a.json());
        String error = a.json().getString("error");
        assertTrue(error.contains(id) && error.contains(says), "the error names the message and says " + says + ": " + error);
        assertEquals(Optional.of(kept), dlq.get(id).await(), "its DLQ row kept");
        assertEquals(0, rows.size(), "nothing queued");
        assertEquals(List.of(), announced, "nothing announced");
        assertEquals(List.of(), runs, "nothing run");
        return a;
    }

    /**
     * FIX-263 (D-156): a 422 of {@code /retry} says why and names no other route — no {@code /replay}, no "use /..." pointer,
     * no {@code /admin} path at all (a pointer to a route names its path, whatever its wording) — and never the route it was
     * refused on.
     */
    static void namesNoOtherRoute(Answer a) {
        String error = a.json().getString("error");
        assertFalse(error.contains("/replay"), "no pointer to /replay: " + error);
        assertFalse(error.contains("use /"), "no route to use instead: " + error);
        assertFalse(error.contains("/retry"), "never the route it was refused on: " + error);
        assertFalse(error.contains("/admin"), "no pointer to any admin route: " + error);
    }

    @Test
    void aFlowNotDeployedHereIs409() throws Exception {
        Parked p = parked("m-1", "shop/gone", 1, Props.empty().with(Envelope.ATTEMPT, 1));
        refused("m-1", p, 409, "flow shop/gone is not deployed");
    }

    @Test
    void aSyncFlowsRowIs422() throws Exception {
        Parked p = parked("m-1", "shop/pay", 1, Props.empty().with(Envelope.ATTEMPT, 1));
        namesNoOtherRoute(refused("m-1", p, 422, "the flow is sync"));
    }

    @Test
    void aRowWithoutAnAttemptOfAtLeastOneIs422() throws Exception {
        Parked none = parked("m-1", "shop/orders", 1, Props.empty());
        namesNoOtherRoute(refused("m-1", none, 422, "was not parked by its flow's worker"));
        Parked zero = parked("m-2", "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 0));
        namesNoOtherRoute(refused("m-2", zero, 422, "was not parked by its flow's worker"));
    }

    /**
     * FIX-263 A2 (D-156): every 422 of {@code /retry} says why the row is not restarted — the flow is not at-least-once; a row its
     * flow's worker did not park, with its step and attempt (at step 2; at step 1 without an attempt, with attempt 0, a replay's
     * row, which keeps its words ", a replay" until FIX-265); a keyed flow's row parked before its key was kept — and none names
     * another route. Each refusal keeps its row and writes, runs and announces nothing. Witness: a row the worker parked restarts.
     */
    @Test
    void noRetryRefusalNamesAnotherRoute() throws Exception {
        Map<String, String> says = new java.util.LinkedHashMap<>();
        Map<String, Parked> refusedRows = new java.util.LinkedHashMap<>();
        refusedRows.put("m-sync", parked("m-sync", "shop/pay", 1, Props.empty().with(Envelope.ATTEMPT, 1)));
        says.put("m-sync", "message m-sync of flow shop/pay is not restarted: the flow is sync, only an at-least-once flow's worker runs a restart");
        refusedRows.put("m-step2", parked("m-step2", "shop/orders", 2, Props.empty().with(Envelope.ATTEMPT, 1)));
        says.put("m-step2", "message m-step2 was not parked by its flow's worker (step 2, attempt 1)");
        refusedRows.put("m-none", parked("m-none", "shop/orders", 1, Props.empty()));
        says.put("m-none", "message m-none was not parked by its flow's worker (step 1, attempt ");
        refusedRows.put("m-zero", parked("m-zero", "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 0)));
        says.put("m-zero", "message m-zero was not parked by its flow's worker (step 1, attempt 0)");
        refusedRows.put("m-replay", parked("m-replay", "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 1).with(Envelope.REPLAY_OF, "m-original")));
        says.put("m-replay", "message m-replay was not parked by its flow's worker (step 1, attempt 1, a replay)");
        refusedRows.put("m-keyless", parked("m-keyless", "shop/keyed", 1, Props.empty().with(Envelope.ATTEMPT, 1)));
        says.put("m-keyless", "message m-keyless of the keyed flow shop/keyed was parked before its key was kept");

        for (Map.Entry<String, Parked> row : refusedRows.entrySet()) {
            String id = row.getKey();
            Answer a = refused(id, row.getValue(), 422, says.get(id));
            namesNoOtherRoute(a);
        }

        String witness = recorded();
        parked(witness, "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 1));
        Answer restarted = retry(witness);
        assertEquals(202, restarted.status(), "witness: a row the worker parked restarts on the same wiring: " + restarted.json());
        assertEquals(new JsonObject().put("id", witness).put("attempt", 2), restarted.json(), "witness: restarted as attempt 2");
    }

    /**
     * FIX-263 (moved from the withdrawn replay route's case, D-156): {@code /retry}'s handler runs off the event loop, on a
     * virtual thread — witnessed where it reaches the store boundary, the queue's restart, by the thread that called it.
     */
    @Test
    void aRetryRunsOnAVirtualThreadNeverTheEventLoop() throws Exception {
        List<String> restartedOn = new CopyOnWriteArrayList<>();
        String id = recorded();
        restartAnswer = () -> {
            Thread t = Thread.currentThread();
            restartedOn.add("virtual=" + t.isVirtual() + " eventLoop=" + io.vertx.core.Context.isOnEventLoopThread() + " " + t);
            return rows.restart(id, "shop/orders", null, 1);
        };
        parked(id, "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 1));

        Answer a = retry(id);

        assertEquals(202, a.status(), "restarted: " + a.json());
        assertEquals(1, restartedOn.size(), "the queue's restart was asked once: " + restartedOn);
        assertTrue(restartedOn.getFirst().startsWith("virtual=true eventLoop=false"), "the route's body runs on a virtual thread: " + restartedOn);
    }

    /**
     * FIX-263 (moved from the withdrawn replay route's case, D-156): on a server with a token, {@code /retry} without it or with a
     * wrong one is 401 and changes nothing — its row kept, nothing queued, run or announced. Witness: with the token the same
     * request restarts the row (202).
     */
    @Test
    void aRetryNeedsTheToken() throws Exception {
        String token = "s3cret-token";
        AdminServer guarded = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(token)), store, dlq, rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments), queue, workers, (target, flow) -> announced.add(target + " " + flow));
        int guardedPort = guarded.start(vertx).await(10, TimeUnit.SECONDS);
        try {
            String id = recorded();
            Parked p = parked(id, "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 1));
            for (String auth : java.util.Arrays.asList(null, "Bearer wrong")) {
                HttpResponse<String> res = post(guardedPort, "/admin/dlq/" + id + "/retry", auth);
                assertEquals(401, res.statusCode(), "retry with " + auth + ": " + res.body());
                assertEquals(Optional.of(p), dlq.get(id).await(), "its DLQ row kept");
                assertEquals(0, rows.size(), "nothing queued");
                assertEquals(List.of(), announced, "nothing announced");
                assertEquals(List.of(), runs, "nothing run");
            }
            HttpResponse<String> ok = post(guardedPort, "/admin/dlq/" + id + "/retry", "Bearer " + token);
            assertEquals(202, ok.statusCode(), "witness: with the token the same retry restarts it: " + ok.body());
            assertEquals(new JsonObject().put("id", id).put("attempt", 2), new JsonObject(ok.body()));
        } finally {
            guarded.stop().await(10, TimeUnit.SECONDS);
        }
    }

    /** {@code POST path} on {@code port} with an empty body; {@code authorization} as given (null: no header). */
    static HttpResponse<String> post(int port, String path, String authorization) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).POST(HttpRequest.BodyPublishers.noBody());
        if (authorization != null) req.header("Authorization", authorization);
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRetryTheQueueAnswersFalseIs404() throws Exception {
        Parked p = parked("m-1", "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 1));
        restartAnswer = () -> Future.succeededFuture(false);
        refused("m-1", p, 404, "any more");
    }

    @Test
    void aRetryWhoseIngressBodyIsNoLongerKeptIs409() throws Exception {
        Parked removed = parked("m-1", "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 1));
        restartAnswer = () -> Future.failedFuture(new BlobNotFoundException(BlobKey.body("m-1", 0)));
        refused("m-1", removed, 409, "its ingress body is no longer kept");
        Parked lost = parked("m-2", "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 1));
        restartAnswer = () -> Future.failedFuture(new BodyLostException(BlobKey.body("m-2", 0)));
        refused("m-2", lost, 409, "its ingress body is no longer kept");
    }

    @Test
    void aRetryWakesItsFlowHereAndAnnouncesItToEveryNode() throws Exception {
        Envelope in = rt.ingress("shop/orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order id=\"1\"/>"));
        store.record(new Recorder(store, rt.clock(), Meter.system(), Map.of()).startSnapshot(in, Recorder.INGRESS, RecordingPolicy.DEFAULT)).await();
        parked(in.id(), "shop/orders", 1, Props.empty().with(Envelope.ATTEMPT, 2));

        Answer a = retry(in.id());
        assertEquals(202, a.status(), "restarted: " + a.json());
        assertEquals(new JsonObject().put("id", in.id()).put("attempt", 3), a.json());
        assertEquals(List.of("null shop/orders"), announced, "its flow announced to every node: the queue tells no body node");
        until(() -> runs.size() == 1, "the worker woken here claims and runs it, no timer fired");
        assertEquals(List.of(in.id() + "@3"), runs, "once, under its id, as attempt k + 1");
        until(() -> rows.size() == 0, "completed");
    }

    static void until(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
        }
    }
}
