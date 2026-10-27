package io.flottila.runtime.admin;

import io.flottila.api.Ack;
import io.flottila.api.Body;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.IdGenerator;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.deploy.PackageDraft;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09c2 (D-067, GATE check 7): {@code POST /admin/messages/{id}/replay?from=n} and {@code POST /admin/dlq/{id}/retry} over a
 * real {@link Deployments} (in-memory stores, a fake runtime, the {@link Doors} component, the steps of {@link Probe}). A run
 * answers 200 {@code {id, replayOf, from, outcome:"COMPLETE"}} with the id the server's {@link IdGenerator} minted; a run that
 * failed answers 502 with the same fields, {@code outcome} FAILED or PARKED, and {@code error}. Refusals in the status order
 * 400 (from missing, not an int, outside 1..steps) → 404 (no timeline, no DLQ row) → 409 (the flow does not run here; a retry of
 * the id already runs on this node) → 422 (step n−1 unrecorded or metadata-only, the parked step beyond the redeployed flow, a
 * body above the flow's byte budget) → 429 + {@code retry-after: 1} (no permit of the flow's admission, which the deliveries
 * share) — and nothing runs for any of them. The run is on a virtual thread, never the event loop. A retry that delivered but
 * whose row removal failed is 500 naming the new id.
 *
 * <p>The shape assumed (P1-09c2 test-author): {@code AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe,
 * Optional<Deployments>, WorkQueue, IdGenerator)}; the retry guard (one retry per id at a time) is the server's, node-local.
 */
class AdminServerReplayTest {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final Duration BOUND = Duration.ofSeconds(20);

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final RemovalFailingDlq dlq = new RemovalFailingDlq(new InMemoryDlq());
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    final FakeIds replayIds = new FakeIds("replay");
    final List<String> minted = new CopyOnWriteArrayList<>();
    final Probe probe = new Probe();
    Deployments deployments;
    AdminServer server;
    int port;

    @BeforeAll
    static void up() {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();
    }

    @AfterAll
    static void down() {
        client.close().await();
        vertx.close().await();
    }

