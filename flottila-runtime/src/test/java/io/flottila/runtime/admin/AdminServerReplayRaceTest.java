package io.flottila.runtime.admin;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.deploy.PackageDraft;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.flottila.runtime.admin.AdminServerReplayTest.BOUND;
import static io.flottila.runtime.admin.AdminServerReplayTest.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09c2 (D-067): what changes between the admin's own lookup and the replay's run is answered by the replay's refusal — a
 * message whose rows are gone by then is 404, a DLQ row removed by then (another node's retry) is 404, and a flow replaced by a
 * redeploy by then (its replay closed) is 409 — and nothing runs. A second retry of an id whose first retry runs on this node is 409
 * and runs nothing; once the first answered, the id can be retried again. Each window is opened at the store boundary: the store
 * answers the replay's own read of the message (its second) as the case needs.
 */
class AdminServerReplayRaceTest {

    static final String AUTH = "Bearer " + AdminServerReplayTest.TOKEN;

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final SecondReads<List<Snapshot>> timelines = new SecondReads<>();
    final SecondReads<Optional<Parked>> rows = new SecondReads<>();
    final InMemoryMessageStore inner = new InMemoryMessageStore();
    final InMemoryDlq innerDlq = new InMemoryDlq();
    final InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
    final AdminServerReplayTest.Probe probe = new AdminServerReplayTest.Probe();
    Deployments deployments;
    AdminServer server;
    int port;

    /** For one id at a time, its second read — the replay's own, after the admin's — answers through {@link #then} instead of the store. */
    static final class SecondReads<T> {
        final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
        volatile String id;
        volatile Function<Future<T>, Future<T>> then;

        Future<T> read(String messageId, Future<T> stored) {
            int n = reads.computeIfAbsent(messageId, k -> new AtomicInteger()).incrementAndGet();
            return messageId.equals(id) && n == 2 ? then.apply(stored) : stored;
        }

        void after(String messageId, Function<Future<T>, Future<T>> answer) {
            reads.remove(messageId);
            then = answer;
            id = messageId;
        }
    }

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
        MessageStore messages = new MessageStore() {
            @Override public Future<Void> record(Snapshot s) { return inner.record(s); }
            @Override public Future<List<Snapshot>> timeline(String messageId) { return timelines.read(messageId, inner.timeline(messageId)); }
            @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
                return inner.findByKey(name, value, after, limit);
            }
        };
        Dlq dlq = new Dlq() {
            @Override public Future<Void> park(Parked parked) { return innerDlq.park(parked); }
            @Override public Future<Page<Parked>> list(Optional<String> after, int limit) { return innerDlq.list(after, limit); }
            @Override public Future<Optional<Parked>> get(String messageId) { return rows.read(messageId, innerDlq.get(messageId)); }
            @Override public Future<Void> remove(String messageId) { return innerDlq.remove(messageId); }
        };
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), probe.factories(), List.of(),
                messages, dlq, queue, () -> 0L);
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(AdminServerReplayTest.TOKEN)), messages, dlq, rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments), queue, new FakeIds("replay"));
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), AdminServerReplayTest.shop().zipTo(tmp.resolve("archives"))));
    }

    @AfterEach
    void stop() throws Exception {
        probe.holds.forEach(AdminServerReplayTest.Hold::release);
        doors.releaseAll();
        server.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    record Answer(int status, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    /** A POST with the token; the response is paused in its head callback, so no byte of it arrives before its body is asked for. */
    Future<Answer> post(String path) {
        return client.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    req.putHeader("Authorization", AUTH);
                    req.response().onSuccess(HttpClientResponse::pause);
                    return req.send();
                })
                .compose(res -> {
                    Future<Buffer> body = res.body();
                    res.resume();
                    return body.map(b -> new Answer(res.statusCode(), b));
                });
    }

    static void assertError(int status, Answer a, String what) {
        assertEquals(status, a.status(), what + ": " + a.body());
        assertEquals(Set.of("error"), a.json().fieldNames(), what + ": " + a.body());
    }

    Envelope delivered(String flow) throws Exception {
        Envelope e = rt.ingress("shop/" + flow, Headers.empty(), Buffer.buffer("<order/>"));
        doors.door(e.flow()).deliver(e).await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        return e;
    }

    /** No rows by the time the replay reads them (retention, say): the replay's UNKNOWN_MESSAGE is 404, nothing runs. */
    @Test
    void aMessageWhoseRowsAreGoneByTheRunIs404() throws Exception {
        Envelope original = delivered("orders");
        until(() -> !inner.timeline(original.id()).await().isEmpty(), "fixture: its rows");
        int ran = probe.total();
        timelines.after(original.id(), stored -> Future.succeededFuture(List.of()));

        assertError(404, post("/admin/messages/" + original.id() + "/replay?from=2").await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "rows gone by the run");
        assertEquals(ran, probe.total(), "nothing ran");
    }

    /** The row removed by the time the retry reads it (another node's retry): the replay's NO_DLQ_ROW is 404, nothing runs. */
    @Test
    void aRowRemovedByTheRunIs404() throws Exception {
        probe.down.set(true);
        Envelope parked = delivered("audit");
        until(() -> innerDlq.get(parked.id()).await().isPresent() && queue.snapshot().isEmpty(), "fixture: parked");
        probe.down.set(false);
        int ran = probe.total();
        rows.after(parked.id(), stored -> Future.succeededFuture(Optional.empty()));

        assertError(404, post("/admin/dlq/" + parked.id() + "/retry").await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "row gone by the run");
        assertEquals(ran, probe.total(), "nothing ran");
    }

    /** {@code then} for {@link SecondReads}: signals {@code reached}, then holds the read until {@code go}. */
    static <T> Function<Future<T>, Future<T>> holding(CountDownLatch reached, CountDownLatch go) {
        return stored -> {
            reached.countDown();
            try {
                if (!go.await(BOUND.toSeconds(), TimeUnit.SECONDS)) fail("fixture: the read was never let go");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return stored;
        };
    }

    /**
     * While the first retry of X runs on this node (held in its own read of the row), a second retry of X is 409 and runs nothing;
     * the first, its step still down, answers 502 PARKED and frees the id: the next retry of X runs (200) and removes the row.
     */
    @Test
    void aSecondRetryOfAnIdWhileItsFirstRunsIs409UntilTheFirstAnswered() throws Exception {
        probe.down.set(true);
        Envelope x = delivered("audit");
        until(() -> innerDlq.get(x.id()).await().isPresent() && queue.snapshot().isEmpty(), "fixture: parked");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        rows.after(x.id(), holding(reached, go));
        Future<Answer> first = post("/admin/dlq/" + x.id() + "/retry");
        if (!reached.await(BOUND.toSeconds(), TimeUnit.SECONDS)) fail("the first retry's own read was not reached");
        int ran = probe.total();

        assertError(409, post("/admin/dlq/" + x.id() + "/retry").await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "a retry of X runs already");
        assertEquals(ran, probe.total(), "nothing ran for it");
        go.countDown();
        Answer parkedAgain = first.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(502, parkedAgain.status(), parkedAgain.body().toString());
        assertEquals("PARKED", parkedAgain.json().getString("outcome"));
        assertTrue(innerDlq.get(x.id()).await().isPresent(), "X's row stays");

        probe.down.set(false);
        Answer again = post("/admin/dlq/" + x.id() + "/retry").await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(200, again.status(), "the first retry answered: X can be retried again: " + again.body());
        assertEquals(Optional.empty(), innerDlq.get(x.id()).await(), "and its row is gone");
    }

    /**
     * The admin found the flow's replay; before its run a redeploy replaced the flow and closed that replay: the replay's CLOSED is
     * 409, nothing runs. The flow deployed now replays.
     */
    @Test
    void aFlowReplacedByTheRunIs409() throws Exception {
        Envelope original = delivered("orders");
        until(() -> !inner.timeline(original.id()).await().isEmpty(), "fixture: its rows");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        timelines.after(original.id(), holding(reached, go));
        Future<Answer> replaying = post("/admin/messages/" + original.id() + "/replay?from=2");
        if (!reached.await(BOUND.toSeconds(), TimeUnit.SECONDS)) fail("the replay's own read was not reached");
        PackageDraft v2 = AdminServerReplayTest.shop();
        v2.flowDoc("orders").put("version", "1.0.1");
        assertEquals(List.of("shop/orders"), OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), v2.zipTo(tmp.resolve("v2")))).swapped(),
                "fixture: orders replaced");
        int ran = probe.total();
        go.countDown();

        assertError(409, replaying.await(BOUND.toMillis(), TimeUnit.MILLISECONDS), "the replay found was closed by the redeploy");
        assertEquals(ran, probe.total(), "nothing ran");
        timelines.after("none", stored -> stored);
        Answer now = post("/admin/messages/" + original.id() + "/replay?from=2").await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(200, now.status(), "witness: the flow deployed now replays: " + now.body());
        assertInstanceOf(String.class, now.json().getValue("id"));
        assertTrue(probe.total() > ran);
    }
}
