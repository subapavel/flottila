package io.flottila.runtime.admin;

import io.flottila.api.Health;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.ArchiveStore;
import io.flottila.runtime.deploy.DeploymentRecord;
import io.flottila.runtime.deploy.DeploymentStore;
import io.flottila.runtime.deploy.Deployments;
import io.flottila.runtime.deploy.DirArchiveStore;
import io.flottila.runtime.deploy.Doors;
import io.flottila.runtime.deploy.InMemoryDeploymentStore;
import io.flottila.runtime.deploy.OptionSchemaSteps;
import io.flottila.runtime.deploy.PackageDraft;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-09d1b (D-059, D-060 b): the deploy routes of the admin API over a real {@link Deployments} (in-memory stores, a fake
 * runtime, the {@link Doors} component). {@code PUT /admin/packages/{name}?revision=} streams the body to a temp file in
 * {@code incomingDir()} counting bytes — above {@code maxPackageBytes} it is 413, by Content-Length before a byte is read, or
 * at byte max+1 of a chunked body with the temp file deleted — and maps each refusal kind to its status; {@code GET
 * /admin/flows} and {@code GET /admin/packages} list what is deployed; all three need the token and are 404 without a
 * {@code Deployments}.
 *
 * <p>The shape assumed (P1-09d1b test-author): {@code AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe,
 * Optional<Deployments>)}, the 5-arg constructor delegating with {@code Optional.empty()}.
 */
