package dev.rail8.ingress;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Producer;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Field;
import dev.rail8.api.settings.Settings;
import dev.rail8.testkit.FakeClock;
import dev.rail8.testkit.FakeTimer;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.PoolOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared port on 127.0.0.1 and a free port, its drain timed on a fake clock. */
class SharedHttpPortTest {

    static final Duration DRAIN = Duration.ofSeconds(5);

    final Vertx vertx = Vertx.vertx();
    final FakeClock clock = FakeClock.at(Instant.parse("2026-01-01T00:00:00Z"));
    final FakeTimer timer = new FakeTimer(clock);
    final SharedHttpPort port = new SharedHttpPort(vertx, timer, "127.0.0.1", 0, DRAIN);
    final HttpClientAgent client = vertx.createHttpClient();

    @AfterEach void close() {
        port.close().await();
        vertx.close().await();
    }

    int listen(Set<String> held) {
        return port.listen(Map.of("http", "/http"), held::contains).await();
    }

    static EndpointConfig in(String name, Object address) {
        return new EndpointConfig(name, URI.create("http://" + name), address == null ? Map.of() : Map.of("address", address));
    }

    /** {@code status body} of a request, or the failure's class when it got no response. */
    String call(HttpMethod method, String path) {
        return call(client, method, path);
    }

    /** {@code status body} of a request sent by {@code client}, or the failure's class when it got no response. */
    String call(HttpClientAgent client, HttpMethod method, String path) {
        Future<String> answer = client.request(method, port.port(), "127.0.0.1", path)
                .compose(req -> req.send().compose(res -> res.body().map(body -> res.statusCode() + " " + body
                        + (res.getHeader("retry-after") == null ? "" : " retry-after=" + res.getHeader("retry-after")))));
        try {
            return answer.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception failed) {
            return "failed " + (failed.getCause() == null ? failed : failed.getCause()).getClass().getSimpleName();
        }
    }

    static void answer(HttpServerRequest req, String body) {
        req.response().end(body);
    }

    @Test
    void aRequestReachesTheMountAtItsExactPathAndNoOtherPath() {
        listen(Set.of());
        HttpIngress.Mount mount = port.mount(in("orders", "/orders"), req -> answer(req, req.method() + " " + req.path() + "?" + req.query())).await();

        assertEquals(URI.create("http://127.0.0.1:" + port.port() + "/http/orders"), mount.url());
        assertEquals("200 POST /http/orders?a=1", call(HttpMethod.POST, "/http/orders?a=1"), "the query aside, every method reaches the handler");
        assertEquals("200 GET /http/orders?null", call(HttpMethod.GET, "/http/orders"));
        assertEquals("404 no endpoint at /http/orders/x", call(HttpMethod.POST, "/http/orders/x"));
        assertEquals("404 no endpoint at /http/Orders", call(HttpMethod.POST, "/http/Orders"), "case-sensitive");
        assertEquals("404 no endpoint at /orders", call(HttpMethod.POST, "/orders"), "the prefix is part of the path");
        assertEquals("404 no endpoint at /http/./orders", call(HttpMethod.POST, "/http/./orders"), "matched as sent, never normalised");
        assertEquals("404 no endpoint at /http/%6Frders", call(HttpMethod.POST, "/http/%6Frders"), "never decoded");
        assertEquals("404 no endpoint at /http/orders/", call(HttpMethod.POST, "/http/orders/"), "a trailing slash is another path");
    }

    @Test
    void twoPrefixesShareOneConnection() {
        port.listen(Map.of("http", "/http", "hook", "/hook"), path -> false).await();
        Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();
        port.mount(in("orders", "/orders"), req -> { connections.add(req.connection()); answer(req, "http"); }).await();
        port.mount(new EndpointConfig("hooks", URI.create("hook://hooks"), Map.of("address", "/orders")),
                req -> { connections.add(req.connection()); answer(req, "hook"); }).await();
        HttpClientAgent one = vertx.createHttpClient(new PoolOptions().setHttp1MaxSize(1));
        try {
            for (int i = 0; i < 3; i++) {
                assertEquals("200 http", call(one, HttpMethod.POST, "/http/orders"));
                assertEquals("200 hook", call(one, HttpMethod.POST, "/hook/orders"), "one address under two prefixes is two paths");
            }
        } finally {
            one.close().await();
        }
        assertEquals(1, connections.size(), "one kept-alive connection carried every request: " + connections);
    }