    @BeforeEach
    void wire() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        env.put("FLOTTILA_STOP_DEADLINE", "PT37S");
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), probe.factories(), List.of(),
                messages, dlq, queue, () -> 0L);
        IdGenerator ids = () -> {
            String id = replayIds.next();
            minted.add(id);
            return id;
        };
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments), queue, ids);
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        probe.holds.forEach(Hold::release);
        doors.releaseAll();
        server.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    // --- the steps (the SPI boundary of a step factory)

    /**
     * {@code mark} counts its runs by step name and adds a header; {@code flaky} counts, fails with "&lt;name&gt; down" while
     * {@link #down} is set, and records the thread of every replayed run (an envelope carrying {@code replayOf}); {@code hold}
     * holds the run that takes the next armed {@link Hold} until it is released; {@code grow} writes a 32-byte body.
     */
    static final class Probe {
        final Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
        final AtomicBoolean down = new AtomicBoolean();
        final List<String> replayThreads = new CopyOnWriteArrayList<>();
        final Queue<Hold> armed = new ConcurrentLinkedQueue<>();
        final List<Hold> holds = new CopyOnWriteArrayList<>();

        int total() {
            return runs.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        int runs(String step) {
            AtomicInteger n = runs.get(step);
            return n == null ? 0 : n.get();
        }

        private void ran(String step) {
            runs.computeIfAbsent(step, s -> new AtomicInteger()).incrementAndGet();
        }

        /** The next run that reaches a {@code hold} step is held there until the returned hold is released. */
        Hold holdNext() {
            Hold h = new Hold();
            holds.add(h);
            armed.add(h);
            return h;
        }

        List<StepFactory> factories() {
            return List.of(
                    factory("mark", (s, res) -> Stage.of(e -> {
                        ran(s.name());
                        return e.withHeader("seen-" + s.name(), "yes");
                    })),
                    factory("flaky", (s, res) -> Stage.of(e -> {
                        ran(s.name());
                        if (e.get(Envelope.REPLAY_OF).isPresent()) {
                            Thread t = Thread.currentThread();
                            replayThreads.add("virtual=" + t.isVirtual() + " eventLoop=" + Context.isOnEventLoopThread() + " " + t);
                            Hold h = armed.poll();   // a retry runs only c: its hold is taken here, before c fails
                            if (h != null) h.hold(e.id());
                        }
                        if (down.get()) throw new IllegalStateException(s.name() + " down");
                        return e;
                    })),
                    factory("hold", (s, res) -> Stage.of(e -> {
                        ran(s.name());
                        Hold h = armed.poll();   // taken before it signals: a hold armed after this run is the next run's
                        if (h != null) h.hold(e.id());
                        return e;
                    })),
                    factory("grow", (s, res) -> Stage.of(e -> {
                        ran(s.name());
                        Body grown = res.blobs().write(e.nextKey(), Buffer.buffer("x".repeat(32)), 32).await();
                        return e.next(grown);
                    })));
        }

        static StepFactory factory(String type, java.util.function.BiFunction<StepConfig, StepResources, Stage> make) {
            return new StepFactory() {
                @Override public String type() { return type; }
                @Override public String configSchema() { return "{}"; }
                @Override public Stage create(StepConfig step, StepResources res) { return make.apply(step, res); }
            };
        }
    }

    /** One held run: {@link #reached} once a run took it (that run's message id in {@link #messageId}), held until {@link #release}. */
    static final class Hold {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile String messageId;

        void hold(String id) {
            messageId = id;
            reached.countDown();
            try {
                if (!release.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("hold: the run of " + id + " was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("hold: interrupted holding " + id, interrupted);
            }
        }

        String awaitReached(String what) throws InterruptedException {
            if (!reached.await(BOUND.toSeconds(), TimeUnit.SECONDS)) fail(what + ": no run reached the hold within " + BOUND);
            return messageId;
        }

        void release() {
            release.countDown();
        }
    }

    // --- the store boundary: a DLQ whose removal can fail

    static final class RemovalFailingDlq implements Dlq {
        final InMemoryDlq inner;
        volatile boolean failRemoves;

        RemovalFailingDlq(InMemoryDlq inner) { this.inner = inner; }

        @Override public Future<Void> park(Parked parked) { return inner.park(parked); }
        @Override public Future<Page<Parked>> list(Optional<String> after, int limit) { return inner.list(after, limit); }
        @Override public Future<Optional<Parked>> get(String messageId) { return inner.get(messageId); }
        @Override public Future<Void> remove(String messageId) {
            return failRemoves ? Future.failedFuture(new IllegalStateException("the DLQ row of " + messageId + " could not be removed")) : inner.remove(messageId);
        }
    }

    // --- fixture

    static final JsonArray STEPS = new JsonArray()
            .add(new JsonObject().put("type", "mark").put("name", "a"))
            .add(new JsonObject().put("type", "mark").put("name", "b"))
            .add(new JsonObject().put("type", "hold").put("name", "h"))
            .add(new JsonObject().put("type", "flaky").put("name", "c"));

    /**
     * {@code shop} 1.0.0, each flow from its own in endpoint: {@code orders} (sync), {@code audit} (at-least-once), {@code ends} (sync,
     * recording level ends) and {@code meta} (sync, payload metadata-only) run a, b, h, c (c is step 4) with one request in flight;
     * {@code big} (sync, 16-byte budget) runs grow (a 32-byte body at step 1), then b; {@code extra} flows are added as given (id → mode).
     */
    static PackageDraft shop(Map<String, String> extra) {
        PackageDraft p = PackageDraft.of("shop", "1.0.0");
        Map<String, String> flows = new java.util.LinkedHashMap<>(Map.of("orders", "sync", "audit", "at-least-once", "ends", "sync", "meta", "sync"));
        flows.putAll(extra);
        flows.forEach((id, mode) -> {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", mode, id + "-in", 1024, STEPS.copy());
            p.flowDoc(id).getJsonObject("limits").put("maxInFlight", 1);
        });
        p.flowDoc("ends").put("recording", new JsonObject().put("level", "ends"));
        p.flowDoc("meta").put("recording", new JsonObject().put("payload", "metadata-only"));
        p.endpoint("big-in", "in", new JsonObject().put("path", "/big")).flow("big", "1.0.0", "sync", "big-in", 16, new JsonArray()
                .add(new JsonObject().put("type", "grow").put("name", "grow")).add(new JsonObject().put("type", "mark").put("name", "b")));
        p.flowDoc("big").getJsonObject("limits").put("maxInFlight", 1);
        return p;
    }

    static PackageDraft shop() {
        return shop(Map.of());
    }

    void deploy(PackageDraft p) throws Exception {
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), p.zipTo(tmp.resolve("archives"))));
    }

    /** A message delivered through {@code flow}'s consumer as the consumer would; its answer within the bound. */
    Ack deliver(Envelope e) throws Exception {
        return doors.door(e.flow()).deliver(e).await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    }

    Envelope envelope(String flow) {
        return rt.ingress("shop/" + flow, Headers.empty(), Buffer.buffer("<order/>"));
    }

    /** A sync message of {@code flow} that went through: its reply, its rows written. */
    Envelope recorded(String flow) throws Exception {
        Envelope e = envelope(flow);
        assertInstanceOf(Ack.Reply.class, deliver(e), "fixture: " + flow + " answers");
        until(() -> !messages.timeline(e.id()).await().isEmpty(), "fixture: the rows of " + e.id());
        return e;
    }

    /** An at-least-once {@code audit} message whose worker run failed at c (step 4) and parked it. */
    Envelope parked(String flow) throws Exception {
        probe.down.set(true);
        Envelope e = envelope(flow);
        assertInstanceOf(Ack.Accepted.class, deliver(e), "fixture: accepted");
        until(() -> dlq.get(e.id()).await().isPresent(), "fixture: " + e.id() + " parked by its worker");
        assertEquals(4, dlq.get(e.id()).await().orElseThrow().step(), "fixture: parked at c, step 4");
        until(() -> queue.snapshot().isEmpty(), "fixture: its work item completed");
        probe.down.set(false);
        return e;
    }

    record Answer(int status, MultiMap headers, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    Answer send(HttpMethod method, String path, String authorization) throws Exception {
        return client.request(new RequestOptions().setMethod(method).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    req.response().onSuccess(HttpClientResponse::pause);
                    return req.send();
                })
                .compose(res -> {
                    Future<Buffer> body = res.body();
                    res.resume();
                    return body.map(b -> new Answer(res.statusCode(), res.headers(), b));
                })
                .await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
    }

    Answer replay(String id, String from) throws Exception {
        return send(HttpMethod.POST, "/admin/messages/" + id + "/replay" + (from == null ? "" : "?from=" + from), AUTH);
    }

    Answer retry(String id) throws Exception {
        return send(HttpMethod.POST, "/admin/dlq/" + id + "/retry", AUTH);
    }

    /** The answer, sent and not awaited. */
    Future<Answer> retrying(String id) {
        return client.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + "/admin/dlq/" + id + "/retry"))
                .compose(req -> {
                    req.putHeader("Authorization", AUTH);
                    req.response().onSuccess(HttpClientResponse::pause);
                    return req.send();
                })
                .compose(res -> {
                    Future<Buffer> body = res.body();
                    res.resume();
                    return body.map(b -> new Answer(res.statusCode(), res.headers(), b));
                });
    }

    static void assertError(int status, Answer a, String what) {
        assertEquals(status, a.status(), what + ": " + a.body());
        String type = a.headers().get("content-type");
        assertTrue(type != null && type.startsWith("application/json"), what + ": " + type);
        assertEquals(Set.of("error"), a.json().fieldNames(), what + ": nothing but {error}: " + a.body());
        String error = a.json().getString("error");
        assertTrue(error != null && !error.isBlank(), what + ": {error} names the refusal: " + a.body());
    }

    /** 200 {@code {id, replayOf, from, outcome:"COMPLETE"}}, the id the last one minted; returns it. */
    String assertComplete(Answer a, String replayOf, int from, String what) {
        assertEquals(200, a.status(), what + ": " + a.body());
        assertFalse(minted.isEmpty(), what + ": an id was minted");
        String id = minted.getLast();
        assertEquals(new JsonObject().put("id", id).put("replayOf", replayOf).put("from", from).put("outcome", "COMPLETE"), a.json(), what);
        return id;
    }

    /** 502 {@code {id, replayOf, from, outcome, error}} with the minted id; returns it. */
    String assertFailed(Answer a, String replayOf, int from, String outcome, String errorText, String what) {
        assertEquals(502, a.status(), what + ": " + a.body());
        JsonObject body = a.json();
        assertEquals(Set.of("id", "replayOf", "from", "outcome", "error"), body.fieldNames(), what + ": " + body);
        String id = minted.getLast();
        assertEquals(id, body.getString("id"), what + ": the run's own id");
        assertEquals(replayOf, body.getString("replayOf"), what);
        assertEquals(from, body.getInteger("from"), what);
        assertEquals(outcome, body.getString("outcome"), what);
        assertTrue(body.getString("error").contains(errorText), what + ": the error names the failure (" + errorText + "): " + body);
        return id;
    }

    /** No run since {@code ran} step runs: no step ran, and no minted id has a row or a DLQ row. */
    void assertNothingRan(int ran, String what) {
        assertEquals(ran, probe.total(), what + ": no step ran: " + probe.runs);
        for (String id : minted) {
            assertEquals(List.of(), messages.timeline(id).await(), what + ": no row under " + id);
            assertEquals(Optional.empty(), dlq.get(id).await(), what + ": no DLQ row under " + id);
        }
    }

    List<String> stepNames(String id) {
        return messages.timeline(id).await().stream().map(Snapshot::stepName).toList();
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + BOUND.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within " + BOUND + ": " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    // --- replay

    /**
     * A recorded sync message replayed from step 2: 200 with exactly {@code {id, replayOf, from, outcome}} — the id the server
     * minted, the original's id, 2, COMPLETE; only steps 2..4 ran again, as a new message whose rows start with the input it was
     * given (step 1's output) and carry {@code replayOf}.
     */
    @Test
    void replayIs200WithIdReplayOfFromOutcome() throws Exception {
        deploy(shop());
        Envelope original = recorded("orders");
        int a = probe.runs("a"), b = probe.runs("b"), c = probe.runs("c");

        Answer answer = replay(original.id(), "2");

        assertTrue(answer.headers().get("content-type").startsWith("application/json"), answer.headers().get("content-type"));
        String id = assertComplete(answer, original.id(), 2, "the replay");
        assertNotEquals(original.id(), id, "a new message");
        assertEquals(List.of(a, b + 1, c + 1), List.of(probe.runs("a"), probe.runs("b"), probe.runs("c")), "steps 2..4 ran again, step 1 did not");
        List<Snapshot> rows = messages.timeline(id).await();
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(Snapshot::step).toList(), "its rows: the input (step 1's output), then steps 2..4");
        assertEquals(List.of("replay", "b", "h", "c"), rows.stream().map(Snapshot::stepName).toList());
        assertTrue(rows.stream().allMatch(r -> original.id().equals(r.props().asMap().get("replayOf")) && r.flow().equals("shop/orders")),
                "every row of the new message is marked replayOf the original, in the original's flow: " + rows);
    }

    /** A sync replay whose step fails: 502 {@code {id, replayOf, from, outcome:"FAILED", error}}, the error the step's. */
    @Test
    void aFailedRunIs502Failed() throws Exception {
        deploy(shop());
        Envelope original = recorded("orders");
        probe.down.set(true);

        Answer answer = replay(original.id(), "3");

        String id = assertFailed(answer, original.id(), 3, "FAILED", "c down", "a sync replay whose step c failed");
        assertEquals(List.of("replay", "h", "c"), stepNames(id), "the run ran from step 3 and recorded its failure");
        assertEquals(Optional.empty(), dlq.get(id).await(), "a sync flow parks nothing");
    }

    /**
     * An at-least-once message parked at c (step 4); its retry while c still fails parks again: 502 {@code {id, replayOf, from:4,
     * outcome:"PARKED", error}} — the new id has its own DLQ row, the original's stays. A replay of it from step 2 that fails parks
     * too: 502 PARKED.
     */
    @Test
    void aParkedRunIs502Parked() throws Exception {
        deploy(shop());
        Envelope original = parked("audit");
        probe.down.set(true);

        String retried = assertFailed(retry(original.id()), original.id(), 4, "PARKED", "c down", "a retry that parked again");

        assertTrue(dlq.get(retried).await().isPresent(), "the retry parked under its own id");
        assertEquals(original.id(), dlq.get(retried).await().orElseThrow().props().asMap().get("replayOf"), "marked replayOf the original");
        assertTrue(dlq.get(original.id()).await().isPresent(), "the original's row stays");

        String replayed = assertFailed(replay(original.id(), "2"), original.id(), 2, "PARKED", "c down", "a replay that parked");
        assertTrue(dlq.get(replayed).await().isPresent(), "the replay parked under its own id");
    }

    /**
     * {@code from} missing, empty, not an int, or outside 1..4 (the flow's steps) is 400 {@code {error}} and runs nothing — also
     * for a message that does not exist (400 before 404).
     */
    @Test
    void badFromIs400() throws Exception {
        deploy(shop());
        Envelope original = recorded("orders");
        int ran = probe.total();

        assertError(400, replay(original.id(), null), "no from");
        for (String from : List.of("", "x", "1.5", "0", "-1", "5", "99999999999", "0x2")) {
            assertError(400, replay(original.id(), from), "from=" + from);
        }
        for (String from : List.of("x", "0")) {
            assertError(400, replay("nobody", from), "from=" + from + " of an unknown message: 400 before 404");
        }
        assertNothingRan(ran, "a bad from");
        assertComplete(replay(original.id(), "4"), original.id(), 4, "witness: from=4 is the last step");
    }

    /** No timeline, no DLQ row: 404 {@code {error}}, nothing runs — a recorded message without a DLQ row included. */
    @Test
    void unknownIs404() throws Exception {
        deploy(shop());
        Envelope original = recorded("orders");
        int ran = probe.total();

        assertError(404, replay("nobody", "1"), "a replay of a message with no timeline");
        assertError(404, retry("nobody"), "a retry without a DLQ row");
        assertError(404, retry(original.id()), "a retry of a recorded message that was never parked");
        assertNothingRan(ran, "unknown");
    }

    /**
     * A message of a flow that does not run here — {@code old} (sync, level ends) and {@code gone} (at-least-once, parked) were
     * removed by a redeploy — is 409 {@code {error}} for a replay and a retry, and nothing runs. 400 still comes first (from=0,
     * from=x); 409 comes before 422 (step 2 of {@code old} was never recorded). A flow that runs still replays.
     */
    @Test
    void notRunningIs409() throws Exception {
        PackageDraft v1 = shop(Map.of("old", "sync", "gone", "at-least-once"));
        v1.flowDoc("old").put("recording", new JsonObject().put("level", "ends"));
        deploy(v1);
        Envelope old = envelope("old");
        assertInstanceOf(Ack.Reply.class, deliver(old), "fixture: old answers");
        Envelope gone = parked("gone");
        Envelope orders = recorded("orders");
        PackageDraft without = shop();
        deploy(without);
        assertTrue(deployments.flows().stream().noneMatch(f -> f.id().equals("shop/old") || f.id().equals("shop/gone")), "fixture: removed: " + deployments.flows());
        int ran = probe.total();

        assertError(409, replay(old.id(), "1"), "a replay of a removed flow's message");
        assertError(409, replay(old.id(), "3"), "409 before 422: step 2 of old was never recorded (level ends)");
        assertError(409, retry(gone.id()), "a retry of a removed flow's row");
        assertError(400, replay(old.id(), "0"), "400 before 409");
        assertError(400, replay(old.id(), "x"), "400 before 409");
        assertNothingRan(ran, "not running");
        assertTrue(dlq.get(gone.id()).await().isPresent(), "the refused retry keeps its row");
        assertComplete(replay(orders.id(), "1"), orders.id(), 1, "witness: a flow that runs replays");
    }

    /**
     * 422 {@code {error}}, nothing run: step n−1 not recorded ({@code ends} records ingress and the last step only) or recorded
     * metadata-only ({@code meta}). A recorded step of the same flows replays (witness). 422 comes before 429: with {@code ends}'
     * only permit held by a delivery, the unrecorded step is still 422 while a recorded one is 429.
     */
    @Test
    void unrecordedOrMetadataOnlyIs422() throws Exception {
        deploy(shop());
        Envelope ends = recorded("ends");
        Envelope meta = recorded("meta");
        int ran = probe.total();

        assertError(422, replay(ends.id(), "3"), "step 2 of ends was not recorded");
        assertError(422, replay(meta.id(), "2"), "step 1 of meta was recorded metadata-only");
        assertNothingRan(ran, "unrecorded or metadata-only");

        Hold h = probe.holdNext();
        Future<Ack> holding = doors.door("shop/ends").deliver(envelope("ends"));
        h.awaitReached("a delivery of ends holds its only permit");
        int heldRan = probe.total();
        assertError(422, replay(ends.id(), "3"), "422 before 429");
        assertError(429, replay(ends.id(), "1"), "witness: the permit is taken");
        assertNothingRan(heldRan, "refused while the permit is held");
        h.release();
        assertInstanceOf(Ack.Reply.class, holding.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));

        assertComplete(replay(ends.id(), "1"), ends.id(), 1, "witness: ends' ingress was recorded");
    }

    /**
     * The other 422s: the parked step beyond the redeployed flow (audit parked at step 4, v2 runs three steps) and an input body
     * above the flow's byte budget ({@code big}: 16 bytes; step 1 wrote 32). Nothing runs; the row stays; from step 1 (8 bytes)
     * {@code big} replays.
     */
    @Test
    void aStepBeyondTheRedeployedFlowOrABodyAboveTheBudgetIs422() throws Exception {
        deploy(shop());
        Envelope audit = parked("audit");
        Envelope big = recorded("big");
        PackageDraft v2 = shop();
        v2.flowDoc("audit").put("steps", new JsonArray().add(STEPS.getJsonObject(0).copy()).add(STEPS.getJsonObject(1).copy()).add(STEPS.getJsonObject(2).copy()));
        deploy(v2);
        int ran = probe.total();

        assertError(422, retry(audit.id()), "parked at step 4 of a flow that now has 3");
        assertError(422, replay(big.id(), "2"), "step 1's 32-byte body is above big's 16-byte budget");
        assertNothingRan(ran, "beyond or too large");
        assertTrue(dlq.get(audit.id()).await().isPresent(), "the refused retry keeps its row");
        assertComplete(replay(big.id(), "1"), big.id(), 1, "witness: big's 8-byte ingress fits");
    }

    /**
     * No permit: a delivery of orders held at h holds the flow's only permit; a replay is 429 {@code {error}} with
     * {@code retry-after: 1} and runs nothing; 400 and 404 still come first. Once the delivery answered, the replay runs.
     */
    @Test
    void noRoomIs429WithRetryAfter() throws Exception {
        deploy(shop());
        Envelope original = recorded("orders");
        Hold h = probe.holdNext();
        Future<Ack> holding = doors.door("shop/orders").deliver(envelope("orders"));
        h.awaitReached("a delivery of orders holds its only permit");
        int ran = probe.total();

        Answer busy = replay(original.id(), "1");

        assertError(429, busy, "no permit");
        assertEquals("1", busy.headers().get("retry-after"), "retry-after: 1");
        assertError(400, replay(original.id(), "9"), "400 before 429");
        assertError(404, replay("nobody", "1"), "404 before 429");
        assertNothingRan(ran, "no room");

        h.release();
        assertInstanceOf(Ack.Reply.class, holding.await(BOUND.toMillis(), TimeUnit.MILLISECONDS));
        assertComplete(replay(original.id(), "1"), original.id(), 1, "the permit is back");
    }

    /** The run of a replay and of a retry is on a virtual thread, never the event loop (nor a platform worker of executeBlocking). */
    @Test
    void theRunIsOnAVirtualThread() throws Exception {
        deploy(shop());
        Envelope original = recorded("orders");
        Envelope parked = parked("audit");

        assertComplete(replay(original.id(), "4"), original.id(), 4, "the replay");
        assertComplete(retry(parked.id()), parked.id(), 4, "the retry");

        assertEquals(2, probe.replayThreads.size(), "step c ran once per replayed run: " + probe.replayThreads);
        for (String t : probe.replayThreads) assertTrue(t.startsWith("virtual=true eventLoop=false"), "a replayed run's thread: " + t);
    }

    // --- retry

    /**
     * A parked message retried once its step works again: 200 {@code {id, replayOf, from:4, outcome:"COMPLETE"}}; its DLQ row is
     * gone (also from {@code GET /admin/dlq}); the new message ran only the parked step, on the parked input.
     */
    @Test
    void retryIs200AndTheRowIsGone() throws Exception {
        deploy(shop());
        Envelope original = parked("audit");
        int a = probe.runs("a"), c = probe.runs("c");

        String id = assertComplete(retry(original.id()), original.id(), 4, "the retry");

        assertEquals(Optional.empty(), dlq.get(original.id()).await(), "the row is gone");
        Answer listed = send(HttpMethod.GET, "/admin/dlq", AUTH);
        assertEquals(200, listed.status(), listed.body().toString());
        assertEquals(new JsonArray(), listed.json().getJsonArray("items"), "GET /admin/dlq lists no row: " + listed.body());
        assertEquals(List.of("replay", "c"), stepNames(id), "the parked step ran on the parked input");
        assertEquals(List.of(a, c + 1), List.of(probe.runs("a"), probe.runs("c")));
    }

    /**
     * One retry per id at a time on this node: while a retry of X is held at h (holding audit's only permit), a second retry of X
     * is 409 — not 429 — and runs nothing, while a retry of another parked id Y is 429 (the guard is per id, the permit per flow).
     * The first retry, still failing at c, answers 502 PARKED and frees the guard: the next retry of X runs and is 200.
     */
    @Test
    void aConcurrentRetryOfTheSameIdIs409() throws Exception {
        deploy(shop());
        Envelope x = parked("audit");
        Envelope y = parked("audit");
        probe.down.set(true);
        Hold h = probe.holdNext();
        Future<Answer> first = retrying(x.id());
        String firstId = h.awaitReached("the first retry of X is held at h");
        int ran = probe.total();
        List<String> mintedBefore = List.copyOf(minted);

        assertError(409, retry(x.id()), "a retry of X already runs");
        assertError(429, retry(y.id()), "a retry of Y: no permit, no conflict");
        assertEquals(ran, probe.total(), "nothing ran for either");
        for (String id : minted) if (!mintedBefore.contains(id)) assertEquals(List.of(), messages.timeline(id).await(), "no row under " + id);

        h.release();
        Answer parkedAgain = first.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(502, parkedAgain.status(), parkedAgain.body().toString());
        assertEquals(firstId, parkedAgain.json().getString("id"), "the held run's own id");
        assertEquals("PARKED", parkedAgain.json().getString("outcome"));
        assertTrue(dlq.get(x.id()).await().isPresent(), "X's row stays");

        probe.down.set(false);
        assertComplete(retry(x.id()), x.id(), 4, "the guard is free once the first retry answered");
        assertEquals(Optional.empty(), dlq.get(x.id()).await());
    }

    /**
     * The retry delivered (step c ran, its rows written) but the DLQ row could not be removed: 500 {@code {error}} naming the new
     * id; the row stays. The guard is free afterwards: once removal works, the next retry is 200 and the row is gone.
     */
    @Test
    void aRetryWhoseRowRemovalFailsIs500NamingTheNewId() throws Exception {
        deploy(shop());
        Envelope original = parked("audit");
        int c = probe.runs("c");
        dlq.failRemoves = true;

        Answer answer = retry(original.id());

        assertError(500, answer, "the row removal failed");
        String id = minted.getLast();
        assertTrue(answer.json().getString("error").contains(id), "the error names the new id " + id + ": " + answer.body());
        assertEquals(c + 1, probe.runs("c"), "the retry delivered");
        assertEquals(List.of("replay", "c"), stepNames(id), "its rows are written");
        assertTrue(dlq.get(original.id()).await().isPresent(), "the row stays");

        dlq.failRemoves = false;
        assertComplete(retry(original.id()), original.id(), 4, "the next retry");
        assertEquals(Optional.empty(), dlq.get(original.id()).await());
    }

    /** Both routes need the token: 401 without it, and nothing runs. */
    @Test
    void replayAndRetryNeedTheToken() throws Exception {
        deploy(shop());
        Envelope original = recorded("orders");
        Envelope parked = parked("audit");
        int ran = probe.total();

        for (String auth : java.util.Arrays.asList(null, "Bearer wrong")) {
            assertError(401, send(HttpMethod.POST, "/admin/messages/" + original.id() + "/replay?from=1", auth), "replay with " + auth);
            assertError(401, send(HttpMethod.POST, "/admin/dlq/" + parked.id() + "/retry", auth), "retry with " + auth);
        }
        assertNothingRan(ran, "no token");
        assertEquals(List.of(), minted, "no id minted without the token");
        assertTrue(dlq.get(parked.id()).await().isPresent());
    }
}