class AdminServerDeployTest {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final long MAX = 16 * 1024;
    static final String FORBIDDEN = "manual deploy is non-production only (ARCHITECTURE §9)";

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    Path data;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<AdminServer> servers = new ArrayList<>();
    final List<Deployments> made = new ArrayList<>();
    Deployments deployments;
    int port;

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
        data = tmp.resolve("data");
        deployments = deployments("dev");
        port = serve(Optional.of(deployments));
    }

    @AfterEach
    void stop() throws Exception {
        doors.releaseAll();
        for (AdminServer s : servers) s.stop().await(10, TimeUnit.SECONDS);
        for (Deployments d : made) OnVirtualThread.call(() -> { d.close(); return null; });
    }

    // --- fixture

    Deployments deployments(String env) {
        Map<String, String> e = new HashMap<>();
        e.put("FLOTTILA_ENV", env);
        e.put("FLOTTILA_NODE_ID", "node-1");
        e.put("FLOTTILA_DATA_DIR", data.toString());
        e.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(MAX));
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        Deployments d = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(new OptionSchemaSteps.Sheet()), List.of(), messages, dlq,
                new InMemoryWorkQueue(rt.clock()), () -> 0L);
        made.add(d);
        return d;
    }

    AdminServer.HealthProbe probe() {
        Map<String, Supplier<Future<Health>>> checks = Map.of();
        return new AdminServer.HealthProbe(checks, () -> 0L);
    }

    int serve(Optional<Deployments> d) throws Exception {
        return start(new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(), probe(), d));
    }

    int start(AdminServer server) throws Exception {
        int bound = server.start(vertx).await(10, TimeUnit.SECONDS);
        servers.add(server);
        return bound;
    }

    /** {@code name} at {@code version}: one in endpoint and one flow per entry of {@code flows} (id → delivery mode), each to {@code partner}. */
    static PackageDraft pkg(String name, String version, Map<String, String> flows) {
        PackageDraft p = PackageDraft.of(name, version).endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        flows.forEach((id, mode) -> p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", mode, id + "-in", 1024, PackageDraft.toPartner()));
        return p;
    }

    static PackageDraft shop() {
        return pkg("shop", "1.0.0", Map.of("orders", "sync"));
    }

    record Answer(int status, String contentType, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    Answer request(int at, HttpMethod method, String path, String authorization, byte[] body) throws Exception {
        return requesting(at, method, path, authorization, body).await(20, TimeUnit.SECONDS);
    }

    /** The request sent; its answer not awaited. */
    Future<Answer> requesting(int at, HttpMethod method, String path, String authorization, byte[] body) {
        return client.request(new RequestOptions().setMethod(method).setAbsoluteURI("http://127.0.0.1:" + at + path))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    return body == null ? req.send() : req.send(Buffer.buffer(body));
                })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b)));
    }

    /**
     * A deploy of {@code store} whose first consumer start is held (P1-09d2b: it holds the node's deploy lock), sent and not awaited;
     * returns once that start was reached. Release the gate, then await the answer.
     */
    record Running(Doors.Gate gate, Future<Answer> answer) {}

    Running aDeployHoldingTheLock() throws Exception {
        Doors.Gate gate = doors.holdStart(1);
        Future<Answer> answer = requesting(port, HttpMethod.PUT, "/admin/packages/store", AUTH, pkg("store", "1.0.0", Map.of("ping", "sync")).zip());
        if (!gate.reached().await(20, TimeUnit.SECONDS)) fail("fixture: the deploy holding the lock did not reach its consumer start within 20 s");
        return new Running(gate, answer);
    }

    Answer put(int at, String path, byte[] zip) throws Exception {
        return request(at, HttpMethod.PUT, path, AUTH, zip);
    }

    Answer put(String path, byte[] zip) throws Exception {
        return put(port, path, zip);
    }

    Answer get(String path) throws Exception {
        return request(port, HttpMethod.GET, path, AUTH, null);
    }

    static void assertJson(int status, Answer a, String what) {
        assertEquals(status, a.status(), what + ": " + a.body());
        assertNotNull(a.contentType(), what + ": a content-type");
        assertTrue(a.contentType().startsWith("application/json"), what + ": " + a.contentType());
    }

    /** A {@code {error}} body and nothing else. */
    static void assertError(int status, Answer a, String what) {
        assertJson(status, a, what);
        JsonObject body = a.json();
        assertEquals(Set.of("error"), body.fieldNames(), what + ": the body is {error} only: " + body);
        assertTrue(!body.getString("error").isBlank(), what + ": {error} names the failure");
    }

    /** The array's values, objects as {@link JsonObject} (its stream wraps them; {@code getList()} would not). */
    static Set<Object> set(JsonArray a) {
        return a.stream().collect(java.util.stream.Collectors.toSet());
    }

    static void until(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("not within 20 s: " + what);
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    static List<Path> entries(Path dir) {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        } catch (IOException e) {
            throw new AssertionError("cannot list " + dir, e);
        }
    }

    // --- raw HTTP/1.1 over a socket: a head without its body, a chunked body cut anywhere

    record Raw(int status, Map<String, String> headers, String body) {}

    Socket socket() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
        s.setSoTimeout(10_000);   // a server that waits for a body it should not read fails the case with SocketTimeoutException
        return s;
    }

    static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    static byte[] filler(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ('a' + i % 26);
        return b;
    }

    static void chunk(OutputStream out, byte[] bytes) throws IOException {
        write(out, Integer.toHexString(bytes.length) + "\r\n");
        out.write(bytes);
        write(out, "\r\n");
    }

    static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') sb.append((char) c);
        if (c == -1 && sb.isEmpty()) throw new EOFException("the server closed the connection without an answer");
        return sb.toString();
    }

    static Raw read(InputStream in) throws IOException {
        String status = line(in);
        Map<String, String> headers = new HashMap<>();
        for (String l = line(in); !l.isEmpty(); l = line(in)) {
            int colon = l.indexOf(':');
            headers.put(l.substring(0, colon).trim().toLowerCase(Locale.ROOT), l.substring(colon + 1).trim());
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (headers.containsKey("content-length")) {
            body.write(in.readNBytes(Integer.parseInt(headers.get("content-length"))));
        } else if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            for (int n = Integer.parseInt(line(in).split(";")[0].trim(), 16); n > 0; n = Integer.parseInt(line(in).split(";")[0].trim(), 16)) {
                body.write(in.readNBytes(n));
                line(in);
            }
        }
        return new Raw(Integer.parseInt(status.split(" ")[1]), headers, body.toString(StandardCharsets.UTF_8));
    }

    String head(String name, String framing) {
        return "PUT /admin/packages/" + name + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: " + AUTH + "\r\n" + framing + "\r\n";
    }

    static void assertRawError(int status, Raw r, String what) {
        assertEquals(status, r.status(), what + ": " + r.body());
        assertTrue(String.valueOf(r.headers().get("content-type")).startsWith("application/json"), what + ": " + r.headers());
        String error = new JsonObject(r.body()).getString("error");
        assertTrue(error != null && !error.isBlank(), what + ": {error}: " + r.body());
    }

    // --- PUT /admin/packages/{name}

    @Test
    void putDeploysThePackageAndAnswers200WithItsVersionArchiveAndFlows() throws Exception {
        byte[] zip = pkg("shop", "1.2.3", Map.of("orders", "sync", "audit", "at-least-once")).zip();

        Answer a = put("/admin/packages/shop?revision=9df3f78", zip);

        assertJson(200, a, "a deploy");
        JsonObject body = a.json();
        assertEquals(Set.of("package", "version", "revision", "archive", "flows", "added", "swapped", "removed"), body.fieldNames(), "the 200 body: " + body);
        assertEquals("shop", body.getString("package"));
        assertEquals("1.2.3", body.getString("version"));
        assertEquals("9df3f78", body.getString("revision"));
        assertEquals("sha256:" + PackageDraft.sha256(zip), body.getString("archive"), "the archive is the SHA-256 of the bytes uploaded");
        assertEquals(Set.of("shop/audit", "shop/orders"), set(body.getJsonArray("flows")), "the flows by their qualified ids");
        assertEquals(new JsonArray().add("shop/audit").add("shop/orders"), body.getJsonArray("added"), "a first deploy adds every flow, sorted (P1-09d2b)");
        assertEquals(new JsonArray(), body.getJsonArray("swapped"));
        assertEquals(new JsonArray(), body.getJsonArray("removed"));
        assertTrue(doors.door("shop/orders").started(), "the flows were started");

        Answer none = put("/admin/packages/store", pkg("store", "1.0.0", Map.of("ping", "at-most-once")).zip());
        assertJson(200, none, "a deploy without a revision");
        assertTrue(none.json().containsKey("revision") && none.json().getValue("revision") == null, "revision is null without one: " + none.json());
    }

    @Test
    void contentLengthAboveMaxIs413Unread() throws Exception {
        assertEquals(MAX, deployments.maxPackageBytes(), "fixture");
        try (Socket s = socket()) {
            write(s.getOutputStream(), head("shop", "Content-Length: " + (MAX + 1) + "\r\n"));   // the head only: not a byte of the body is sent

            Raw r = read(s.getInputStream());

            assertRawError(413, r, "a Content-Length above FLOTTILA_MAX_PACKAGE_BYTES is refused on the head, the body never read");
        }
        assertEquals(List.of(), entries(deployments.incomingDir()), "no temp file is left");
        assertEquals(List.of(), doors.events(), "nothing was deployed");

        try (Socket s = socket()) {   // exactly max is not too large: it reaches the deploy, which finds no zip
            write(s.getOutputStream(), head("shop", "Content-Length: " + MAX + "\r\n"));
            s.getOutputStream().write(filler((int) MAX));
            s.getOutputStream().flush();
            assertRawError(400, read(s.getInputStream()), "a body of exactly the limit is deployed (and refused as no zip)");
        }
    }

    /**
     * A client that sends {@code Expect: 100-continue} sends its body only after the server's {@code 100 Continue}: the 100 is
     * read on the socket before a byte of the body is written (a server that waits for the body fails on the 10 s read timeout).
     */
    @Test
    void anExpectContinueUploadIsAnsweredWithoutWaiting() throws Exception {
        byte[] zip = shop().zip();
        try (Socket s = socket()) {
            write(s.getOutputStream(), head("shop", "Content-Length: " + zip.length + "\r\nExpect: 100-continue\r\n"));

            Raw interim = read(s.getInputStream());

            assertEquals(100, interim.status(), "100 Continue answers the head before the body is sent: " + interim);
            s.getOutputStream().write(zip);
            s.getOutputStream().flush();
            Raw done = read(s.getInputStream());
            assertEquals(200, done.status(), "then the body deploys: " + done.body());
            assertEquals("shop", new JsonObject(done.body()).getString("package"));
        }
        assertTrue(doors.door("shop/orders").started(), "the package was deployed");
    }

    @Test
    void aChunkedBodyAboveMaxIs413AndLeavesNoFile() throws Exception {
        Path incoming = deployments.incomingDir();
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            write(out, head("shop", "Transfer-Encoding: chunked\r\n"));
            chunk(out, filler(512));
            until(() -> entries(incoming).stream().anyMatch(Files::isRegularFile), "the body is piped to a temp file in incomingDir() as it arrives");

            chunk(out, filler((int) MAX + 1 - 512));   // byte max+1 arrives; the last chunk never does

            Raw r = read(s.getInputStream());
            assertRawError(413, r, "the counted body passed the limit");
        }
        until(() -> entries(incoming).isEmpty(), "the temp file is deleted after the 413: " + entries(incoming));
        assertEquals(List.of(), doors.events(), "nothing was deployed");

        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            write(out, head("shop", "Transfer-Encoding: chunked\r\n"));
            chunk(out, filler(512));
            chunk(out, filler((int) MAX - 512));
            write(out, "0\r\n\r\n");
            assertRawError(400, read(s.getInputStream()), "a chunked body of exactly the limit is deployed (and refused as no zip)");
        }
    }

    /**
     * P1-09f through HTTP: a step option outside its factory's {@code configSchema()} is a 422 {@code {error, problems}} whose line names
     * the flow, the step's position and name, its type and the option's path; nothing is deployed.
     */
    @Test
    void aStepOptionOutsideItsSchemaIs422WithItsLine() throws Exception {
        PackageDraft p = shop();
        p.flowDoc("orders").put("steps", new JsonArray().add(OptionSchemaSteps.Sheet.step("bad", 7)).addAll(PackageDraft.toPartner()));

        Answer refused = put("/admin/packages/shop", p.zip());

        assertJson(422, refused, "INVALID");
        JsonObject body = refused.json();
        assertEquals(Set.of("error", "problems"), body.fieldNames(), "a 422 is {error, problems}: " + body);
        assertTrue(body.getJsonArray("problems").stream().map(String.class::cast)
                        .anyMatch(l -> l.contains("orders'") && l.contains("step 1 'bad' (type 'sheet') option at '/template'")),
                "a line names the flow, the step, its type and the option: " + body);
        assertEquals(List.of(), doors.events(), "nothing was deployed");
        assertEquals(List.of(), deployments.flows());
    }

    @Test
    void eachRefusalKindMapsToItsStatusAndBody() throws Exception {
        assertError(400, put("/admin/packages/shop", "no zip at all".getBytes(StandardCharsets.UTF_8)), "BAD_REQUEST");

        PackageDraft invalid = shop();
        invalid.manifest().put("dependencies", new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.0.0")));
        Answer unprocessable = put("/admin/packages/shop", invalid.zip());
        assertJson(422, unprocessable, "INVALID");
        JsonObject body = unprocessable.json();
        assertEquals(Set.of("error", "problems"), body.fieldNames(), "a 422 is {error, problems}: " + body);
        assertTrue(!body.getString("error").isBlank());
        JsonArray problems = body.getJsonArray("problems");
        assertTrue(!problems.isEmpty() && problems.stream().allMatch(String.class::isInstance), "problems are lines: " + problems);
        assertTrue(problems.stream().map(String.class::cast).anyMatch(l -> l.contains("dependencies")), "a line names the problem: " + problems);

        doors.failStart(1, "address in use");
        Answer failed = put("/admin/packages/shop", shop().zip());
        assertError(500, failed, "FAILED");
        assertTrue(failed.json().getString("error").startsWith("flow 'shop/orders' did not start: ") && failed.json().getString("error").contains("address in use"),
                "the 500 names the flow and the cause: " + failed.json());

        assertJson(200, put("/admin/packages/shop", shop().zip()), "fixture: shop deploys");
        assertJson(200, put("/admin/packages/shop", shop().zip()), "the running package again is a redeploy, not a conflict (P1-09d2b)");
        Running running = aDeployHoldingTheLock();
        assertError(409, put("/admin/packages/shop", shop().zip()), "CONFLICT: another deploy holds the node's lock");
        running.gate().release();
        assertJson(200, running.answer().await(20, TimeUnit.SECONDS), "fixture: the deploy that held the lock completes");

        Deployments closed = deployments("dev");
        OnVirtualThread.call(() -> { closed.close(); return null; });
        assertError(503, put(serve(Optional.of(closed)), "/admin/packages/store", pkg("store", "1.0.0", Map.of("ping", "sync")).zip()), "STOPPING");

        Answer forbidden = put(serve(Optional.of(deployments("prod"))), "/admin/packages/store", pkg("store", "1.0.0", Map.of("ping", "sync")).zip());
        assertError(403, forbidden, "FORBIDDEN");
        assertEquals(FORBIDDEN, forbidden.json().getString("error"));
    }

    /** The body streamed to a temp file in incomingDir() is gone once the answer is out, whatever the deploy answered (409: another deploy runs). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {200, 400, 409, 422})
    void theUploadedTempFileIsDeletedWhateverTheOutcome(int status) throws Exception {
        byte[] body = switch (status) {
            case 200, 409 -> shop().zip();
            case 400 -> "no zip at all".getBytes(StandardCharsets.UTF_8);
            case 422 -> {
                PackageDraft invalid = shop();
                invalid.manifest().put("dependencies", new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.0.0")));
                yield invalid.zip();
            }
            default -> throw new IllegalArgumentException("no fixture for " + status);
        };
        Running running = status == 409 ? aDeployHoldingTheLock() : null;   // its own upload stays in incomingDir() until it answers
        List<Path> before = entries(deployments.incomingDir());
        if (running == null) assertEquals(List.of(), before, "fixture: nothing in incomingDir() before the upload");

        Answer a = put("/admin/packages/shop", body);

        assertJson(status, a, "the deploy answered " + status);
        assertEquals(before, entries(deployments.incomingDir()), "no upload temp file (nor unpack directory) of this request is left after a " + status);
        if (running != null) {
            running.gate().release();
            assertJson(200, running.answer().await(20, TimeUnit.SECONDS), "fixture: the deploy that held the lock completes");
            assertEquals(List.of(), entries(deployments.incomingDir()), "nor of the deploy that held the lock");
        }
    }

    /**
     * P1-09d2b (D-063): a redeploy answers 200 with what it did — {@code added}, {@code swapped} and {@code removed}, sorted
     * qualified ids — beside the package's version, revision, archive and every flow it now runs; a first deploy adds every flow.
     */
    @Test
    void redeploy200ListsAddedSwappedRemoved() throws Exception {
        Map<String, String> v1Flows = new java.util.LinkedHashMap<>();
        v1Flows.put("orders", "sync");
        v1Flows.put("audit", "at-least-once");
        v1Flows.put("ping", "at-most-once");
        Answer first = put("/admin/packages/shop?revision=r1", pkg("shop", "1.0.0", v1Flows).zip());
        assertJson(200, first, "fixture: v1");
        assertEquals(new JsonArray().add("shop/audit").add("shop/orders").add("shop/ping"), first.json().getJsonArray("added"), "v1 adds every flow: " + first.json());
        assertEquals(new JsonArray(), first.json().getJsonArray("swapped"));
        assertEquals(new JsonArray(), first.json().getJsonArray("removed"));
        Map<String, String> v2Flows = new java.util.LinkedHashMap<>();
        v2Flows.put("orders", "sync");
        v2Flows.put("ping", "at-most-once");
        v2Flows.put("refunds", "sync");
        PackageDraft v2 = pkg("shop", "1.1.0", v2Flows);
        v2.flowDoc("orders").put("description", "changed in 1.1.0");
        byte[] zip = v2.zip();

        Answer a = put("/admin/packages/shop?revision=r2", zip);

        assertJson(200, a, "the redeploy");
        JsonObject body = a.json();
        assertEquals(Set.of("package", "version", "revision", "archive", "flows", "added", "swapped", "removed"), body.fieldNames(), "the 200 body: " + body);
        assertEquals("shop", body.getString("package"));
        assertEquals("1.1.0", body.getString("version"));
        assertEquals("r2", body.getString("revision"));
        assertEquals("sha256:" + PackageDraft.sha256(zip), body.getString("archive"));
        assertEquals(Set.of("shop/orders", "shop/ping", "shop/refunds"), set(body.getJsonArray("flows")), "every flow it runs now");
        assertEquals(new JsonArray().add("shop/refunds"), body.getJsonArray("added"), "the new flow: " + body);
        assertEquals(new JsonArray().add("shop/orders"), body.getJsonArray("swapped"), "the changed flow; ping is unchanged: " + body);
        assertEquals(new JsonArray().add("shop/audit"), body.getJsonArray("removed"), "the flow v2 no longer has: " + body);
        assertEquals(List.of("shop/orders", "shop/ping", "shop/refunds"),
                get("/admin/flows").json().getJsonArray("items").stream().map(i -> ((JsonObject) i).getString("id")).toList(), "GET /admin/flows lists v2's flows");
    }

    /** {@code shop} at {@code version} with the sync flows {@code orders} and {@code ping}; {@code orders}' yaml carries {@code text}. */
    static PackageDraft ordersAndPing(String version, String text) {
        Map<String, String> flows = new java.util.LinkedHashMap<>();
        flows.put("orders", "sync");
        flows.put("ping", "sync");
        PackageDraft p = pkg("shop", version, flows);
        p.flowDoc("orders").put("description", text);
        return p;
    }

    /**
     * P1-09d2c (D-063; D-059's 500 gains {@code problems}): a redeploy whose start fails rolls back and answers 500
     * {@code {error, problems}} — the error names the version that did not start and the one that runs again, each problem is a
     * line, one names the flow and the cause — and GET /admin/flows and /admin/packages list what ran before, every flow RUNNING.
     */
    @Test
    void failedStart500HasProblems() throws Exception {
        assertJson(200, put("/admin/packages/shop?revision=r1", ordersAndPing("1.0.0", "v1").zip()), "fixture: v1");
        JsonObject flowsBefore = get("/admin/flows").json();
        JsonObject packagesBefore = get("/admin/packages").json();
        doors.failStartOf("shop/orders", "address in use");

        Answer a = put("/admin/packages/shop?revision=r2", ordersAndPing("1.1.0", "v2").zip());

        assertJson(500, a, "a redeploy whose start failed");
        JsonObject body = a.json();
        assertEquals(Set.of("error", "problems"), body.fieldNames(), "a 500 of a failed start is {error, problems}: " + body);
        String error = body.getString("error");
        assertTrue(error.contains("version 1.1.0 did not start") && error.contains("1.0.0 runs again"), "the error says which version runs: " + body);
        JsonArray problems = body.getJsonArray("problems");
        assertTrue(!problems.isEmpty() && problems.stream().allMatch(String.class::isInstance), "problems are lines: " + problems);
        assertTrue(problems.stream().map(String.class::cast).anyMatch(l -> l.contains("orders") && l.contains("address in use")), "a line names the flow and the cause: " + problems);
        assertEquals(flowsBefore, get("/admin/flows").json(), "v1's flows are listed as before, RUNNING");
        assertEquals(packagesBefore, get("/admin/packages").json(), "the package record is v1's");
    }

    /**
     * P1-09d2c: a redeploy whose start fails and whose rollback cannot start {@code orders} again either answers 500 naming
     * "flows [shop/orders] FAILED"; GET /admin/flows lists orders as before but in state FAILED, ping RUNNING; the package record
     * is v1's and still names orders.
     */
    @Test
    void aFlowWhoseRollbackFailedShowsFAILEDInGetFlows() throws Exception {
        assertJson(200, put("/admin/packages/shop?revision=r1", ordersAndPing("1.0.0", "v1").zip()), "fixture: v1");
        JsonObject flowsBefore = get("/admin/flows").json();
        JsonObject packagesBefore = get("/admin/packages").json();
        doors.failStartOf("shop/orders", "address in use");
        doors.failStartOf("shop/orders", "still in use");

        Answer a = put("/admin/packages/shop?revision=r2", ordersAndPing("1.1.0", "v2").zip());

        assertJson(500, a, "a redeploy whose rollback failed");
        assertEquals(Set.of("error", "problems"), a.json().fieldNames(), "{error, problems}: " + a.json());
        assertTrue(a.json().getString("error").contains("flows [shop/orders] FAILED"), "the error names the FAILED flow: " + a.json());
        JsonObject expected = flowsBefore.copy();
        for (Object o : expected.getJsonArray("items")) {
            JsonObject item = (JsonObject) o;
            if (item.getString("id").equals("shop/orders")) item.put("state", "FAILED");
        }
        assertEquals(expected, get("/admin/flows").json(), "orders is listed as before, FAILED; ping RUNNING");
        assertEquals(packagesBefore, get("/admin/packages").json(), "the package record is v1's, naming orders");
    }

    @Test
    void aBadNameOrRevisionIs400() throws Exception {
        byte[] zip = shop().zip();
        for (String name : List.of("Shop", "1shop", "shop_x", "-shop", "a" + "b".repeat(64))) {
            assertError(400, put("/admin/packages/" + name, zip), "the name " + name);
        }
        for (String revision : List.of("a%20b", "x%2Fy", "r".repeat(65), "")) {
            assertError(400, put("/admin/packages/shop?revision=" + revision, zip), "the revision '" + revision + "'");
        }
        assertEquals(List.of(), doors.events(), "nothing was deployed");

        String longest = "a" + "b".repeat(63);
        String revision = "v1.2_3-" + "x".repeat(57);
        Answer ok = put("/admin/packages/" + longest + "?revision=" + revision, pkg(longest, "1.0.0", Map.of("orders", "sync")).zip());
        assertJson(200, ok, "a 64-character name and revision");
        assertEquals(longest, ok.json().getString("package"));
        assertEquals(revision, ok.json().getString("revision"));
    }

    @Test
    void putAndFlowsNeedTheToken() throws Exception {
        byte[] zip = shop().zip();
        for (String auth : java.util.Arrays.asList(null, "Bearer wrong", TOKEN)) {
            Answer a = request(port, HttpMethod.PUT, "/admin/packages/shop", auth, zip);
            assertError(401, a, "PUT with Authorization " + auth);
            assertError(401, request(port, HttpMethod.GET, "/admin/flows", auth, null), "GET /admin/flows with Authorization " + auth);
            assertError(401, request(port, HttpMethod.GET, "/admin/packages", auth, null), "GET /admin/packages with Authorization " + auth);
        }
        assertEquals(List.of(), doors.events(), "nothing was deployed without the token");
        assertEquals(List.of(), deployments.flows());
        assertJson(200, put("/admin/packages/shop", zip), "with the token it deploys");
    }

    @Test
    void withoutDeploymentsTheRoutesAre404() throws Exception {
        int fiveArg = start(new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(), probe()));
        int empty = serve(Optional.empty());
        for (int at : List.of(fiveArg, empty)) {
            assertError(404, request(at, HttpMethod.PUT, "/admin/packages/shop", AUTH, shop().zip()), "PUT without Deployments");
            assertError(404, request(at, HttpMethod.GET, "/admin/flows", AUTH, null), "GET /admin/flows without Deployments");
            assertError(404, request(at, HttpMethod.GET, "/admin/packages", AUTH, null), "GET /admin/packages without Deployments");
        }
        assertEquals(List.of(), doors.events());
        assertJson(200, get("/admin/flows"), "with Deployments the route exists");
    }

    @Test
    void getFlowsListsDeployedFlowsSortedById() throws Exception {
        assertEquals(new JsonObject().put("items", new JsonArray()), get("/admin/flows").json(), "nothing deployed: no items");
        assertJson(200, put("/admin/packages/zeta", pkg("zeta", "3.0.0", Map.of("beta", "sync", "alpha", "at-least-once")).zip()), "fixture");
        assertJson(200, put("/admin/packages/alpha?revision=r9", pkg("alpha", "2.0.0", Map.of("orders", "at-most-once")).zip()), "fixture");

        Answer a = get("/admin/flows");

        assertJson(200, a, "GET /admin/flows");
        JsonObject expected = new JsonObject().put("items", new JsonArray()
                .add(new JsonObject().put("id", "alpha/orders").put("package", "alpha").put("packageVersion", "2.0.0").put("revision", "r9")
                        .put("flowVersion", "1.0.0").put("delivery", "at-most-once"))
                .add(new JsonObject().put("id", "zeta/alpha").put("package", "zeta").put("packageVersion", "3.0.0").putNull("revision")
                        .put("flowVersion", "1.0.0").put("delivery", "at-least-once"))
                .add(new JsonObject().put("id", "zeta/beta").put("package", "zeta").put("packageVersion", "3.0.0").putNull("revision")
                        .put("flowVersion", "1.0.0").put("delivery", "sync")));
        JsonObject listed = a.json();
        for (Object o : listed.getJsonArray("items")) {   // P1-09d2a (D-063): each item also shows its flow's effective hash and its state
            JsonObject item = (JsonObject) o;
            assertTrue(String.valueOf(item.remove("hash")).matches("sha256:[0-9a-f]{64}"), "the item's effective hash: " + a.json());
            assertEquals("RUNNING", item.remove("state"), "the item's state: " + a.json());
        }
        assertEquals(expected, listed, "every flow by qualified id, sorted by id, revision null without one, not paged");
    }

    @Test
    void getPackagesListsVersionAndFileHashes() throws Exception {
        PackageDraft zeta = pkg("zeta", "3.0.0", Map.of("beta", "sync")).file("xsl/deep/map.xsl", "<xsl:stylesheet/>");
        PackageDraft alpha = pkg("alpha", "2.0.0", Map.of("orders", "sync", "audit", "at-least-once"));
        byte[] zetaZip = zeta.zip();
        byte[] alphaZip = alpha.zip();
        rt.clock().advance(Duration.ofHours(1));
        Instant zetaAt = rt.clock().instant();
        assertJson(200, put("/admin/packages/zeta", zetaZip), "fixture");
        rt.clock().advance(Duration.ofHours(1));
        Instant alphaAt = rt.clock().instant();
        assertJson(200, put("/admin/packages/alpha?revision=r9", alphaZip), "fixture");

        Answer a = get("/admin/packages");

        assertJson(200, a, "GET /admin/packages");
        assertEquals(Set.of("items"), a.json().fieldNames());
        JsonArray items = a.json().getJsonArray("items");
        assertEquals(List.of("alpha", "zeta"), items.stream().map(i -> ((JsonObject) i).getString("name")).toList(), "sorted by name");
        record Expected(PackageDraft p, byte[] zip, String version, Object revision, Instant at, Set<Object> flows) {}
        List<Expected> expected = List.of(new Expected(alpha, alphaZip, "2.0.0", "r9", alphaAt, Set.of("alpha/audit", "alpha/orders")),
                new Expected(zeta, zetaZip, "3.0.0", null, zetaAt, Set.of("zeta/beta")));
        for (int i = 0; i < 2; i++) {
            JsonObject item = items.getJsonObject(i);
            Expected e = expected.get(i);
            // P1-09c3b: every item also carries its state and its problems (packagesListStateAndProblems)
            // P2-02a: and its folders and artifacts (packagesListsFoldersAndArtifacts)
            assertEquals(Set.of("name", "version", "revision", "archive", "deployedAt", "flows", "files", "folders", "artifacts", "state", "problems"), item.fieldNames(),
                    "an item: " + item);
            assertEquals(e.version(), item.getString("version"));
            assertTrue(item.containsKey("revision") && java.util.Objects.equals(e.revision(), item.getValue("revision")), "revision, null without one: " + item);
            assertEquals("sha256:" + PackageDraft.sha256(e.zip()), item.getString("archive"));
            assertEquals(e.at(), Instant.parse(item.getString("deployedAt")), "deployedAt is the node clock's instant at the deploy, ISO-8601: " + item);
            assertEquals(e.flows(), set(item.getJsonArray("flows")));
            Set<Object> files = new HashSet<>();
            e.p().files().forEach((path, bytes) -> files.add(new JsonObject().put("path", path).put("sha256", PackageDraft.sha256(bytes))));
            assertEquals(files, set(item.getJsonArray("files")), "every file, /-separated path and SHA-256: " + item);
        }
    }

    // --- P1-09c3b (D-070): restore lists a package it could not install FAILED, with its problems

    /** A Deployments on {@code dir} over the pool's {@code rows} and {@code archives} (the 13-arg constructor). */
    Deployments deployments(Path dir, DeploymentStore rows, ArchiveStore archives) {
        Map<String, String> e = new HashMap<>();
        e.put("FLOTTILA_ENV", "dev");
        e.put("FLOTTILA_NODE_ID", "node-1");
        e.put("FLOTTILA_DATA_DIR", dir.toString());
        e.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(MAX));
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        Deployments d = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                new InMemoryWorkQueue(rt.clock()), () -> 0L, rows, archives);
        made.add(d);
        return d;
    }

    /**
     * GET /admin/packages after a restore: every item gains {@code state} and {@code problems}. alpha restored is RUNNING with
     * {@code problems: []}; zeta, whose archive is gone from the archive store, is FAILED — its version, revision (null), archive
     * and deployedAt from its row, {@code flows: []}, {@code files: []}, and problem lines (strings), one naming its archive —
     * and GET /admin/flows lists alpha's flow only.
     */
    @Test
    void packagesListStateAndProblems() throws Exception {
        InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
        Path bucket = tmp.resolve("bucket");
        DirArchiveStore archives = new DirArchiveStore(bucket);
        Deployments first = deployments(tmp.resolve("node-1"), rows, archives);
        byte[] alpha = pkg("alpha", "2.0.0", Map.of("orders", "sync")).zip();
        byte[] zeta = pkg("zeta", "3.0.0", Map.of("beta", "sync")).zip();
        Path uploads = tmp.resolve("uploads");
        OnVirtualThread.call(() -> first.deploy("alpha", Optional.of("r9"), PackageDraft.write(uploads, alpha)));
        rt.clock().advance(Duration.ofHours(1));
        OnVirtualThread.call(() -> first.deploy("zeta", Optional.empty(), PackageDraft.write(uploads, zeta)));
        OnVirtualThread.call(() -> { first.close(); return null; });
        String zetaSha = PackageDraft.sha256(zeta);
        Files.delete(bucket.resolve(zetaSha + ".zip"));
        DeploymentRecord zetaRow = rows.list().await().get(1);
        assertEquals("zeta", zetaRow.name(), "fixture: zeta's row");
        Deployments second = deployments(tmp.resolve("node-2"), rows, archives);
        OnVirtualThread.call(() -> { second.restore(); return null; });
        int at = serve(Optional.of(second));

        Answer a = request(at, HttpMethod.GET, "/admin/packages", AUTH, null);

        assertJson(200, a, "GET /admin/packages");
        JsonArray items = a.json().getJsonArray("items");
        assertEquals(List.of("alpha", "zeta"), items.stream().map(i -> ((JsonObject) i).getString("name")).toList(), "sorted by name: " + a.json());
        Set<String> fields = Set.of("name", "version", "revision", "archive", "deployedAt", "flows", "files", "folders", "artifacts", "state", "problems");
        JsonObject running = items.getJsonObject(0);
        assertEquals(fields, running.fieldNames(), "an item: " + running);
        assertEquals("RUNNING", running.getString("state"), running.encode());
        assertEquals(new JsonArray(), running.getJsonArray("problems"), "a RUNNING package lists no problems: " + running.encode());
        assertEquals(new JsonArray().add("alpha/orders"), running.getJsonArray("flows"), running.encode());

        JsonObject failed = items.getJsonObject(1);
        assertEquals(fields, failed.fieldNames(), "a FAILED item has the same fields: " + failed);
        assertEquals("FAILED", failed.getString("state"), failed.encode());
        JsonArray problems = failed.getJsonArray("problems");
        assertTrue(!problems.isEmpty() && problems.stream().allMatch(String.class::isInstance), "problems are lines: " + failed.encode());
        assertTrue(problems.stream().map(String.class::cast).anyMatch(l -> l.contains(zetaSha)), "a line names the missing archive: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("flows"), "a FAILED package has no flows: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("files"), "nor files: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("folders"), "nor folders (P2-02a): " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("artifacts"), "nor artifacts (P2-02a): " + failed.encode());
        assertEquals("3.0.0", failed.getString("version"), failed.encode());
        assertTrue(failed.containsKey("revision") && failed.getValue("revision") == null, "revision null, as its row: " + failed.encode());
        assertEquals("sha256:" + zetaSha, failed.getString("archive"), failed.encode());
        assertEquals(zetaRow.deployedAt(), Instant.parse(failed.getString("deployedAt")), "deployedAt from its row: " + failed.encode());

        JsonArray flows = request(at, HttpMethod.GET, "/admin/flows", AUTH, null).json().getJsonArray("items");
        assertEquals(List.of("alpha/orders"), flows.stream().map(i -> ((JsonObject) i).getString("id")).toList(), "only alpha's flow runs: " + flows.encode());
    }

    // --- P2-02a (D-078): every item lists its folders and artifacts; a FAILED package lists none

    /** One folder or artifact item as {@code <field>=<value>} pairs, null shown as {@code null}; its field set asserted first. */
    static String item(JsonObject o, List<String> fields) {
        assertEquals(Set.copyOf(fields), o.fieldNames(), "the item's fields, null included: " + o);
        return String.join(" ", fields.stream().map(f -> f + "=" + o.getValue(f)).toList());
    }

    static final String FOLDER_ONLY = "schemaVersion: 1\nkind: folder\n";

    /**
     * GET /admin/packages: a package deployed with a flow in a namespace folder and a test two folders deep lists
     * {@code folders:[{path,namespace,description}]} (every folder below the root, null where folder.yaml sets nothing or there is
     * none, the namespace verbatim) and {@code artifacts:[{path,kind,id}]} (a test's id null), each sorted by path. A package restored
     * FAILED — its archive loads with folders and a flow, but its test names a fixture next to itself that is not at that path from
     * the package root (a package deployed before D-078) — lists {@code folders: []} and {@code artifacts: []}.
     */
    @Test
    void packagesListsFoldersAndArtifacts() throws Exception {
        PackageDraft shop = pkg("shop", "1.0.0", Map.of("orders", "sync")).move("flows/orders.yaml", "urn-a/orders.yaml")
                .file("urn-a/folder.yaml", FOLDER_ONLY + "namespace: \" urn:Example:A\"\ndescription: namespace a\n")
                .file("urn-b/v2/folder.yaml", FOLDER_ONLY)
                .file("urn-b/v2/orders.test.yaml", "schemaVersion: 1\nkind: test\nflow: orders\ncases: [ { name: c, send: fixtures/in.xml, expect: { steps: 1 } } ]\n")
                .file("fixtures/in.xml", "<in/>");
        assertJson(200, put("/admin/packages/shop", shop.zip()), "fixture");

        Answer a = get("/admin/packages");

        assertJson(200, a, "GET /admin/packages");
        JsonObject listed = a.json().getJsonArray("items").getJsonObject(0);
        assertTrue(listed.getValue("folders") instanceof JsonArray && listed.getValue("artifacts") instanceof JsonArray, "folders and artifacts are arrays: " + listed.encode());
        List<String> folderFields = List.of("path", "namespace", "description");
        assertEquals(List.of("path=endpoints namespace=null description=null", "path=fixtures namespace=null description=null",
                        "path=urn-a namespace= urn:Example:A description=namespace a", "path=urn-b namespace=null description=null",
                        "path=urn-b/v2 namespace=null description=null"),
                listed.getJsonArray("folders").stream().map(o -> item((JsonObject) o, folderFields)).toList(), listed.encode());
        List<String> artifactFields = List.of("path", "kind", "id");
        assertEquals(List.of("path=endpoints/orders-in.yaml kind=endpoint id=orders-in", "path=endpoints/partner.yaml kind=endpoint id=partner",
                        "path=urn-a/orders.yaml kind=flow id=orders", "path=urn-b/v2/orders.test.yaml kind=test id=null"),
                listed.getJsonArray("artifacts").stream().map(o -> item((JsonObject) o, artifactFields)).toList(), listed.encode());

        InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
        DirArchiveStore bucket = new DirArchiveStore(tmp.resolve("bucket"));
        byte[] legacy = pkg("legacy", "1.0.0", Map.of("beta", "sync")).move("flows/beta.yaml", "urn-a/beta.yaml")
                .file("urn-a/folder.yaml", FOLDER_ONLY + "namespace: urn:legacy\n")
                .file("urn-a/beta.test.yaml", "schemaVersion: 1\nkind: test\nflow: beta\ncases: [ { name: c, send: in.xml, expect: { steps: 1 } } ]\n")
                .file("urn-a/in.xml", "<in/>").zip();
        String sha = PackageDraft.sha256(legacy);
        Path upload = PackageDraft.write(tmp.resolve("uploads"), legacy);
        OnVirtualThread.call(() -> { bucket.put(sha, upload); return null; });
        rows.save(new DeploymentRecord("legacy", "1.0.0", Optional.empty(), sha, rt.clock().instant())).await(10, TimeUnit.SECONDS);
        Deployments restoring = deployments(tmp.resolve("node-2"), rows, bucket);
        OnVirtualThread.call(() -> { restoring.restore(); return null; });
        int at = serve(Optional.of(restoring));

        JsonObject failed = request(at, HttpMethod.GET, "/admin/packages", AUTH, null).json().getJsonArray("items").getJsonObject(0);

        assertEquals("FAILED", failed.getString("state"), "send: in.xml is not a path from the package root: " + failed.encode());
        assertTrue(failed.getJsonArray("problems").stream().map(String::valueOf).anyMatch(l -> l.contains("urn-a/beta.test.yaml") && l.contains("in.xml")),
                "a problem names the test and its fixture: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("folders"), "a FAILED package lists no folders: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("artifacts"), "nor artifacts: " + failed.encode());
    }
}
