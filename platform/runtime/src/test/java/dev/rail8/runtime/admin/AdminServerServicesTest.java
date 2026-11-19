package dev.rail8.runtime.admin;

import dev.rail8.api.plugin.AdminService;
import dev.rail8.api.plugin.PluginSettings;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.admin.AdminServerDeployTest.Raw;
import dev.rail8.runtime.sample.AdminSamplePlugin;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static dev.rail8.runtime.admin.AdminServerDeployTest.chunk;
import static dev.rail8.runtime.admin.AdminServerDeployTest.read;
import static dev.rail8.runtime.admin.AdminServerDeployTest.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The services of the admin API that a module brings, at {@code /admin/modules/<plugin name>/<path>}, over the sample module
 * {@link AdminSamplePlugin}, mounted twice: as {@code admin-sample} and as {@code other-sample}. Each call runs behind the
 * browser guard and the token, on a virtual thread of its own. Before the call, a body over the limit is 413, a POST that is not
 * {@code application/json} is 415 and bytes that are not UTF-8 are 400. A valid reply goes to the client as it is; anything else
 * is a 500. At most 4 calls of one plugin run at once, and a fifth is 503 at once. A call holds its place until it returns, and
 * its thread is interrupted when its client goes away or the server stops. The node's own answers are problems.
 */
class AdminServerServicesTest {

    static final String TOKEN = "s3cret-token";
    static final String SAMPLE = "admin-sample";
    static final String OTHER = "other-sample";
    static final long MAX = AdminService.MAX_REQUEST_BYTES;
    static final Duration WAIT = Duration.ofSeconds(20);

    static Vertx vertx;

    final FakeRuntime rt = FakeRuntime.create();
    final AdminSamplePlugin.Holds holds = new AdminSamplePlugin.Holds();
    final List<Socket> open = new ArrayList<>();
    AdminServer server;
    int port;

    @BeforeAll
    static void up() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void down() {
        vertx.close().await();
    }

