package io.flottila.runtime.admin;

import io.flottila.api.Health;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.StepCpu;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.deploy.OptionSchemaSteps;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static io.flottila.runtime.admin.AdminServerDeployTest.Answer;
import static io.flottila.runtime.admin.AdminServerDeployTest.assertError;
import static io.flottila.runtime.admin.AdminServerDeployTest.assertJson;
import static io.flottila.runtime.admin.AdminServerDeployTest.pkg;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-108 (D-094): {@code GET /admin/flows/{flow}/cpu} shows the node's CPU-sampling aggregate of one deployed flow — cumulative
 * since the sampler started, per node: with sampling on, 200 {@code {flow, sampling:"on", period, since, items:[{step, name,
 * executions, samples, cpuNanos}], totals:{samples, attributed, pending, unattributed, platform, lost, dropped}}}, the items by CPU
 * descending (the order {@code StepCpu.rows} gives), the totals node-wide; with sampling off 200 {@code {flow, sampling:"off",
 * items:[]}}. The route exists where {@code GET /admin/flows} does (with a {@link Deployments}), needs the same token, and a flow
 * not deployed here is the usual 404 {@code {error}}. A flow's id is qualified, {@code <package>/<flow>}: it travels as one path
 * segment, its slash percent-encoded ({@code shop%2Forders}).
 *
 * <p>Next to {@link AdminServerDeployTest}, whose fixture (a real {@link Deployments} over in-memory stores, the {@link Doors}
 * component, {@code pkg}) it reuses; the spec named {@code AdminServerTest}, which has no {@link Deployments}.
 *
 * <p>The shape assumed (FIX-108 test-author): {@code AdminServer.Sampling(Duration period, Instant since, StepCpu cpu)}, a nested
 * record like {@link AdminServer.HealthProbe}, and the constructor {@code AdminServer(Config, MessageStore, Dlq, BlobStore,
 * HealthProbe, Optional<Deployments>, WorkQueue, IdGenerator, Optional<AdminServer.Sampling>)}; the earlier constructors stay and
 * mean sampling off. The node gives it {@code new Sampling(sampler.period(), sampler.since(), cpu)} when it runs a sampler.
 *
 * <p>Round 3 (review r1, blocking 2): a stream that ended while the sampler was open makes the route answer
 * {@code sampling:"failed"} with the last aggregate. Shape assumed: {@code AdminServer.Sampling(Duration period, Instant since,
 * StepCpu cpu, BooleanSupplier failed)}, read at every request; the node gives it {@code sampler::failed} ({@code CpuSampler.failed()}:
 * true once the stream ended other than by {@code close()}).
 */
class AdminServerCpuTest {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final long T = 10_000_000_000L;
    static final long MS = 1_000_000L;
    static final long P = 4 * MS;
    static final Instant SINCE = Instant.parse("2026-11-02T10:15:30.250Z");

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<AdminServer> servers = new ArrayList<>();
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

