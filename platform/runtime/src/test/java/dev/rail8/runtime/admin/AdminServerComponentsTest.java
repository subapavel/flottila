package dev.rail8.runtime.admin;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Producer;
import dev.rail8.api.Runtime;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Settings;
import dev.rail8.api.settings.Tab;
import dev.rail8.core.admission.Targets;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.flow.SequentialIntake;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.DirArchiveStore;
import dev.rail8.runtime.deploy.Doors;
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
import java.util.function.Function;
import java.util.function.Supplier;

import static dev.rail8.runtime.admin.AdminServerDeployTest.Answer;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertError;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET /admin/components} lists every component the node serves, sorted by scheme, each with its HTTP prefix and its
 * settings description, after the node's environment and the origin senders call its HTTP port at: 200
 * {@code {environment, httpOrigin, items:[{scheme, httpPrefix, settings}]}}. It needs the token like every {@code /admin/*} route.
 */
class AdminServerComponentsTest {

    static final String TOKEN = "s3cret-token";

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    Deployments deployments;
    AdminServer server;

    /** A component of the scheme {@code drop} that describes a host, a polling tab and two alternatives; it is never started. */
    static final class Drop implements Component {
        static final Tab POLLING = Tab.of("polling", "Polling");

        @Override public String scheme() { return "drop"; }
        @Override public Settings settings() {
            return Settings.of(
                            Field.text("host", "The server."),
                            Field.integer("port", "The port.").range(1, 65535).byDefault(22).in(),
                            Field.secret("password", "The password.").tab(Tab.SECURITY),
                            Field.secret("privateKey", "The key.").tab(Tab.SECURITY),
                            Field.choice("afterSuccess", "What then.", "delete", "move").byDefault("delete").tab(POLLING).in(),
                            Field.text("moveTo", "Where to.").required().visibleWhen("afterSuccess", "move").tab(POLLING).in())
                    .exactlyOne("password", "privateKey");
        }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Producer producer(EndpointConfig cfg) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    /** A component of the scheme {@code web} that takes requests on the node's HTTP port under {@code /web}; it is never started. */
    static final class Web implements Component {
        @Override public String scheme() { return "web"; }
        @Override public Optional<String> httpPrefix() { return Optional.of("/web"); }
        @Override public Settings settings() { return Settings.of(HttpIngress.ADDRESS); }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Producer producer(EndpointConfig cfg) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
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
        wire(Map.of());
    }

    /** The node of environment {@code dev}, its env with {@code extra} too; a node wired before, never started, is closed. */
    void wire(Map<String, String> extra) throws Exception {
        if (deployments != null) {
            Deployments before = deployments;
            OnVirtualThread.call(() -> { before.close(); return null; });
        }
        Map<String, String> e = new HashMap<>(extra);
        e.put("RAIL8_ENV", "dev");
        e.put("RAIL8_NODE_ID", "node-1");
        e.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        Doors doors = new Doors();
        Map<String, Component> components = Map.of(Doors.SCHEME, doors, "drop", new Drop(), "web", new Web());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        InMemoryWorkQueue queue = new InMemoryWorkQueue(rt.clock());
        deployments = new Deployments(NodeConfig.from(e), runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
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
        return client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + "/admin/components"))
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

    /**
     * {@code drop} before {@code rec} before {@code web}: sorted by scheme, only {@code web} with an HTTP prefix. The described one lists its own tab between general and retry, every
     * field with its tab, its directions and only what it sets, and its rule; the undescribed one the common tabs and nothing else.
     */
    @Test
    void theComponentsAreListedBySchemeWithTheirSettings() throws Exception {
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        Answer listed = get(port, "Bearer " + TOKEN);
        assertJson(200, listed, "GET /admin/components");
        JsonObject expected = new JsonObject("""
                {"environment": "dev", "httpOrigin": null, "items": [
                  {"scheme": "drop", "httpPrefix": null, "settings": {"format": 1, "described": true,
                    "tabs": [{"id": "general", "label": "General", "common": true}, {"id": "polling", "label": "Polling", "common": false},
                             {"id": "retry", "label": "Retry", "common": true}, {"id": "recording", "label": "Recording", "common": true},
                             {"id": "security", "label": "Security", "common": true}],
                    "fields": [
                      {"name": "host", "type": "text", "help": "The server.", "tab": "general", "required": false, "directions": ["in", "out"]},
                      {"name": "port", "type": "integer", "help": "The port.", "tab": "general", "required": false, "directions": ["in"],
                       "default": 22, "min": 1, "max": 65535},
                      {"name": "password", "type": "secret", "help": "The password.", "tab": "security", "required": false, "directions": ["in", "out"]},
                      {"name": "privateKey", "type": "secret", "help": "The key.", "tab": "security", "required": false, "directions": ["in", "out"]},
                      {"name": "afterSuccess", "type": "choice", "help": "What then.", "tab": "polling", "required": false, "directions": ["in"],
                       "default": "delete", "choices": ["delete", "move"]},
                      {"name": "moveTo", "type": "text", "help": "Where to.", "tab": "polling", "required": true, "directions": ["in"],
                       "visibleWhen": {"field": "afterSuccess", "values": ["move"]}}],
                    "rules": [{"kind": "exactlyOne", "fields": ["password", "privateKey"]}]}},
                  {"scheme": "rec", "httpPrefix": null, "settings": {"format": 1, "described": false,
                    "tabs": [{"id": "general", "label": "General", "common": true}, {"id": "retry", "label": "Retry", "common": true},
                             {"id": "recording", "label": "Recording", "common": true}, {"id": "security", "label": "Security", "common": true}],
                    "fields": [], "rules": []}}]}
                """);
        expected.getJsonArray("items").add(new JsonObject().put("scheme", "web").put("httpPrefix", "/web")
                .put("settings", new JsonObject(Settings.of(HttpIngress.ADDRESS).toJson())));
        assertEquals(expected, listed.json(), "sorted by scheme, each with its prefix and its description");
    }

    /** An advertised origin, {@code RAIL8_HTTP_ORIGIN}, is told as set; a component's prefix does not depend on it. */
    @Test
    void theAdvertisedOriginIsTold() throws Exception {
        wire(Map.of("RAIL8_HTTP_ORIGIN", "https://rail8.example.com"));
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        JsonObject listed = get(port, "Bearer " + TOKEN).json();
        assertEquals("dev", listed.getString("environment"));
        assertEquals("https://rail8.example.com", listed.getString("httpOrigin"));
        assertEquals("/web", listed.getJsonArray("items").getJsonObject(2).getString("httpPrefix"));
    }

    @Test
    void theRouteNeedsTheToken() throws Exception {
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        assertError(401, get(port, null), "GET /admin/components without the token");
    }
}
