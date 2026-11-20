package dev.rail8.runtime.admin;

import dev.rail8.api.Ack;
import dev.rail8.api.Envelope;
import dev.rail8.api.Headers;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.queue.InMemoryFailedMessages;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.Snapshot;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Doors;
import dev.rail8.runtime.deploy.PackageDraft;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.queue.ParkedRows;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Promise;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code POST /admin/messages/{id}/cancel} over the in-memory queue and acts, with a real {@code Deployments} of package
 * {@code shop} on node {@code node-a}: {@code orders} is at-least-once, its one step {@code wait} waits for good. A message
 * running here stops at once — its history's FAILED row at step 1 says who cancelled it and why — and its item completes with no
 * clock tick: 202 naming this node. A queued message is marked, 202 with no node and the flow announced to every node, and its
 * next hand-out carries the cancel; a repeat answers the first cancel. A parked message is cancelled as the DLQ route does it,
 * 200 {@code state: "ended"}. A held row is 409 naming it, an unknown message 404 saying that sync and at-most-once runs are not
 * cancelled, a body other than none or {@code {"reason"}} 400 before the act.
 */
@Timeout(60)
class AdminServerMessageCancelTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    static Vertx vertx;
    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore store = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock(), dlq);
    final Doors doors = new Doors();
    final List<String> announced = new CopyOnWriteArrayList<>();
    /** How many runs entered the {@code wait} step, which waits on a future nothing completes until its run is stopped. */
    final AtomicInteger waiting = new AtomicInteger();
    final StepFactory wait = new StepFactory() {
        @Override public String type() { return "wait"; }
        @Override public String configSchema() { return "{}"; }
        @Override public Stage create(StepConfig step, StepResources resources) {
            return Stage.of(e -> {
                waiting.incrementAndGet();
                Promise.<Void>promise().future().await();
                return e;
            });
        }
    };
    @TempDir Path tmp;
    Deployments deployments;
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
        deployments = new Deployments(NodeConfig.from(env), rt.runtime(), Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(wait),
                List.of(), store, dlq, rows, () -> 0L);
        PackageDraft shop = PackageDraft.of("shop", "1.0.0");
        JsonArray steps = new JsonArray().add(new JsonObject().put("type", "wait").put("name", "wait"));
        shop.endpoint("orders-in", "in", new JsonObject().put("path", "/orders")).flow("orders", "1.0.0", "at-least-once", "orders-in", 65_536, steps);
        OnVirtualThread.call(() -> deployments.deploy("shop", shop.folderIn(tmp.resolve("folders"))));
        admin = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.empty()), store, dlq, rt.blobs(), new AdminServer.HealthProbe(Map.of(), () -> 0L),
                Optional.of(deployments), rows, new InMemoryFailedMessages(rows), Optional.empty(), new Workers(), (target, flow) -> announced.add(target + " " + flow));
        port = admin.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        admin.stop().await(10, TimeUnit.SECONDS);
        doors.releaseAll();
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    static void until(BooleanSupplier condition, Supplier<String> what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) fail("not within 10 s: " + what.get());
            Thread.onSpinWait();
        }
    }

    HttpResponse<String> cancel(String id, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/messages/" + id + "/cancel"))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(req::header);
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** {@code id}'s rows of its history that failed. */
    List<Snapshot> failures(String id) {
        return store.timeline(id).await().stream().filter(s -> s.outcome() == Snapshot.Outcome.FAILED).toList();
    }

    @Test
    void aRunningMessageStopsAtOnceOnTheNodeThatTookTheCancel() throws Exception {
        Envelope in = rt.ingress("shop/orders", Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order/>"));
        assertEquals(new Ack.Accepted(in.id()), doors.door("shop/orders").deliver(in).await(10, TimeUnit.SECONDS));
        until(() -> waiting.get() == 1, () -> "the run waits in its step");

        HttpResponse<String> res = cancel(in.id(), "{\"reason\": \"wrong order\"}", Map.of("X-Rail8-Actor", "ops-anna"));
        assertEquals(202, res.statusCode(), res.body());
        JsonObject body = new JsonObject(res.body());
        assertEquals(in.id(), body.getString("id"));
        assertEquals(1L, body.getLong("auditId"), "the in-memory acts count events from 1");
        assertEquals(rt.clock().instant(), Instant.parse(body.getString("cancelledAt")));
        assertEquals("stopping", body.getString("state"));
        assertEquals("node-a", body.getString("node"), "the holder of the row's live lease: this node");

        until(() -> rows.size() == 0, () -> "the item completed with no clock tick: the run was stopped here at once");
        List<Snapshot> failed = failures(in.id());
        assertEquals(1, failed.size(), "one FAILED row: " + store.timeline(in.id()).await());
        assertEquals(1, failed.getFirst().step(), "at the step where the run stopped");
        assertTrue(failed.getFirst().error().orElseThrow().contains("cancelled by ops-anna: wrong order"), failed.getFirst().error().orElseThrow());
        assertEquals(Optional.empty(), dlq.get(in.id()).await(), "not parked");
        assertEquals(1, waiting.get(), "it did not run again");
        assertEquals(List.of(), announced, "a live holder: nothing announced");
    }

    @Test
    void aQueuedMessageIsMarkedAndItsNextHandOutCarriesTheCancel() throws Exception {
        rows.enqueue("q1", "shop/orders", 1, null).await();

        HttpResponse<String> res = cancel("q1", null, Map.of());
        assertEquals(202, res.statusCode(), res.body());
        JsonObject body = new JsonObject(res.body());
        assertEquals("stopping", body.getString("state"));
        assertTrue(body.containsKey("node") && body.getValue("node") == null, "no one holds it: " + body);
        assertEquals(List.of("null shop/orders"), announced, "the flow announced to every node: its row ends at its next claim");

        HttpResponse<String> again = cancel("q1", "{\"reason\": \"twice\"}", Map.of("X-Rail8-Actor", "ops-ben"));
        assertEquals(202, again.statusCode(), again.body());
        assertEquals(body.getLong("auditId"), new JsonObject(again.body()).getLong("auditId"), "a repeat answers the first cancel");

        WorkQueue.Item item = rows.claim("n2", "shop/orders", 10, LEASE).await().getFirst();
        assertEquals(Optional.of(new WorkQueue.Cancel(body.getLong("auditId"), "unknown", Optional.empty())), item.cancel(),
                "handed out with the first cancel: its actor (none declared) and no reason");
    }

    @Test
    void aParkedMessageIsCancelledAsTheDlqRouteDoes() throws Exception {
        WorkQueue.Item item = rows.enqueueLeased("p1", "t", 1, "K", "n1", LEASE).await().orElseThrow();
        assertTrue(rows.stop(item.seq(), item.attempts(), ParkedRows.byTheWorker("p1", "t", 1, Optional.of("K"))).await(), "fixture: p1 parked and stopped");

        HttpResponse<String> res = cancel("p1", null, Map.of());
        assertEquals(200, res.statusCode(), res.body());
        JsonObject body = new JsonObject(res.body());
        assertEquals("ended", body.getString("state"));
        assertEquals("p1", body.getString("id"));
        assertEquals(Optional.empty(), dlq.get("p1").await(), "its DLQ row is gone");
        assertEquals(0, rows.size(), "its stopped row too");
    }

    @Test
    void aHeldRowIs409AndAnUnknownMessage404() throws Exception {
        WorkQueue.Item item = rows.enqueueLeased("h1", "shop/orders", 1, null, "n1", LEASE).await().orElseThrow();
        assertTrue(rows.hold(item.seq(), item.attempts()).await(), "fixture: held");

        HttpResponse<String> held = cancel("h1", null, Map.of());
        assertEquals(409, held.statusCode(), held.body());
        assertTrue(new JsonObject(held.body()).getString("error").contains("work item " + item.seq()), held.body());

        HttpResponse<String> unknown = cancel("nobody", null, Map.of());
        assertEquals(404, unknown.statusCode(), unknown.body());
        String error = new JsonObject(unknown.body()).getString("error");
        assertTrue(error.contains("sync and at-most-once runs are not cancelled; their time limit ends them"), error);
        assertEquals(List.of(), announced);
    }

    @Test
    void aBodyOtherThanAReasonIs400BeforeTheAct() throws Exception {
        rows.enqueue("q1", "shop/orders", 1, null).await();
        HttpResponse<String> res = cancel("q1", "{\"why\": \"no\"}", Map.of());
        assertEquals(400, res.statusCode(), res.body());
        assertEquals(Optional.empty(), rows.claim("n2", "shop/orders", 10, LEASE).await().getFirst().cancel(), "nothing marked");
    }
}