    @BeforeEach
    void start() throws Exception {
        List<AdminService> services = new AdminSamplePlugin(holds).services(PluginSettings.of(SAMPLE, Map.of()));
        server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), new InMemoryMessageStore(), new InMemoryDlq(), rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), new AdminServer.Modules(Map.of(SAMPLE, services, OTHER, services), rt.clock()));
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        server.stop().await(10, TimeUnit.SECONDS);   // interrupts every call a test left held
        for (Socket s : open) s.close();
    }

    // --- the wire

    Socket socket() throws IOException {
        Socket s = new Socket();
        open.add(s);
        s.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
        s.setSoTimeout((int) WAIT.toMillis());   // a server that waits for a body it should not read fails the case
        return s;
    }

    /** The head of a call of {@code method} on {@code /admin/modules/<route>} with the token and {@code headers}. */
    String head(String method, String route, String headers) {
        return method + " /admin/modules/" + route + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: Bearer " + TOKEN + "\r\n" + headers + "\r\n";
    }

    /** A call sent on {@code s}, its answer not read: a body, when there is one, with its Content-Length. */
    void send(Socket s, String method, String route, String contentType, byte[] body) throws IOException {
        String headers = (contentType == null ? "" : "Content-Type: " + contentType + "\r\n") + (body == null ? "" : "Content-Length: " + body.length + "\r\n");
        OutputStream out = s.getOutputStream();
        write(out, head(method, route, headers));
        if (body != null) out.write(body);
        out.flush();
    }

    Raw call(String method, String route, String contentType, byte[] body) throws IOException {
        Socket s = socket();
        send(s, method, route, contentType, body);
        return read(s.getInputStream());
    }

    /** {@code POST /admin/modules/admin-sample/<path>} with {@code json} as {@code application/json}. */
    Raw post(String path, String json) throws IOException {
        return call("POST", SAMPLE + "/" + path, "application/json", utf8(json));
    }

    /** A held call of the sample, {@code POST hold} on a socket of its own, and the thread it is held on. */
    record Held(Socket socket, Thread thread) {}

    Held hold(String plugin) throws Exception {
        Socket s = socket();
        send(s, "POST", plugin + "/hold", "application/json", utf8("{}"));
        return new Held(s, holds.next());
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A JSON string of exactly {@code size} bytes. */
    static String jsonOfSize(long size) {
        return "\"" + "a".repeat((int) size - 2) + "\"";
    }

    static void assertReply(int status, String type, String json, Raw r, String what) {
        assertEquals(status, r.status(), what + ": " + r.body());
        assertEquals(type, r.headers().get("content-type"), what);
        assertEquals(json, r.body(), what + ": the reply's JSON as it is");
    }

    /** A problem of the node's own: {@code application/problem+json} of type {@code urn:rail8:admin:problem:<slug>}. */
    static void assertProblem(int status, String slug, Raw r, String what) {
        assertEquals(status, r.status(), what + ": " + r.body());
        assertEquals("application/problem+json", r.headers().get("content-type"), what);
        JsonObject problem = new JsonObject(r.body());
        assertEquals("urn:rail8:admin:problem:" + slug, problem.getString("type"), what + ": " + problem);
        assertEquals(status, problem.getInteger("status"), what + ": " + problem);
        assertFalse(problem.getString("title").isBlank() || problem.getString("detail").isBlank(), what + ": a title and a detail: " + problem);
    }

    // --- what a call is given and what its reply becomes

    @Test
    void aGetIsGivenNoBodyAndTheNodesClock() throws Exception {
        rt.clock().set(Instant.parse("2026-11-19T10:00:00Z"));
        assertReply(200, "application/json", "{\"now\":\"2026-11-19T10:00:00Z\",\"json\":\"\"}", call("GET", SAMPLE + "/clock", null, null), "GET clock");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application/json", "application/json; charset=utf-8", "Application/JSON"})
    void aPostIsGivenItsBodyAsTheClientSentIt(String contentType) throws Exception {
        String json = "{ \"name\": \"Žluťoučký kůň\", \"n\": [1, 2] }";
        assertReply(200, "application/json", json, call("POST", SAMPLE + "/echo", contentType, utf8(json)), contentType);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "200|application/json|\"text\"",
            "200|application/json|null",
            "201|application/json|[1,2]",
            "400|application/problem+json|{\"status\":400}",
            "404|application/problem+json|{\"type\":\"urn:example:gone\",\"title\":\"Gone\",\"status\":404,\"detail\":\"no such thing\"}",
            "599|application/problem+json|{\"status\":599}"})
    void aValidReplyGoesToTheClientAsItIs(int status, String type, String json) throws Exception {
        assertReply(status, type, json, post("reply", new JsonObject().put("status", status).put("json", json).encode()), status + " " + json);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "204|{}", "302|{}", "199|{}", "600|{}", "0|{}",
            "400|{\"status\":500}", "400|{\"status\":\"400\"}", "400|{}", "400|[400]", "503|\"busy\"",
            "200|{", "200|{} {}", "200|''", "201|[1,]", "200|NaN"})
    void anyOtherReplyIs500(int status, String json) throws Exception {
        assertProblem(500, "invalid-reply", post("reply", new JsonObject().put("status", status).put("json", json).encode()), status + " " + json);
    }

    @Test
    void noReplyOrAnExceptionIs500() throws Exception {
        assertProblem(500, "invalid-reply", post("reply", "{\"none\":true}"), "a null reply");
        assertProblem(500, "invalid-reply", post("fail", "{}"), "an exception");
    }

    @Test
    void aReplyOfAtMostTheLimitGoesToTheClientOneByteMoreIs500() throws Exception {
        long max = AdminService.MAX_REPLY_BYTES;
        Raw fits = post("reply", new JsonObject().put("status", 200).put("bytes", max).encode());
        assertEquals(200, fits.status(), "a reply of " + max + " bytes");
        assertEquals(max, fits.body().length(), "the whole reply");
        assertProblem(500, "invalid-reply", post("reply", new JsonObject().put("status", 200).put("bytes", max + 1).encode()), "a reply of " + (max + 1) + " bytes");
    }

    // --- refused before the call

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"declared", "counted"})
    void aBodyOfAtMostTheLimitIsTakenOneByteMoreIs413(String how) throws Exception {
        byte[] fits = utf8(jsonOfSize(MAX));
        Socket s = socket();
        OutputStream out = s.getOutputStream();
        if (how.equals("declared")) {
            send(s, "POST", SAMPLE + "/echo", "application/json", fits);
        } else {
            write(out, head("POST", SAMPLE + "/echo", "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n"));
            chunk(out, fits);
            write(out, "0\r\n\r\n");
        }
        Raw taken = read(s.getInputStream());
        assertEquals(200, taken.status(), how + ": " + MAX + " bytes");
        assertEquals(MAX, taken.body().length(), how + ": echoed whole");

        Socket over = socket();
        out = over.getOutputStream();
        if (how.equals("declared")) {
            write(out, head("POST", SAMPLE + "/echo", "Content-Type: application/json\r\nContent-Length: " + (MAX + 1) + "\r\n"));
        } else {
            write(out, head("POST", SAMPLE + "/echo", "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n"));
            chunk(out, utf8(jsonOfSize(MAX + 1)));
        }
        InputStream in = over.getInputStream();
        assertProblem(413, "too-large", read(in), how + ": " + (MAX + 1) + " bytes");
        assertEquals(-1, in.read(), how + ": the server closed the connection after the 413: the rest is never read");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"text/plain", "application/jsonx", "application/problem+json", "none"})
    void aPostThatIsNotJsonIs415(String contentType) throws Exception {
        assertProblem(415, "unsupported-media-type", call("POST", SAMPLE + "/echo", contentType.equals("none") ? null : contentType, utf8("{}")), contentType);
    }

    /** An invalid byte, an overlong slash, a surrogate encoded on its own, a sequence cut short. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"7b2261223a22ff227d", "7b2261223a22c0af227d", "7b2261223a22eda080227d", "7b2261223a22e282"})
    void bytesThatAreNotUtf8Are400(String hex) throws Exception {
        assertProblem(400, "not-utf-8", call("POST", SAMPLE + "/echo", "application/json", HexFormat.of().parseHex(hex)), hex);
    }

    // --- the routes

    @Test
    void theRoutesAreBehindTheBrowserGuardAndTheToken() throws Exception {
        Socket bare = socket();
        write(bare.getOutputStream(), "GET /admin/modules/" + SAMPLE + "/clock HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n");
        assertEquals(401, read(bare.getInputStream()).status(), "without the token");
        Socket browser = socket();
        write(browser.getOutputStream(), head("GET", SAMPLE + "/clock", "Origin: https://evil.example\r\n"));
        assertEquals(403, read(browser.getInputStream()).status(), "from a browser");
    }

    @Test
    void aRouteNoServiceHasIs404AndAnotherMethodIs405() throws Exception {
        assertEquals(404, call("POST", SAMPLE + "/nothing", "application/json", utf8("{}")).status(), "no such path");
        assertEquals(404, call("GET", "nobody/clock", null, null).status(), "no such plugin");
        assertEquals(405, call("GET", SAMPLE + "/echo", null, null).status(), "a POST service called with GET");
    }

    // --- four at once, held until the call returns, interrupted when its client goes away or the server stops

    @Test
    void atMostFourCallsOfOnePluginRunAtOnceAndAFifthIs503AtOnce() throws Exception {
        List<Held> held = new ArrayList<>();
        for (int i = 0; i < 4; i++) held.add(hold(SAMPLE));

        assertProblem(503, "busy", post("echo", "{}"), "a fifth call of the plugin");
        assertProblem(503, "busy", call("GET", SAMPLE + "/clock", null, null), "a fifth call, of another of its services");
        Socket unsent = socket();
        write(unsent.getOutputStream(), head("POST", SAMPLE + "/echo", "Content-Type: application/json\r\nContent-Length: 10\r\n"));
        InputStream in = unsent.getInputStream();
        assertProblem(503, "busy", read(in), "a fifth call whose body was never sent: answered at once");
        assertEquals(-1, in.read(), "and its connection closed: the body is never read");
        assertReply(200, "application/json", "{}", call("POST", OTHER + "/echo", "application/json", utf8("{}")), "another plugin has places of its own");

        Held first = held.getFirst();
        holds.letGo(first.thread());
        assertReply(200, "application/json", "{\"held\":true}", read(first.socket().getInputStream()), "the call let go");
        assertReply(200, "application/json", "{}", post("echo", "{}"), "its place is free once its answer is out");
    }

    @Test
    void aCallIsInterruptedWhenItsClientGoesAwayAndItsPlaceIsFreed() throws Exception {
        Held gone = hold(SAMPLE);
        gone.socket().close();
        assertTrue(gone.thread().join(WAIT), "the call ended");
        assertTrue(holds.interrupted(gone.thread()), "interrupted when its client went away");

        for (int i = 0; i < 4; i++) hold(SAMPLE);   // four places again
        assertProblem(503, "busy", post("echo", "{}"), "and no fifth");
    }

    @Test
    void stoppingTheServerInterruptsTheCallsThatRun() throws Exception {
        Held one = hold(SAMPLE);
        Held two = hold(OTHER);
        server.stop().await(10, TimeUnit.SECONDS);
        for (Held h : List.of(one, two)) {
            assertTrue(h.thread().join(WAIT), "the call ended");
            assertTrue(holds.interrupted(h.thread()), "interrupted as the server stopped");
        }
    }
}