    @Test
    void aHeldPathIsTryAgainWhileAnotherPathServes() {
        listen(Set.of("/http/swapping"));
        port.mount(in("orders", "/orders"), req -> answer(req, "ok")).await();
        assertEquals("200 ok", call(HttpMethod.POST, "/http/orders"));
        assertEquals("503 the endpoint at /http/swapping takes no request at the moment retry-after=1", call(HttpMethod.POST, "/http/swapping"));
        assertEquals("200 ok", call(HttpMethod.POST, "/http/orders"), "the other path serves on");
    }

    @Test
    void aPathAFlowHoldsWithoutAMountIsTryAgainNotNotFound() {
        listen(Set.of("/http/swapping"));
        assertEquals("503 the endpoint at /http/swapping takes no request at the moment retry-after=1", call(HttpMethod.POST, "/http/swapping"));
        assertEquals("404 no endpoint at /http/other", call(HttpMethod.POST, "/http/other"));
    }

    @Test
    void aHandlerThatThrowsGetsItsRequestA500() {
        listen(Set.of());
        port.mount(in("broken", "/broken"), req -> { throw new IllegalStateException("bug"); }).await();
        assertEquals("500 the endpoint failed to take the request", call(HttpMethod.POST, "/http/broken"));
    }

    @Test
    void onePathHasOneMountUntilItCloses() {
        listen(Set.of());
        HttpIngress.Mount first = port.mount(in("a", "/orders"), req -> answer(req, "a")).await();
        Future<HttpIngress.Mount> second = port.mount(in("b", "/orders"), req -> answer(req, "b"));

        IllegalStateException held = assertInstanceOf(IllegalStateException.class, second.cause());
        assertEquals("endpoint 'b': the path /http/orders is mounted by endpoint 'a'", held.getMessage());
        assertEquals("200 a", call(HttpMethod.POST, "/http/orders"));

        first.close().await();
        port.mount(in("b", "/orders"), req -> answer(req, "b")).await();
        assertEquals("200 b", call(HttpMethod.POST, "/http/orders"), "the path is free at once once its mount closed");

        assertTrue(first.close().succeeded(), "a late close of the first mount answers as its first close");
        assertEquals("200 b", call(HttpMethod.POST, "/http/orders"), "and leaves the mount that holds the path now alone");
    }

    @Test
    void aMountNeedsAPrefixForItsSchemeAndAnAddressOfItsForm() {
        listen(Set.of());
        Function<EndpointConfig, Throwable> refusal = cfg -> port.mount(cfg, req -> answer(req, "")).cause();

        assertEquals("endpoint 'w': the scheme webhook declares no HTTP prefix",
                assertInstanceOf(IllegalArgumentException.class, refusal.apply(new EndpointConfig("w", URI.create("webhook://w"),
                        Map.of("address", "/x")))).getMessage());
        assertEquals("endpoint 'x': option 'address' is missing", assertInstanceOf(IllegalArgumentException.class, refusal.apply(in("x", null))).getMessage());
        for (Object bad : List.of("orders", "/", "/a//b", "/a/../b", "/..", "/a/.", "/a b", "/a%20b", "/a?b", "/" + "a".repeat(256), 7)) {
            assertInstanceOf(IllegalArgumentException.class, refusal.apply(in("x", bad)), String.valueOf(bad));
        }
        for (Object good : List.of("/a", "/A.b_c~d-e/1", "/" + "a".repeat(255), "/a/.b/..c")) {
            port.mount(in("ok", good), req -> answer(req, "")).await().close().await();
        }
    }

