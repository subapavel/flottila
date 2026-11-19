package dev.rail8.runtime.admin;

import dev.rail8.api.Component;
import dev.rail8.api.Health;
import dev.rail8.api.Runtime;
import dev.rail8.core.admission.Targets;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.flow.SequentialIntake;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.DirArchiveStore;
import dev.rail8.runtime.deploy.Doors;
import dev.rail8.runtime.deploy.OptionSchemaSteps;
import dev.rail8.runtime.deploy.InMemoryDeploymentStore;
import dev.rail8.runtime.lease.InMemoryPollerLeases;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static dev.rail8.runtime.admin.AdminServerDeployTest.Answer;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertError;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET /admin/steps} lists every step type a module or plugin of the node builds, sorted by type, each with the JSON Schema
 * of its options: 200 {@code {items:[{type, configSchema}]}}. It needs the token like every {@code /admin/*} route.
 */
class AdminServerStepsTest {

    static final String TOKEN = "s3cret-token";

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    Deployments deployments;
    AdminServer server;

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
        Map<String, String> e = new HashMap<>();
        e.put("RAIL8_ENV", "dev");
        e.put("RAIL8_NODE_ID", "node-1");
        e.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        Doors doors = new Doors();
        Map<String, Component> components = Map.of(Doors.SCHEME, doors);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        deployments = new Deployments(NodeConfig.from(e), runtime, components, rt.clock(), rt.timer(),
                List.of(new OptionSchemaSteps.Sheet(), new OptionSchemaSteps.Garbled(OptionSchemaSteps.Garbled.NOT_JSON)), List.of(), messages, dlq,
                queue, () -> 0L, new InMemoryDeploymentStore(), new DirArchiveStore(tmp.resolve("archives")), new SequentialIntake(messages, queue),
                new InMemoryPollerLeases(rt.clock()), new Targets(new Targets.Limit(3, Duration.ofMillis(750)), Map.of(), rt.timer()));
        Map<String, Supplier<Future<Health>>> checks = Map.of();
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(),
                new AdminServer.HealthProbe(checks, () -> 0L), Optional.of(deployments), new InMemoryWorkQueue(rt.clock()), Optional.empty());
    }

    @AfterEach
    void stop() throws Exception {
        server.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    Answer get(int port, String authorization) throws Exception {
        return client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + "/admin/steps"))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    req.response().onSuccess(HttpClientResponse::pause);   // the body may arrive before the compose below attaches to it
                    return req.send();
                })
                .compose(res -> {
                    Future<Buffer> all = res.body();
                    res.resume();
                    return all.map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b));
                })
                .await(20, TimeUnit.SECONDS);
    }

    /** {@code garbled} before {@code sheet}: sorted by type. A schema that is no JSON object is null; the built-in steps are not listed. */
    @Test
    void theStepTypesAreListedByTypeWithTheirSchemas() throws Exception {
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        Answer listed = get(port, "Bearer " + TOKEN);
        assertJson(200, listed, "GET /admin/steps");
        assertEquals(new JsonObject()
                .put("items", new JsonArray()
                        .add(new JsonObject().put("type", "garbled").putNull("configSchema"))
                        .add(new JsonObject().put("type", "sheet").put("configSchema", new JsonObject(OptionSchemaSteps.Sheet.SCHEMA)))),
                listed.json(), "sorted by type, each with its schema");
    }

    @Test
    void theRouteNeedsTheToken() throws Exception {
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        assertError(401, get(port, null), "GET /admin/steps without the token");
    }
}
