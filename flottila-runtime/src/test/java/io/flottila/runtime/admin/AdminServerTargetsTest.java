package io.flottila.runtime.admin;

import io.flottila.api.Health;
import io.flottila.api.Runtime;
import io.flottila.core.admission.TargetLimit;
import io.flottila.core.admission.Targets;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.SequentialIntake;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.DirArchiveStore;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.deploy.InMemoryDeploymentStore;
import io.flottila.runtime.lease.InMemoryPollerLeases;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.flottila.runtime.admin.AdminServerDeployTest.Answer;
import static io.flottila.runtime.admin.AdminServerDeployTest.assertError;
import static io.flottila.runtime.admin.AdminServerDeployTest.assertJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-09b (D-099): {@code GET /admin/targets} lists the gauges of the node's {@link Targets} — 200 {@code {items:[{target, maxCalls,
 * maxWaitMs, inFlight, waiting, refused}]}} sorted by target — and never an unshared limit (a component that names no target);
 * it needs the token like every {@code /admin/*} route.
 *
 * <p>The gauges are driven through the core API of the very {@link Targets} the {@link Deployments} was given (acquire, the fake
 * clock past maxWait), so the expected numbers are the case's own. Two limits: {@code http://a.example:80} by an override (1 call,
 * 2.5 s — a maxWait across a whole second) and {@code https://b.example:443} with the defaults (3 calls, 0.75 s — below one second).
 *
 * <p>The shape assumed (P2-09b test-author): the longest {@link Deployments} constructor takes the node's {@link Targets} last
 * ({@code ..., Intake, PollerLeases, Targets}); the route is served where {@code GET /admin/flows} is (with a
 * {@link Deployments}) and lists that Deployments' Targets.
 */
class AdminServerTargetsTest {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final String A = "http://a.example:80";
    static final String B = "https://b.example:443";

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<AdminServer> servers = new ArrayList<>();
    Targets targets;
    Deployments deployments;

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
    void wire() {
        Map<String, String> e = new HashMap<>();
        e.put("FLOTTILA_ENV", "dev");
        e.put("FLOTTILA_NODE_ID", "node-1");
        e.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        targets = new Targets(new Targets.Limit(3, Duration.ofMillis(750)), Map.of(A, new Targets.Limit(1, Duration.ofMillis(2500))), rt.timer());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                queue, () -> 0L, new InMemoryDeploymentStore(), new DirArchiveStore(tmp.resolve("archives")), new SequentialIntake(messages, queue),
                new InMemoryPollerLeases(rt.clock()), targets);
    }

    @AfterEach
    void stop() throws Exception {
        for (AdminServer s : servers) s.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    int serve() throws Exception {
        Map<String, Supplier<Future<Health>>> checks = Map.of();
        AdminServer server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(),
                new AdminServer.HealthProbe(checks, () -> 0L), Optional.of(deployments), new InMemoryWorkQueue(rt.clock()), rt.ids(), Optional.empty());
        int bound = server.start(vertx).await(10, TimeUnit.SECONDS);
        servers.add(server);
        return bound;
    }

    Answer get(int port, String path, String authorization) throws Exception {
        return client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    req.response().onSuccess(HttpClientResponse::pause);   // FIX-21: the body may arrive before the compose below attaches to it
                    return req.send();
                })
                .compose(res -> {
                    Future<Buffer> all = res.body();
                    res.resume();
                    return all.map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b));
                })
                .await(20, TimeUnit.SECONDS);
    }

    /**
     * An unshared limit in use is never listed: with only that, the list is empty. Then B is made first and A second; A has one
     * call in flight, two waiters refused at its maxWait (2.5 s on the fake clock) and one waiting; B two calls in flight. The
     * route lists A before B (sorted by target, not by first use), each with its maxCalls, its maxWait in milliseconds and its
     * counts, and still not the unshared limit.
     */
    @Test
    void targetsRouteListsTheGauges() throws Exception {
        int port = serve();
        TargetLimit unshared = targets.unshared();
        assertTrue(unshared.acquire().succeeded(), "fixture: the unshared limit grants");

        Answer none = get(port, "/admin/targets", AUTH);
        assertJson(200, none, "GET /admin/targets with only an unshared limit");
        assertEquals(new JsonObject("{\"items\": []}"), none.json(), "an unshared limit is not listed");

        TargetLimit b = targets.of(B);
        TargetLimit a = targets.of(A);
        assertTrue(a.acquire().succeeded(), "fixture: A grants its one call");
        Future<TargetLimit.Permit> refused1 = a.acquire();
        Future<TargetLimit.Permit> refused2 = a.acquire();
        rt.clock().advance(Duration.ofMillis(2500));
        assertTrue(refused1.failed() && refused2.failed(), "fixture: both of A's waiters refused at its maxWait");
        Future<TargetLimit.Permit> waiting = a.acquire();
        assertFalse(waiting.isComplete(), "fixture: A's next waiter waits");
        assertTrue(b.acquire().succeeded() && b.acquire().succeeded(), "fixture: B grants two of its three calls");

        Answer listed = get(port, "/admin/targets", AUTH);

        assertJson(200, listed, "GET /admin/targets");
        assertEquals(new JsonObject("""
                {"items": [
                  {"target": "http://a.example:80", "maxCalls": 1, "maxWaitMs": 2500, "inFlight": 1, "waiting": 1, "refused": 2},
                  {"target": "https://b.example:443", "maxCalls": 3, "maxWaitMs": 750, "inFlight": 2, "waiting": 0, "refused": 0}]}
                """), listed.json(), "A (an override) before B (the defaults), sorted by target; the unshared limit is not listed");
    }

    /** Without the token, with a wrong one or without the Bearer scheme the route is 401 {error}, naming no target; with it 200. */
    @Test
    void targetsRouteIs401WithoutTheToken() throws Exception {
        int port = serve();
        assertTrue(targets.of(A).acquire().succeeded(), "fixture: A has a call in flight");
        for (String auth : Arrays.asList(null, "Bearer wrong", TOKEN)) {
            Answer a = get(port, "/admin/targets", auth);
            assertError(401, a, "GET /admin/targets with Authorization " + auth);
            assertFalse(a.body().toString().contains("a.example"), "a refused request learns no target: " + a.body());
        }
        Answer ok = get(port, "/admin/targets", AUTH);
        assertJson(200, ok, "with the token");
        assertEquals(A, ok.json().getJsonArray("items").getJsonObject(0).getString("target"), ok.json().encode());
    }
}