    @Test
    void noMountBeforeTheListenNorAfterTheClose() {
        assertEquals("endpoint 'a': the HTTP port takes no mounts yet: it is not listening",
                assertInstanceOf(IllegalStateException.class, port.mount(in("a", "/a"), req -> answer(req, "")).cause()).getMessage());
        listen(Set.of());
        port.close().await();
        assertEquals("endpoint 'a': the HTTP port takes no mounts any more: it is closed",
                assertInstanceOf(IllegalStateException.class, port.mount(in("a", "/a"), req -> answer(req, "")).cause()).getMessage());
        assertInstanceOf(IllegalStateException.class, port.listen(Map.of(), path -> false).cause(), "it listens once");
    }

    @Test
    void aCloseWaitsForTheRequestsItTookThenCompletes() throws Exception {
        listen(Set.of("/http/slow"));
        CompletableFuture<HttpServerRequest> taken = new CompletableFuture<>();
        CompletableFuture<Context> on = new CompletableFuture<>();
        HttpIngress.Mount mount = port.mount(in("slow", "/slow"), req -> { on.complete(Vertx.currentContext()); taken.complete(req); }).await();
        Future<String> pending = client.request(HttpMethod.POST, port.port(), "127.0.0.1", "/http/slow")
                .compose(req -> req.send().compose(res -> res.body().map(body -> res.statusCode() + " " + body)));
        HttpServerRequest req = taken.get(10, TimeUnit.SECONDS);

        Future<Void> closed = mount.close();
        assertEquals("503 the endpoint at /http/slow takes no request at the moment retry-after=1", call(HttpMethod.POST, "/http/slow"),
                "no request reaches a closing mount");
        clock.advance(SharedHttpPort.DRAIN_CHECK);
        assertFalse(closed.isComplete(), "the request it took is still open");

        on.get().runOnContext(v -> answer(req, "late"));
        assertEquals("200 late", pending.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS), "the answer reaches its sender");
        clock.advance(SharedHttpPort.DRAIN_CHECK);
        assertTrue(closed.isComplete());
        assertTrue(closed.succeeded());
        assertEquals(0, timer.pending(), "its deadline is cancelled");
        assertTrue(mount.close().succeeded(), "a second close answers as the first");
    }

    @Test
    void aRequestStillOpenAtTheDrainDeadlineIsReset() throws Exception {
        listen(Set.of());
        CompletableFuture<HttpServerRequest> taken = new CompletableFuture<>();
        HttpIngress.Mount mount = port.mount(in("stuck", "/stuck"), taken::complete).await();
        Future<String> pending = client.request(HttpMethod.POST, port.port(), "127.0.0.1", "/http/stuck")
                .compose(req -> req.send().compose(res -> res.body().map(body -> res.statusCode() + " " + body)));
        taken.get(10, TimeUnit.SECONDS);

        Future<Void> closed = mount.close();
        clock.advance(DRAIN.minus(SharedHttpPort.DRAIN_CHECK));
        assertFalse(closed.isComplete());
        clock.advance(SharedHttpPort.DRAIN_CHECK);
        assertTrue(closed.succeeded(), "it completes at the deadline, never fails");
        Exception cut = assertThrows(Exception.class, () -> pending.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertFalse(cut.getCause() == null, "the sender saw its request cut: " + cut);
    }

    @Test
    void aSenderThatDisconnectsLetsTheCloseCompleteBeforeTheDeadline() throws Exception {
        listen(Set.of());
        CompletableFuture<Void> gone = new CompletableFuture<>();
        CompletableFuture<HttpServerRequest> taken = new CompletableFuture<>();
        HttpIngress.Mount mount = port.mount(in("stuck", "/stuck"), req -> {
            req.response().closeHandler(v -> gone.complete(null));
            taken.complete(req);
        }).await();
        HttpClientAgent sender = vertx.createHttpClient();
        sender.request(HttpMethod.POST, port.port(), "127.0.0.1", "/http/stuck").compose(req -> req.send());
        taken.get(10, TimeUnit.SECONDS);

        Future<Void> closed = mount.close();
        assertFalse(closed.isComplete(), "the request it took is open");
        sender.close().await();
        gone.get(10, TimeUnit.SECONDS);
        clock.advance(SharedHttpPort.DRAIN_CHECK);

        assertTrue(closed.succeeded(), "a request whose connection is gone never answers: the close does not wait for it");
        assertEquals(0, timer.pending(), "its deadline is cancelled");
    }

    @Test
    void aCloseWithNothingInFlightCompletesAtOnce() {
        listen(Set.of());
        HttpIngress.Mount mount = port.mount(in("idle", "/idle"), req -> answer(req, "ok")).await();
        assertEquals("200 ok", call(HttpMethod.POST, "/http/idle"));
        assertTrue(mount.close().succeeded());
        assertEquals(0, timer.pending());
    }

    @Test
    void theOpenRequestsOfAMountArePrunedAsTheyAnswer() {
        listen(Set.of());
        HttpIngress.Mount mount = port.mount(in("many", "/many"), req -> answer(req, "ok")).await();
        for (int i = 0; i < 3 * SharedHttpPort.PRUNE_AT; i++) {
            assertEquals("200 ok", call(HttpMethod.POST, "/http/many"));
            assertTrue(port.taken("/http/many") <= SharedHttpPort.PRUNE_AT, "kept at most " + SharedHttpPort.PRUNE_AT + ": " + port.taken("/http/many"));
        }
        assertTrue(mount.close().succeeded(), "nothing is open");
    }

    @Test
    void thePrefixesOfTheComponentsAreReadAndChecked() {
        assertEquals(Map.of("http", "/http", "hook-2", "/hook-2"), SharedHttpPort.prefixes(List.of(
                component("http", Optional.of("/http"), Settings.of(HttpIngress.ADDRESS)),
                component("hook-2", Optional.of("/hook-2"), Settings.of(Field.text("secret", "A secret.").in(), HttpIngress.ADDRESS)),
                component("sftp", Optional.empty(), Settings.NONE))));

        assertEquals("the component of the scheme a (" + Prefixed.class.getName() + ") declares the HTTP prefix 'http': a prefix is / and one"
                        + " segment, a lower-case letter, then at most 31 lower-case letters, digits or dashes",
                refused(component("a", Optional.of("http"), Settings.of(HttpIngress.ADDRESS))));
        for (String bad : List.of("/", "/Http", "/a/b", "/1a", "/" + "a".repeat(33), "/a_b", "")) {
            refused(component("a", Optional.of(bad), Settings.of(HttpIngress.ADDRESS)));
        }
        assertTrue(refused(component("a", Optional.of("/rail8"), Settings.of(HttpIngress.ADDRESS))).endsWith("kept for the platform itself"));
        assertEquals("the component of the scheme a (" + Prefixed.class.getName() + ") and the component of the scheme b (" + Prefixed.class.getName()
                        + ") both declare the HTTP prefix /x; a prefix has one component",
                refused(component("a", Optional.of("/x"), Settings.of(HttpIngress.ADDRESS)), component("b", Optional.of("/x"), Settings.of(HttpIngress.ADDRESS))));
        assertTrue(refused(component("a", Optional.of("/x"), Settings.of(Field.text("address", "Mine.").in()))).endsWith(
                "but its settings do not list the in field 'address' (HttpIngress.ADDRESS)"), "its own address field is not the node's");
        assertTrue(refused(component("a", Optional.of("/x"), Settings.NONE)).contains("do not list"));
        assertTrue(refused(component("a", null, Settings.of(HttpIngress.ADDRESS))).contains("its HTTP prefix cannot be read"));
    }

    static String refused(Component... components) {
        return assertThrows(IllegalStateException.class, () -> SharedHttpPort.prefixes(List.of(components))).getMessage();
    }

    static Component component(String scheme, Optional<String> prefix, Settings settings) {
        return new Prefixed(scheme, prefix, settings);
    }

    /** A component that only names its scheme, prefix and settings. */
    record Prefixed(String scheme, Optional<String> prefix, Settings settings) implements Component {
        @Override public Optional<String> httpPrefix() { return prefix; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { throw new UnsupportedOperationException(); }
        @Override public Producer producer(EndpointConfig cfg) { throw new UnsupportedOperationException(); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }
}
