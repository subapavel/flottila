package io.flottila.runtime.admin;

import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeIds;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-069 (P1-09c2): the admin API refuses what a browser sends. Every {@code /admin/**} request that carries an {@code Origin}
 * header — whatever its value, the server's own origin included — or a {@code Sec-Fetch-Site} other than {@code none} is 403
 * {@code {error}} before the token check, token or no token, and before the route: nothing is read, deployed, released or
 * replayed. What the CLI and curl send — neither header, or {@code Sec-Fetch-Site: none} — passes to the token check and the
 * route as before. Each "never reached" is witnessed at the store boundary (a recording message store and work queue, the
 * deployments' package list) and by the same request without the header, which does reach it.
 *
 * <p>The shape assumed (P1-09c2 test-author): {@code AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe,
 * Optional<Deployments>, WorkQueue, IdGenerator)}.
 */
class AdminServerBrowserGuardTest {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final String FLOW = "shop/orders";
    static final String EVIL = "https://evil.example";

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final RecordingStore messages = new RecordingStore(new InMemoryMessageStore());
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    final AdminServerWorkTest.RecordingQueue queue = new AdminServerWorkTest.RecordingQueue(rows);
    final List<AdminServer> started = new ArrayList<>();
    Deployments deployments;
    long seq;

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
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(),
                messages, new InMemoryDlq(), rows, () -> 0L);
        rows.enqueue("h-1", FLOW, 1, null).await();
        WorkQueue.Item item = rows.claim("w1", FLOW, 1, Duration.ofSeconds(30)).await().get(0);
        assertTrue(rows.hold(item.seq(), item.attempts()).await(), "fixture: h-1 held");
        seq = item.seq();
    }

    @AfterEach
    void stop() throws Exception {
        doors.releaseAll();
        for (AdminServer s : started) s.stop().await(10, TimeUnit.SECONDS);
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    // --- fixture

    /** A server on loopback with {@code token} (empty: an open loopback server) over the recording stores and the deployments. */
    int serve(Optional<String> token) throws Exception {
        AdminServer server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, token), messages, new InMemoryDlq(), rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.of(deployments), queue, new FakeIds("replay"));
        int port = server.start(vertx).await(10, TimeUnit.SECONDS);
        started.add(server);
        return port;
    }

    record Answer(int status, MultiMap headers, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    /** One request: {@code headers} as given (a null value leaves the header out), {@code body} sent when not null. */
    static Answer send(int port, HttpMethod method, String path, Map<String, String> headers, byte[] body) throws Exception {
        return client.request(new RequestOptions().setMethod(method).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    headers.forEach((name, value) -> { if (value != null) req.putHeader(name, value); });
                    req.response().onSuccess(HttpClientResponse::pause);
                    return body == null ? req.send() : req.send(Buffer.buffer(body));
                })
                .compose(res -> {
                    Future<Buffer> answer = res.body();
                    res.resume();
                    return answer.map(b -> new Answer(res.statusCode(), res.headers(), b));
                })
                .await(20, TimeUnit.SECONDS);
    }

    static Map<String, String> headers(String... nameValue) {
        Map<String, String> h = new HashMap<>();
        for (int i = 0; i < nameValue.length; i += 2) h.put(nameValue[i], nameValue[i + 1]);
        return h;
    }

    /** One route of each method: GET a timeline, PUT a package, POST a release, POST a replay. */
    record Route(HttpMethod method, String path, byte[] body) {
        @Override public String toString() { return method + " " + path; }
    }

    List<Route> routes() {
        return List.of(new Route(HttpMethod.GET, "/admin/messages/m-404", null),
                new Route(HttpMethod.PUT, "/admin/packages/shop", AdminServerDeployTest.shop().zip()),
                new Route(HttpMethod.POST, "/admin/work/" + seq + "/release", null),
                new Route(HttpMethod.POST, "/admin/messages/m-404/replay?from=1", null));
    }

    static void assertRefused(Answer a, String what) {
        assertEquals(403, a.status(), what + ": " + a.body());
        String type = a.headers().get("content-type");
        assertTrue(type != null && type.startsWith("application/json"), what + ": " + type);
        assertEquals(Set.of("error"), a.json().fieldNames(), what + ": {error} only: " + a.body());
        String error = a.json().getString("error");
        assertTrue(error != null && !error.isBlank(), what + ": the error says why: " + a.body());
        assertNull(a.headers().get("www-authenticate"), what + ": the guard's refusal, not the token check's: " + a.headers());
    }

    /** Nothing reached a route: no timeline read, no release asked, no package deployed; h-1 still held. */
    void assertNothingReached(String what) {
        assertEquals(List.of(), messages.timelines, what + ": no timeline was read");
        assertEquals(List.of(), queue.calls, what + ": the work queue was not asked");
        assertEquals(List.of(), deployments.packages(), what + ": nothing was deployed");
        assertEquals(List.of("h-1"), rows.held(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(h -> h.item().messageId()).toList(),
                what + ": h-1 is still held");
    }

    // --- cases

    /**
     * A cross-origin POST with the right token — the CSRF a browser can send without a preflight — is 403 and reaches nothing: the
     * release is never asked, the replay never reads the timeline. So is any Origin: the server's own, and {@code null}. Witness: the
     * same POSTs without Origin reach their routes (the release is asked and done, the replay reads the timeline).
     */
    @Test
    void aCrossOriginPostIs403WithoutReachingTheRoute() throws Exception {
        int port = serve(Optional.of(TOKEN));
        String release = "/admin/work/" + seq + "/release";
        for (String origin : List.of(EVIL, "http://127.0.0.1:" + port, "null")) {
            assertRefused(send(port, HttpMethod.POST, release, headers("Authorization", AUTH, "Origin", origin), null), "POST release from " + origin);
            assertRefused(send(port, HttpMethod.POST, "/admin/messages/m-404/replay?from=1", headers("Authorization", AUTH, "Origin", origin), null),
                    "POST replay from " + origin);
        }
        assertNothingReached("cross-origin POSTs");

        assertEquals(404, send(port, HttpMethod.POST, "/admin/messages/m-404/replay?from=1", headers("Authorization", AUTH), null).status(),
                "witness: without Origin the replay route answers (no such message)");
        assertEquals(List.of("m-404"), messages.timelines, "witness: and read the timeline");
        Answer released = send(port, HttpMethod.POST, release, headers("Authorization", AUTH), null);
        assertEquals(200, released.status(), "witness: without Origin the release route answers: " + released.body());
        assertEquals(List.of(seq), queue.releases, "witness: the release was asked");
    }

    /** {@code Sec-Fetch-Site} other than {@code none} — same-origin, same-site, cross-site, anything else — is 403 on every method. */
    @Test
    void aSecFetchSiteOtherThanNoneIs403() throws Exception {
        int port = serve(Optional.of(TOKEN));
        for (String site : List.of("cross-site", "same-site", "same-origin", "bogus")) {
            for (Route r : routes()) {
                assertRefused(send(port, r.method(), r.path(), headers("Authorization", AUTH, "Sec-Fetch-Site", site), r.body()), r + " with Sec-Fetch-Site " + site);
            }
        }
        assertNothingReached("Sec-Fetch-Site other than none");
    }

    /**
     * What curl and the CLI send — no Origin, no Sec-Fetch-Site — and {@code Sec-Fetch-Site: none} (a typed URL) pass the guard:
     * the token check and the routes answer as before (GET a timeline 404, PUT a package 200, POST a release 200, POST a replay 404).
     */
    @Test
    void aCurlStyleRequestWithoutOriginPasses() throws Exception {
        int port = serve(Optional.of(TOKEN));
        assertEquals(404, send(port, HttpMethod.GET, "/admin/messages/m-404", headers("Authorization", AUTH), null).status(), "GET: the route's 404");
        assertEquals(404, send(port, HttpMethod.GET, "/admin/messages/m-404", headers("Authorization", AUTH, "Sec-Fetch-Site", "none"), null).status(),
                "Sec-Fetch-Site: none passes");
        assertEquals(List.of("m-404", "m-404"), messages.timelines, "both reached the route");
        Answer put = send(port, HttpMethod.PUT, "/admin/packages/shop", headers("Authorization", AUTH, "Sec-Fetch-Site", "none"), AdminServerDeployTest.shop().zip());
        assertEquals(200, put.status(), "PUT deploys: " + put.body());
        assertEquals(List.of("shop"), deployments.packages().stream().map(p -> p.name()).toList());
        assertEquals(200, send(port, HttpMethod.POST, "/admin/work/" + seq + "/release", headers("Authorization", AUTH), null).status(), "POST releases");
        assertEquals(List.of(seq), queue.releases);
        assertEquals(404, send(port, HttpMethod.POST, "/admin/messages/m-404/replay?from=1", headers("Authorization", AUTH), null).status(),
                "POST replay: the route's 404");
        assertEquals(401, send(port, HttpMethod.GET, "/admin/messages/m-404", headers(), null).status(), "and the token check still applies");
    }

    /**
     * The guard runs before the token check: on GET, PUT and POST, with Origin or with Sec-Fetch-Site cross-site, no token, a
     * wrong token and the right one are all 403 — never 401 — and reach nothing. On an open loopback server (no token configured)
     * the guard applies all the same. Witness: without the browser headers and without a token, the same requests are 401.
     */
    @Test
    void theGuardRunsBeforeTheTokenCheck() throws Exception {
        int port = serve(Optional.of(TOKEN));
        List<Map<String, String>> browsers = List.of(headers("Origin", EVIL), headers("Sec-Fetch-Site", "cross-site"));
        for (Map<String, String> browser : browsers) {
            for (String auth : java.util.Arrays.asList(null, "Bearer wrong", AUTH)) {
                for (Route r : routes()) {
                    Map<String, String> h = new HashMap<>(browser);
                    h.put("Authorization", auth);
                    assertRefused(send(port, r.method(), r.path(), h, r.body()), r + " " + browser + " with Authorization " + auth);
                }
            }
        }
        assertNothingReached("browser requests to a token server");

        int open = serve(Optional.empty());
        for (Route r : routes()) assertRefused(send(open, r.method(), r.path(), headers("Origin", EVIL), r.body()), r + " from a browser to an open loopback server");
        assertNothingReached("browser requests to an open server");

        for (Route r : routes()) {
            Answer plain = send(port, r.method(), r.path(), headers(), r.body());
            assertEquals(401, plain.status(), "witness: " + r + " without a token is the token check's 401: " + plain.body());
        }
        assertNothingReached("the token check's 401s");
    }

    // --- the store boundary: a message store that records the timelines read

    static final class RecordingStore implements MessageStore {
        final MessageStore inner;
        final List<String> timelines = new CopyOnWriteArrayList<>();

        RecordingStore(MessageStore inner) { this.inner = inner; }

        @Override public Future<Void> record(Snapshot snapshot) { return inner.record(snapshot); }
        @Override public Future<List<Snapshot>> timeline(String messageId) {
            timelines.add(messageId);
            return inner.timeline(messageId);
        }
        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
            return inner.findByKey(name, value, after, limit);
        }
    }
}
