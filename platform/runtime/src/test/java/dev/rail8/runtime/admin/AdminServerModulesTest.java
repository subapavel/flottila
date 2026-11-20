package dev.rail8.runtime.admin;

import dev.rail8.api.plugin.AdminService;
import dev.rail8.api.plugin.PluginSettings;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.runtime.sample.AdminSamplePlugin;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.admin.AdminServerDeployTest.Answer;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertError;
import static dev.rail8.runtime.admin.AdminServerDeployTest.assertJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET /admin/modules} lists the node's modules, then its plugins, as they loaded, each with what it brings: 200
 * {@code {items:[{kind, name, version, api, sha256, from, brings:{schemes, steps, languages, services, command}}]}}. A version,
 * digest or location a module has not is null, its services are those the node serves for it, sorted by path, then method, and
 * {@code command} says whether it brings a command of the command line. It sits behind the browser guard and the token, as every
 * {@code /admin/*} route does.
 */
class AdminServerModulesTest {

    static final String TOKEN = "s3cret-token";

    static Vertx vertx;
    static HttpClientAgent client;

    final FakeRuntime rt = FakeRuntime.create();
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

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.stop().await(10, TimeUnit.SECONDS);
    }

    int start(AdminServer.Modules modules) throws Exception {
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), new InMemoryMessageStore(), new InMemoryDlq(), rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), modules);
        return server.start(vertx).await(10, TimeUnit.SECONDS);
    }

    Answer get(int port, Map<String, String> headers) throws Exception {
        return client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + "/admin/modules"))
                .compose(req -> {
                    headers.forEach(req::putHeader);
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

    Answer get(int port) throws Exception {
        return get(port, Map.of("Authorization", "Bearer " + TOKEN));
    }

    static JsonObject brings(List<String> schemes, List<String> steps, List<String> languages, JsonArray services, boolean command) {
        return new JsonObject().put("schemes", new JsonArray(schemes)).put("steps", new JsonArray(steps)).put("languages", new JsonArray(languages))
                .put("services", services).put("command", command);
    }

    static JsonObject service(String method, String path) {
        return new JsonObject().put("method", method).put("path", path);
    }

    @Test
    void eachModuleThenEachPluginIsListedWithWhatItBrings() throws Exception {
        List<AdminService> services = new AdminSamplePlugin(new AdminSamplePlugin.Holds()).services(PluginSettings.of("admin-sample", Map.of()));
        int port = start(new AdminServer.Modules(List.of(
                new Plugins.Listed("module", "http", Optional.of("0.1.0"), "0.3", Optional.of("ab12"), Optional.of("rail8-component-http.jar"),
                        List.of("http"), List.of(), List.of(), false),
                new Plugins.Listed("module", "admin-sample", Optional.empty(), "0.3", Optional.empty(), Optional.of("/build/test-classes"), List.of(),
                        List.of(), List.of(), true),
                new Plugins.Listed("module", "nowhere", Optional.empty(), "0.3", Optional.empty(), Optional.empty(), List.of(), List.of(), List.of(),
                        false),
                new Plugins.Listed("plugin", "acme", Optional.of("1.2.0"), "0.3", Optional.of("cd34"), Optional.of("acme.jar"), List.of("acme"),
                        List.of("drop"), List.of("upper"), false)),
                Map.of("admin-sample", services), rt.clock()));
        Answer listed = get(port);
        assertJson(200, listed, "GET /admin/modules");
        assertEquals(new JsonObject().put("items", new JsonArray()
                        .add(new JsonObject().put("kind", "module").put("name", "http").put("version", "0.1.0").put("api", "0.3").put("sha256", "ab12")
                                .put("from", "rail8-component-http.jar").put("brings", brings(List.of("http"), List.of(), List.of(), new JsonArray(), false)))
                        .add(new JsonObject().put("kind", "module").put("name", "admin-sample").putNull("version").put("api", "0.3").putNull("sha256")
                                .put("from", "/build/test-classes").put("brings", brings(List.of(), List.of(), List.of(), new JsonArray()
                                        .add(service("GET", "clock")).add(service("POST", "echo")).add(service("POST", "fail"))
                                        .add(service("POST", "hold")).add(service("POST", "reply")), true)))
                        .add(new JsonObject().put("kind", "module").put("name", "nowhere").putNull("version").put("api", "0.3").putNull("sha256")
                                .putNull("from").put("brings", brings(List.of(), List.of(), List.of(), new JsonArray(), false)))
                        .add(new JsonObject().put("kind", "plugin").put("name", "acme").put("version", "1.2.0").put("api", "0.3").put("sha256", "cd34")
                                .put("from", "acme.jar").put("brings", brings(List.of("acme"), List.of("drop"), List.of("upper"), new JsonArray(), false)))),
                listed.json(), "in load order, each with what it brings");
    }

    @Test
    void withoutModulesTheListIsEmpty() throws Exception {
        int port = start(AdminServer.Modules.NONE);
        Answer listed = get(port);
        assertJson(200, listed, "GET /admin/modules");
        assertEquals(new JsonObject().put("items", new JsonArray()), listed.json());
    }

    @Test
    void theRouteIsBehindTheBrowserGuardAndTheToken() throws Exception {
        int port = start(AdminServer.Modules.NONE);
        assertError(401, get(port, Map.of()), "without the token");
        assertError(403, get(port, Map.of("Authorization", "Bearer " + TOKEN, "Origin", "https://evil.example")), "from a browser");
    }
}