    /** One package deployed: shop/orders and shop/audit, which the aggregate has rows of, and shop/idle, which ran nothing. */
    @BeforeEach
    void deploy() throws Exception {
        Map<String, String> e = new HashMap<>();
        e.put("FLOTTILA_ENV", "dev");
        e.put("FLOTTILA_NODE_ID", "node-1");
        e.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(new OptionSchemaSteps.Sheet()),
                List.of(), messages, dlq, new InMemoryWorkQueue(rt.clock()), () -> 0L);
        Path archive = pkg("shop", "1.0.0", Map.of("orders", "sync", "audit", "sync", "idle", "sync")).zipTo(tmp.resolve("uploads"));
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), archive));
        assertEquals(Set.of("shop/orders", "shop/audit", "shop/idle"), Set.copyOf(deployments.flows().stream().map(f -> f.id()).toList()), "fixture: deployed");
    }

    @AfterEach
    void stop() throws Exception {
        doors.releaseAll();
        for (AdminServer s : servers) s.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    /**
     * What a sampler fed: shop/orders step 1 "validate" twice (one sample of P each run), step 2 "map" once (three samples of P);
     * shop/audit step 1 one sample of 9P; a virtual thread's sample outside every step (pending), a platform sample, 7 lost.
     */
    static StepCpu fed() {
        StepCpu cpu = new StepCpu(StepCpu.Limits.DEFAULT);
        cpu.sample(1, true, T + MS, P);
        cpu.step(1, T, T + 10 * MS, "shop/orders", 1, "validate");
        cpu.sample(2, true, T + MS, P);
        cpu.sample(2, true, T + 2 * MS, P);
        cpu.sample(2, true, T + 3 * MS, P);
        cpu.step(2, T, T + 10 * MS, "shop/orders", 2, "map");
        cpu.sample(3, true, T + 21 * MS, P);
        cpu.step(3, T + 20 * MS, T + 30 * MS, "shop/orders", 1, "validate");
        cpu.sample(4, true, T + MS, 9 * P);
        cpu.step(4, T, T + 10 * MS, "shop/audit", 1, "check");
        cpu.sample(9, true, T + 50 * MS, P);   // no step of thread 9 holds it: pending
        cpu.sample(7, false, T + MS, P);   // a platform thread's
        cpu.lost(7);
        return cpu;
    }

    AdminServer.HealthProbe probe() {
        Map<String, Supplier<Future<Health>>> checks = Map.of();
        return new AdminServer.HealthProbe(checks, () -> 0L);
    }

    AdminServer.Config config() {
        return new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN));
    }

    /** A server with the deploy, work and replay routes and, given one, the sampling aggregate. */
    int serve(Optional<AdminServer.Sampling> sampling) throws Exception {
        return start(new AdminServer(config(), messages, dlq, rt.blobs(), probe(), Optional.of(deployments), new InMemoryWorkQueue(rt.clock()), rt.ids(), sampling));
    }

    int sampling() throws Exception {
        return serve(Optional.of(new AdminServer.Sampling(Duration.ofMillis(4), SINCE, fed(), () -> false)));
    }

    int start(AdminServer server) throws Exception {
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

    @Test
    void flowCpuListsStepsByCpuDescendingWithTotals() throws Exception {
        int port = sampling();

        Answer a = get(port, "/admin/flows/shop%2Forders/cpu", AUTH);

        assertJson(200, a, "GET /admin/flows/shop%2Forders/cpu");
        assertEquals(new JsonObject("""
                {"flow": "shop/orders", "sampling": "on", "period": "PT0.004S", "since": "2026-11-02T10:15:30.250Z",
                 "items": [
                   {"step": 2, "name": "map", "executions": 1, "samples": 3, "cpuNanos": 12000000},
                   {"step": 1, "name": "validate", "executions": 2, "samples": 2, "cpuNanos": 8000000}],
                 "totals": {"samples": 7, "attributed": 6, "pending": 1, "unattributed": 0, "platform": 1, "lost": 7, "dropped": 0}}
                """), a.json(), "map (12 ms) before validate (8 ms); shop/audit's row is not listed; the totals are the node's");
    }

    /**
     * Once the sampler says its stream failed, the route answers {@code sampling:"failed"} with everything else as before — period,
     * since, the rows and the totals the stream last fed — read at every request: the same server answered "on" a moment before.
     */
    @Test
    void flowCpuAnswersFailedWithTheLastAggregateOnceTheStreamFailed() throws Exception {
        AtomicBoolean failed = new AtomicBoolean();
        int port = serve(Optional.of(new AdminServer.Sampling(Duration.ofMillis(4), SINCE, fed(), failed::get)));
        Answer on = get(port, "/admin/flows/shop%2Forders/cpu", AUTH);
        assertJson(200, on, "witness: the stream runs");
        assertEquals("on", on.json().getString("sampling"), on.json().encode());

        failed.set(true);
        Answer a = get(port, "/admin/flows/shop%2Forders/cpu", AUTH);

        assertJson(200, a, "the stream failed");
        assertEquals(new JsonObject("""
                {"flow": "shop/orders", "sampling": "failed", "period": "PT0.004S", "since": "2026-11-02T10:15:30.250Z",
                 "items": [
                   {"step": 2, "name": "map", "executions": 1, "samples": 3, "cpuNanos": 12000000},
                   {"step": 1, "name": "validate", "executions": 2, "samples": 2, "cpuNanos": 8000000}],
                 "totals": {"samples": 7, "attributed": 6, "pending": 1, "unattributed": 0, "platform": 1, "lost": 7, "dropped": 0}}
                """), a.json(), "failed: the last aggregate as it was, only sampling says failed");
    }

    @Test
    void flowCpuIsOffWithoutASampler() throws Exception {
        int deployOnly = start(new AdminServer(config(), messages, dlq, rt.blobs(), probe(), Optional.of(deployments)));
        int none = serve(Optional.empty());
        JsonObject off = new JsonObject("""
                {"flow": "shop/orders", "sampling": "off", "items": []}
                """);
        for (int port : List.of(deployOnly, none)) {
            Answer a = get(port, "/admin/flows/shop%2Forders/cpu", AUTH);
            assertJson(200, a, "sampling off");
            assertEquals(off, a.json(), "off: the flow, sampling off, no items, no period, since or totals");
        }
    }

    @Test
    void flowCpuOfAnUnknownFlowIs404() throws Exception {
        int on = sampling();
        int off = serve(Optional.empty());
        for (int port : List.of(on, off)) {
            for (String unknown : List.of("shop%2Fnope", "nope%2Forders", "orders")) {
                assertError(404, get(port, "/admin/flows/" + unknown + "/cpu", AUTH), "the flow " + unknown + " is not deployed here");
            }
        }
        Answer idle = get(on, "/admin/flows/shop%2Fidle/cpu", AUTH);
        assertJson(200, idle, "a deployed flow that ran nothing is no unknown flow");
        assertEquals(new JsonObject("""
                {"flow": "shop/idle", "sampling": "on", "period": "PT0.004S", "since": "2026-11-02T10:15:30.250Z", "items": [],
                 "totals": {"samples": 7, "attributed": 6, "pending": 1, "unattributed": 0, "platform": 1, "lost": 7, "dropped": 0}}
                """), idle.json());
    }

    @Test
    void flowCpuNeedsTheToken() throws Exception {
        int port = sampling();
        for (String auth : java.util.Arrays.asList(null, "Bearer wrong", TOKEN)) {
            assertError(401, get(port, "/admin/flows/shop%2Forders/cpu", auth), "Authorization " + auth);
        }
        assertJson(200, get(port, "/admin/flows/shop%2Forders/cpu", AUTH), "with the token");
    }
}
