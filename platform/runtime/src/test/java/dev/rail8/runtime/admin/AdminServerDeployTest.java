package dev.rail8.runtime.admin;

import dev.rail8.api.Ack;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeploymentRecord;
import dev.rail8.runtime.deploy.DeploymentStore;
import dev.rail8.runtime.deploy.DeploymentStore.PoolLock;
import dev.rail8.runtime.deploy.DeploymentStore.Snapshot;
import dev.rail8.runtime.deploy.Deployments;
import dev.rail8.runtime.deploy.Doors;
import dev.rail8.runtime.deploy.GitRepos;
import dev.rail8.runtime.deploy.InMemoryDeploymentStore;
import dev.rail8.runtime.deploy.OptionSchemaSteps;
import dev.rail8.runtime.deploy.PackageDraft;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The deploy routes of the admin API over a real {@link Deployments} (in-memory stores, a fake runtime, the {@link Doors}
 * component, package repositories under a {@code file://} base). {@code POST /admin/deploy} takes the body
 * {@code {"repository", "commit"}} — above {@code DEPLOY_BODY_MAX} bytes it is 413, by Content-Length before a byte is read, or once
 * a chunked body passes it — deploys that commit, or with {@code ?check=true} checks it, and maps each refusal kind to its status;
 * {@code GET /admin/flows} and {@code GET /admin/packages} list what is deployed and {@code DELETE /admin/packages/{name}}
 * undeploys; all need the token and are 404 without a {@code Deployments}.
 */
class AdminServerDeployTest {

    static final String TOKEN = "s3cret-token";
    static final String AUTH = "Bearer " + TOKEN;
    static final long MAX = 16 * 1024;
    /** The largest body of {@code POST /admin/deploy}. */
    static final int BODY_MAX = 4 * 1024;
    static final String FORBIDDEN = "manual deploy is non-production only (ARCHITECTURE §9)";

    static Vertx vertx;
    static HttpClientAgent client;

    @TempDir Path tmp;
    Path data;
    GitRepos repos;
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
        repos = new GitRepos(tmp.resolve("repos"));
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
        e.put("RAIL8_ENV", env);
        e.put("RAIL8_NODE_ID", "node-1");
        e.put("RAIL8_DATA_DIR", data.toString());
        e.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(MAX));
        e.put("RAIL8_GIT_BASE", env.equals("prod") ? GitRepos.UNSERVED : repos.base().toString());
        if (env.equals("prod")) e.put("RAIL8_GIT_REF", "refs/heads/main");
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
                    req.response().onSuccess(HttpClientResponse::pause);   // FIX-21: the body may arrive before the compose below attaches to it
                    return body == null ? req.send() : req.send(Buffer.buffer(body));
                })
                .compose(res -> {
                    Future<Buffer> all = res.body();
                    res.resume();
                    return all.map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b));
                });
    }

    /**
     * A deploy of {@code store} whose first consumer start is held (it holds the node's deploy lock), sent and not awaited; returns
     * once that start was reached. Release the gate, then await the answer.
     */
    record Running(Doors.Gate gate, Future<Answer> answer) {}

    Running aDeployHoldingTheLock() throws Exception {
        Doors.Gate gate = doors.holdStart(1);
        Future<Answer> answer = requesting(port, HttpMethod.POST, "/admin/deploy", AUTH, body("store", repos.commit(pkg("store", "1.0.0", Map.of("ping", "sync")))));
        if (!gate.reached().await(20, TimeUnit.SECONDS)) fail("fixture: the deploy holding the lock did not reach its consumer start within 20 s");
        return new Running(gate, answer);
    }

    /** The body of a deploy of {@code commit} of {@code repository}. */
    static byte[] body(String repository, String commit) {
        return new JsonObject().put("repository", repository).put("commit", commit).encode().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code POST /admin/deploy<query>} with {@code body} on the server at {@code at}. */
    Answer post(int at, String query, byte[] body) throws Exception {
        return request(at, HttpMethod.POST, "/admin/deploy" + query, AUTH, body);
    }

    /** Commits {@code p} to the repository of its name and deploys that commit through the server at {@code at}. */
    Answer deploy(int at, PackageDraft p) throws Exception {
        return post(at, "", body(p.name(), repos.commit(p)));
    }

    Answer deploy(PackageDraft p) throws Exception {
        return deploy(port, p);
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

    String head(String framing) {
        return "POST /admin/deploy HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: " + AUTH + "\r\nContent-Type: application/json\r\n" + framing + "\r\n";
    }

    static void assertRawError(int status, Raw r, String what) {
        assertEquals(status, r.status(), what + ": " + r.body());
        assertTrue(String.valueOf(r.headers().get("content-type")).startsWith("application/json"), what + ": " + r.headers());
        String error = new JsonObject(r.body()).getString("error");
        assertTrue(error != null && !error.isBlank(), what + ": {error}: " + r.body());
    }

    // --- POST /admin/deploy

    @Test
    void aDeployAnswers200WithItsVersionRepositoryCommitAndFlows() throws Exception {
        PackageDraft shop = pkg("shop", "1.2.3", Map.of("orders", "sync", "audit", "at-least-once"));
        String commit = repos.commit(shop);

        Answer a = post(port, "", body("shop", commit));

        assertJson(200, a, "a deploy");
        JsonObject body = a.json();
        assertEquals(Set.of("package", "version", "repository", "commit", "flows", "added", "swapped", "removed"), body.fieldNames(), "the 200 body: " + body);
        assertEquals("shop", body.getString("package"));
        assertEquals("1.2.3", body.getString("version"));
        assertEquals("shop", body.getString("repository"));
        assertEquals(commit, body.getString("commit"));
        assertEquals(Set.of("shop/audit", "shop/orders"), set(body.getJsonArray("flows")), "the flows by their qualified ids");
        assertEquals(new JsonArray().add("shop/audit").add("shop/orders"), body.getJsonArray("added"), "a first deploy adds every flow, sorted");
        assertEquals(new JsonArray(), body.getJsonArray("swapped"));
        assertEquals(new JsonArray(), body.getJsonArray("removed"));
        assertTrue(doors.door("shop/orders").started(), "the flows were started");

        String head = repos.commit(pkg("store", "1.0.0", Map.of("ping", "at-most-once")));
        Answer byRef = post(port, "", body("store", "main"));
        assertJson(200, byRef, "a deploy of a branch, in dev");
        assertEquals(head, byRef.json().getString("commit"), "the commit the branch names: " + byRef.json());
    }

    /**
     * {@code ?check=true} answers what the deploy would do, as the deploy answers it, and changes nothing: no flow starts, nothing
     * is listed, no record or audit row is written, nothing is left in {@code .incoming}; a refused check answers the deploy's
     * refusal. A {@code check} other than {@code true} or {@code false} is 400.
     */
    @Test
    void aCheckAnswersAsTheDeployWouldAndChangesNothing() throws Exception {
        assertJson(200, deploy(pkg("shop", "1.0.0", Map.of("orders", "sync"))), "fixture: v1 runs");
        JsonObject flows = get("/admin/flows").json();
        JsonObject packages = get("/admin/packages").json();
        List<String> events = doors.events();
        PackageDraft v2 = pkg("shop", "1.1.0", Map.of("orders", "sync", "ping", "sync"));
        String commit = repos.commit(v2);

        Answer a = post(port, "?check=true", body("shop", commit));

        assertJson(200, a, "a check");
        assertEquals(new JsonObject().put("package", "shop").put("version", "1.1.0").put("repository", "shop").put("commit", commit)
                .put("flows", new JsonArray().add("shop/orders").add("shop/ping")).put("added", new JsonArray().add("shop/ping"))
                .put("swapped", new JsonArray()).put("removed", new JsonArray()), a.json(), "what the deploy would answer");
        assertEquals(flows, get("/admin/flows").json(), "the flows listed are v1's");
        assertEquals(packages, get("/admin/packages").json(), "the package listed is v1");
        assertTrue(doors.events().stream().skip(events.size()).noneMatch(e -> e.startsWith("start")), "no consumer started: " + doors.events());
        assertEquals(List.of(), entries(deployments.incomingDir()), "nothing is left in .incoming");

        PackageDraft invalid = pkg("shop", "1.2.0", Map.of("orders", "sync"));
        invalid.manifest().put("dependencies", new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.0.0")));
        assertJson(422, post(port, "?check=true", body("shop", repos.commit(invalid))), "a check the deploy would refuse");
        assertError(400, post(port, "?check=yes", body("shop", commit)), "check is true or false");
        assertJson(200, post(port, "?check=false", body("shop", commit)), "check=false deploys");
        assertEquals("1.1.0", names(get("/admin/packages")).isEmpty() ? null : get("/admin/packages").json().getJsonArray("items").getJsonObject(0).getString("version"));
    }

    @Test
    void aBodyAboveTheLimitIs413Unread() throws Exception {
        try (Socket s = socket()) {
            write(s.getOutputStream(), head("Content-Length: " + (BODY_MAX + 1) + "\r\n"));   // the head only: not a byte of the body is sent

            Raw r = read(s.getInputStream());

            assertRawError(413, r, "a Content-Length above the limit is refused on the head, the body never read");
            assertEquals("the request body is above " + BODY_MAX + " bytes", new JsonObject(r.body()).getString("error"));
        }
        assertEquals(List.of(), doors.events(), "nothing was deployed");

        try (Socket s = socket()) {   // exactly the limit is not too large: it reaches the route, which finds no JSON
            write(s.getOutputStream(), head("Content-Length: " + BODY_MAX + "\r\n"));
            s.getOutputStream().write(filler(BODY_MAX));
            s.getOutputStream().flush();
            assertRawError(400, read(s.getInputStream()), "a body of exactly the limit is read (and refused as no JSON)");
        }
    }

    /**
     * A client that sends {@code Expect: 100-continue} sends its body only after the server's {@code 100 Continue}: the 100 is
     * read on the socket before a byte of the body is written (a server that waits for the body fails on the 10 s read timeout).
     */
    @Test
    void anExpectContinueDeployIsAnsweredWithoutWaiting() throws Exception {
        byte[] body = body("shop", repos.commit(shop()));
        try (Socket s = socket()) {
            write(s.getOutputStream(), head("Content-Length: " + body.length + "\r\nExpect: 100-continue\r\n"));

            Raw interim = read(s.getInputStream());

            assertEquals(100, interim.status(), "100 Continue answers the head before the body is sent: " + interim);
            s.getOutputStream().write(body);
            s.getOutputStream().flush();
            Raw done = read(s.getInputStream());
            assertEquals(200, done.status(), "then the body deploys: " + done.body());
            assertEquals("shop", new JsonObject(done.body()).getString("package"));
        }
        assertTrue(doors.door("shop/orders").started(), "the package was deployed");
    }

    @Test
    void aChunkedBodyAboveTheLimitIs413() throws Exception {
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            write(out, head("Transfer-Encoding: chunked\r\n"));
            chunk(out, filler(512));
            chunk(out, filler(BODY_MAX + 1 - 512));   // byte max+1 arrives; the last chunk never does

            Raw r = read(s.getInputStream());
            assertRawError(413, r, "the counted body passed the limit");
        }
        assertEquals(List.of(), doors.events(), "nothing was deployed");

        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            write(out, head("Transfer-Encoding: chunked\r\n"));
            chunk(out, filler(512));
            chunk(out, filler(BODY_MAX - 512));
            write(out, "0\r\n\r\n");
            assertRawError(400, read(s.getInputStream()), "a chunked body of exactly the limit is read (and refused as no JSON)");
        }
    }

    /** A body other than {@code {"repository", "commit"}}, both strings and nothing else, is 400 before anything is fetched. */
    @Test
    void aBodyOfAnotherShapeIs400() throws Exception {
        String commit = repos.commit(shop());
        for (String body : List.of("", "not json", "[]", "{\"repository\":\"shop\"}", "{\"repository\":\"shop\",\"commit\":7}",
                "{\"repository\":\"shop\",\"commit\":\"" + commit + "\",\"actor\":\"me\"}")) {
            Answer a = request(port, HttpMethod.POST, "/admin/deploy", AUTH, body.isEmpty() ? null : body.getBytes(StandardCharsets.UTF_8));
            assertError(400, a, "the body '" + body + "'");
            assertTrue(a.json().getString("error").contains("the body is {\"repository\": \"<name>\", \"commit\": \"<commit or ref>\"}"), a.json().encode());
        }
        assertEquals(List.of(), doors.events(), "nothing was deployed");
        assertEquals(List.of(), entries(deployments.incomingDir()), "nothing was written");
    }

    /**
     * A step option outside its factory's {@code configSchema()} is a 422 {@code {error, problems}} whose line names
     * the flow, the step's position and name, its type and the option's path; nothing is deployed.
     */
    @Test
    void aStepOptionOutsideItsSchemaIs422WithItsLine() throws Exception {
        PackageDraft p = shop();
        p.flowDoc("orders").put("steps", new JsonArray().add(OptionSchemaSteps.Sheet.step("bad", 7)).addAll(PackageDraft.toPartner()));

        Answer refused = deploy(p);

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
        assertError(400, post(port, "", body("Shop!", "main")), "BAD_REQUEST: a repository name");
        assertError(400, post(port, "", body("shop", "a..b")), "BAD_REQUEST: neither a commit nor a ref");

        PackageDraft invalid = shop();
        invalid.manifest().put("dependencies", new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.0.0")));
        Answer unprocessable = deploy(invalid);
        assertJson(422, unprocessable, "INVALID");
        JsonObject body = unprocessable.json();
        assertEquals(Set.of("error", "problems"), body.fieldNames(), "a 422 is {error, problems}: " + body);
        assertTrue(!body.getString("error").isBlank());
        JsonArray problems = body.getJsonArray("problems");
        assertTrue(!problems.isEmpty() && problems.stream().allMatch(String.class::isInstance), "problems are lines: " + problems);
        assertTrue(problems.stream().map(String.class::cast).anyMatch(l -> l.contains("dependencies")), "a line names the problem: " + problems);
        Answer missing = post(port, "", body("shop", "no-such-branch"));
        assertError(422, missing, "INVALID: a ref the repository does not have");
        assertEquals("shop at no-such-branch: not found", missing.json().getString("error"));

        doors.failStart(1, "address in use");
        Answer failed = deploy(shop());
        assertError(500, failed, "FAILED");
        assertTrue(failed.json().getString("error").startsWith("flow 'shop/orders' did not start: ") && failed.json().getString("error").contains("address in use"),
                "the 500 names the flow and the cause: " + failed.json());

        assertJson(200, deploy(shop()), "fixture: shop deploys");
        assertJson(200, deploy(shop()), "the running package again is a redeploy, not a conflict");
        Running running = aDeployHoldingTheLock();
        assertError(409, deploy(shop()), "CONFLICT: another deploy holds the node's lock");
        running.gate().release();
        assertJson(200, running.answer().await(20, TimeUnit.SECONDS), "fixture: the deploy that held the lock completes");
        Answer fork = post(port, "", body("shop-fork", repos.commit("shop-fork", shop())));
        assertError(409, fork, "CONFLICT: shop is deployed from another repository");
        assertEquals("package 'shop' is deployed from the repository 'shop', not 'shop-fork': a package comes from one repository; undeploy it first to deploy"
                + " it from another", fork.json().getString("error"));

        Deployments closed = deployments("dev");
        OnVirtualThread.call(() -> { closed.close(); return null; });
        assertError(503, deploy(serve(Optional.of(closed)), pkg("store", "1.0.0", Map.of("ping", "sync"))), "STOPPING");

        Answer forbidden = deploy(serve(Optional.of(deployments("prod"))), pkg("store", "1.0.0", Map.of("ping", "sync")));
        assertError(403, forbidden, "FORBIDDEN");
        assertEquals(FORBIDDEN, forbidden.json().getString("error"));
        Answer noBase = deploy(serve(Optional.of(deployments(tmp.resolve("node-nobase"), new InMemoryDeploymentStore(), false))), shop());
        assertError(403, noBase, "FORBIDDEN: the node has no git base");
        assertEquals("the node has no RAIL8_GIT_BASE: it deploys nothing", noBase.json().getString("error"));
    }

    /** Whatever the deploy answered (409: another deploy runs), nothing of this request is left in incomingDir() once the answer is out. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {200, 400, 409, 422})
    void nothingIsLeftInIncomingWhateverTheOutcome(int status) throws Exception {
        byte[] body = switch (status) {
            case 200, 409 -> body("shop", repos.commit(shop()));
            case 400 -> body("Shop!", "main");
            case 422 -> {
                PackageDraft invalid = shop();
                invalid.manifest().put("dependencies", new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.0.0")));
                yield body("shop", repos.commit(invalid));
            }
            default -> throw new IllegalArgumentException("no fixture for " + status);
        };
        Running running = status == 409 ? aDeployHoldingTheLock() : null;   // its own folder stays in incomingDir() until it answers
        List<Path> before = entries(deployments.incomingDir());
        if (running == null) assertEquals(List.of(), before, "fixture: nothing in incomingDir() before the deploy");

        Answer a = post(port, "", body);

        assertJson(status, a, "the deploy answered " + status);
        assertEquals(before, entries(deployments.incomingDir()), "no folder of this request is left after a " + status);
        if (running != null) {
            running.gate().release();
            assertJson(200, running.answer().await(20, TimeUnit.SECONDS), "fixture: the deploy that held the lock completes");
            assertEquals(List.of(), entries(deployments.incomingDir()), "nor of the deploy that held the lock");
        }
    }

    /**
     * A redeploy answers 200 with what it did — {@code added}, {@code swapped} and {@code removed}, sorted qualified ids — beside
     * the package's version, repository, commit and every flow it now runs; a first deploy adds every flow.
     */
    @Test
    void redeploy200ListsAddedSwappedRemoved() throws Exception {
        Map<String, String> v1Flows = new java.util.LinkedHashMap<>();
        v1Flows.put("orders", "sync");
        v1Flows.put("audit", "at-least-once");
        v1Flows.put("ping", "at-most-once");
        Answer first = deploy(pkg("shop", "1.0.0", v1Flows));
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
        String commit = repos.commit(v2);

        Answer a = post(port, "", body("shop", commit));

        assertJson(200, a, "the redeploy");
        JsonObject body = a.json();
        assertEquals(Set.of("package", "version", "repository", "commit", "flows", "added", "swapped", "removed"), body.fieldNames(), "the 200 body: " + body);
        assertEquals("shop", body.getString("package"));
        assertEquals("1.1.0", body.getString("version"));
        assertEquals(commit, body.getString("commit"));
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
     * A redeploy whose start fails rolls back and answers 500
     * {@code {error, problems}} — the error names the version that did not start and the one that runs again, each problem is a
     * line, one names the flow and the cause — and GET /admin/flows and /admin/packages list what ran before, every flow RUNNING.
     */
    @Test
    void failedStart500HasProblems() throws Exception {
        assertJson(200, deploy(ordersAndPing("1.0.0", "v1")), "fixture: v1");
        JsonObject flowsBefore = get("/admin/flows").json();
        JsonObject packagesBefore = get("/admin/packages").json();
        doors.failStartOf("shop/orders", "address in use");

        Answer a = deploy(ordersAndPing("1.1.0", "v2"));

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
     * A redeploy whose start fails and whose rollback cannot build {@code orders}' consumer again answers 500 naming
     * "flows [shop/orders] FAILED"; GET /admin/flows lists orders as before but in state FAILED, ping RUNNING; the package record
     * is v1's and still names orders.
     */
    @Test
    void aFlowWhoseRollbackFailedShowsFAILEDInGetFlows() throws Exception {
        assertJson(200, deploy(ordersAndPing("1.0.0", "v1")), "fixture: v1");
        JsonObject flowsBefore = get("/admin/flows").json();
        JsonObject packagesBefore = get("/admin/packages").json();
        doors.failStartOf("shop/orders", "address in use");
        AtomicInteger builds = new AtomicInteger();
        doors.onConsumer(cfg -> {   // the second: v2 builds the first
            if ("shop/orders".equals(cfg.options().get("flow")) && builds.incrementAndGet() == 2) throw new IllegalArgumentException("orders cannot be built again");
        });

        Answer a = deploy(ordersAndPing("1.1.0", "v2"));

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

    /**
     * A redeploy whose start fails and whose rollback cannot start {@code orders} again answers 500 naming no FAILED flow;
     * GET /admin/flows lists orders as before but RESTARTING, with its {@code restart}: one failure, the next attempt a second
     * later, the cause; ping RUNNING. Once a fresh consumer of orders started, it is listed as before.
     */
    @Test
    void aFlowWhoseConsumerIsTriedAgainShowsRESTARTINGInGetFlows() throws Exception {
        assertJson(200, deploy(ordersAndPing("1.0.0", "v1")), "fixture: v1");
        JsonObject flowsBefore = get("/admin/flows").json();
        doors.failStartOf("shop/orders", "address in use");
        doors.failStartOf("shop/orders", "still in use");

        Answer a = deploy(ordersAndPing("1.1.0", "v2"));

        assertJson(500, a, "a redeploy that rolled back");
        assertFalse(a.json().getString("error").contains("FAILED"), "no flow is FAILED: " + a.json());
        JsonObject expected = flowsBefore.copy();
        for (Object o : expected.getJsonArray("items")) {
            JsonObject item = (JsonObject) o;
            if (item.getString("id").equals("shop/orders")) {
                item.put("state", "RESTARTING").put("restart", new JsonObject().put("failures", 1).put("next", rt.clock().instant().plusSeconds(1).toString())
                        .put("cause", "still in use"));
            }
        }
        assertEquals(expected, get("/admin/flows").json(), "orders is listed as before, RESTARTING with its restart; ping RUNNING");

        rt.clock().advance(Duration.ofSeconds(1));

        until(() -> {
            try {
                return flowsBefore.equals(get("/admin/flows").json());
            } catch (Exception unanswered) {
                throw new IllegalStateException(unanswered);
            }
        }, "orders is listed as before once its fresh consumer started");
    }

    /** A repository or a commit off their rules is 400 naming the rule; a 100-character repository and a 40-hex commit deploy. */
    @Test
    void aBadRepositoryOrCommitIs400() throws Exception {
        for (String repository : List.of("", ".", "..", "a/b", "shop!", "a".repeat(101))) {
            assertError(400, post(port, "", body(repository, "main")), "the repository '" + repository + "'");
        }
        for (String commit : List.of("", "a..b", "refs/notes/x", "main~1", "r".repeat(300))) {
            assertError(400, post(port, "", body("shop", commit)), "the commit '" + commit + "'");
        }
        assertEquals(List.of(), doors.events(), "nothing was deployed");

        String longest = "R." + "e".repeat(98);
        String commit = repos.commit(longest, pkg("shop", "1.0.0", Map.of("orders", "sync")));
        Answer ok = post(port, "", body(longest, commit.toUpperCase(Locale.ROOT)));
        assertJson(200, ok, "a 100-character repository, its commit in upper case");
        assertEquals(longest, ok.json().getString("repository"));
        assertEquals(commit, ok.json().getString("commit"), "the commit's id, lower case");
    }

    @Test
    void deployAndFlowsNeedTheToken() throws Exception {
        byte[] body = body("shop", repos.commit(shop()));
        for (String auth : java.util.Arrays.asList(null, "Bearer wrong", TOKEN)) {
            Answer a = request(port, HttpMethod.POST, "/admin/deploy", auth, body);
            assertError(401, a, "POST with Authorization " + auth);
            assertError(401, request(port, HttpMethod.GET, "/admin/flows", auth, null), "GET /admin/flows with Authorization " + auth);
            assertError(401, request(port, HttpMethod.GET, "/admin/packages", auth, null), "GET /admin/packages with Authorization " + auth);
        }
        assertEquals(List.of(), doors.events(), "nothing was deployed without the token");
        assertEquals(List.of(), deployments.flows());
        assertJson(200, post(port, "", body), "with the token it deploys");
    }

    /** An upload to {@code PUT /admin/packages/{name}} deploys nothing: the route takes DELETE only. */
    @Test
    void aPutOfAPackageIsNoRoute() throws Exception {
        Answer a = request(port, HttpMethod.PUT, "/admin/packages/shop", AUTH, "PK".getBytes(StandardCharsets.UTF_8));

        assertEquals(405, a.status(), a.body().toString());
        assertEquals(List.of(), doors.events(), "nothing was deployed");
    }

    @Test
    void withoutDeploymentsTheRoutesAre404() throws Exception {
        int fiveArg = start(new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, rt.blobs(), probe()));
        int empty = serve(Optional.empty());
        for (int at : List.of(fiveArg, empty)) {
            assertError(404, request(at, HttpMethod.POST, "/admin/deploy", AUTH, body("shop", repos.commit(shop()))), "POST without Deployments");
            assertError(404, request(at, HttpMethod.GET, "/admin/flows", AUTH, null), "GET /admin/flows without Deployments");
            assertError(404, request(at, HttpMethod.GET, "/admin/packages", AUTH, null), "GET /admin/packages without Deployments");
        }
        assertEquals(List.of(), doors.events());
        assertJson(200, get("/admin/flows"), "with Deployments the route exists");
    }

    @Test
    void getFlowsListsDeployedFlowsSortedById() throws Exception {
        assertEquals(new JsonObject().put("items", new JsonArray()), get("/admin/flows").json(), "nothing deployed: no items");
        PackageDraft zeta = pkg("zeta", "3.0.0", Map.of("beta", "sync", "alpha", "at-least-once"));
        assertJson(200, deploy(zeta), "fixture");
        PackageDraft alpha = pkg("alpha", "2.0.0", Map.of("orders", "at-most-once"));
        assertJson(200, deploy(alpha), "fixture");

        Answer a = get("/admin/flows");

        assertJson(200, a, "GET /admin/flows");
        JsonObject expected = new JsonObject().put("items", new JsonArray()
                .add(new JsonObject().put("id", "alpha/orders").put("package", "alpha").put("packageVersion", "2.0.0").put("commit", alpha.commit())
                        .put("flowVersion", "1.0.0").put("delivery", "at-most-once"))
                .add(new JsonObject().put("id", "zeta/alpha").put("package", "zeta").put("packageVersion", "3.0.0").put("commit", zeta.commit())
                        .put("flowVersion", "1.0.0").put("delivery", "at-least-once"))
                .add(new JsonObject().put("id", "zeta/beta").put("package", "zeta").put("packageVersion", "3.0.0").put("commit", zeta.commit())
                        .put("flowVersion", "1.0.0").put("delivery", "sync")));
        JsonObject listed = a.json();
        for (Object o : listed.getJsonArray("items")) {   // each item also shows its flow's effective hash and its state
            JsonObject item = (JsonObject) o;
            assertTrue(String.valueOf(item.remove("hash")).matches("sha256:[0-9a-f]{64}"), "the item's effective hash: " + a.json());
            assertEquals("RUNNING", item.remove("state"), "the item's state: " + a.json());
        }
        assertEquals(expected, listed, "every flow by qualified id, sorted by id, with its package's commit, not paged");
    }

    @Test
    void getPackagesListsVersionAndFileHashes() throws Exception {
        PackageDraft zeta = pkg("zeta", "3.0.0", Map.of("beta", "sync")).file("xsl/deep/map.xsl", "<xsl:stylesheet/>");
        PackageDraft alpha = pkg("alpha", "2.0.0", Map.of("orders", "sync", "audit", "at-least-once"));
        rt.clock().advance(Duration.ofHours(1));
        Instant zetaAt = rt.clock().instant();
        assertJson(200, deploy(zeta), "fixture");
        rt.clock().advance(Duration.ofHours(1));
        Instant alphaAt = rt.clock().instant();
        assertJson(200, deploy(alpha), "fixture");

        Answer a = get("/admin/packages");

        assertJson(200, a, "GET /admin/packages");
        assertEquals(Set.of("items"), a.json().fieldNames());
        JsonArray items = a.json().getJsonArray("items");
        assertEquals(List.of("alpha", "zeta"), items.stream().map(i -> ((JsonObject) i).getString("name")).toList(), "sorted by name");
        record Expected(PackageDraft p, String version, Instant at, Set<Object> flows) {}
        List<Expected> expected = List.of(new Expected(alpha, "2.0.0", alphaAt, Set.of("alpha/audit", "alpha/orders")),
                new Expected(zeta, "3.0.0", zetaAt, Set.of("zeta/beta")));
        for (int i = 0; i < 2; i++) {
            JsonObject item = items.getJsonObject(i);
            Expected e = expected.get(i);
            // every item also carries its state and problems, its folders and artifacts, its content digest and dependencies
            assertEquals(Set.of("name", "version", "repository", "commit", "deployedAt", "flows", "files", "folders", "artifacts", "state", "problems", "content",
                    "dependencies"), item.fieldNames(), "an item: " + item);
            assertEquals(e.p().content(), item.getString("content"), "the content digest of its files: " + item);
            assertEquals(new JsonArray(), item.getJsonArray("dependencies"), "no dependencies: " + item);
            assertEquals(e.version(), item.getString("version"));
            assertEquals(e.p().name(), item.getString("repository"), "the repository of its name: " + item);
            assertEquals(e.p().commit(), item.getString("commit"), "the commit deployed: " + item);
            assertEquals(e.at(), Instant.parse(item.getString("deployedAt")), "deployedAt is the node clock's instant at the deploy, ISO-8601: " + item);
            assertEquals(e.flows(), set(item.getJsonArray("flows")));
            Set<Object> files = new HashSet<>();
            e.p().files().forEach((path, bytes) -> files.add(new JsonObject().put("path", path).put("sha256", PackageDraft.sha256(bytes))));
            assertEquals(files, set(item.getJsonArray("files")), "every file, /-separated path and SHA-256: " + item);
        }
    }

    // --- restore lists a package it could not install FAILED, with its problems

    /** A Deployments in dev on {@code dir} over the pool's {@code rows}, reading the pool's repositories when {@code git}. */
    Deployments deployments(Path dir, DeploymentStore rows, boolean git) {
        Map<String, String> e = new HashMap<>();
        e.put("RAIL8_ENV", "dev");
        e.put("RAIL8_NODE_ID", "node-1");
        e.put("RAIL8_DATA_DIR", dir.toString());
        e.put("RAIL8_MAX_PACKAGE_BYTES", Long.toString(MAX));
        if (git) e.put("RAIL8_GIT_BASE", repos.base().toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        Deployments d = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                new InMemoryWorkQueue(rt.clock()), () -> 0L, rows);
        made.add(d);
        return d;
    }

    /**
     * GET /admin/packages after a restore: every item shows {@code state} and {@code problems}. alpha restored is RUNNING with
     * {@code problems: []}; zeta, whose commit is gone from its repository, is FAILED — its version, repository, commit and
     * deployedAt from its record, {@code flows: []}, {@code files: []}, and its problem line naming the commit — and
     * GET /admin/flows lists alpha's flow only.
     */
    @Test
    void packagesListStateAndProblems() throws Exception {
        InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
        Deployments first = deployments(tmp.resolve("node-1"), rows, true);
        String alpha = repos.commit(pkg("alpha", "2.0.0", Map.of("orders", "sync")));
        String zeta = repos.commit(pkg("zeta", "3.0.0", Map.of("beta", "sync")));
        OnVirtualThread.call(() -> first.deploy("alpha", alpha, "test"));
        rt.clock().advance(Duration.ofHours(1));
        OnVirtualThread.call(() -> first.deploy("zeta", zeta, "test"));
        OnVirtualThread.call(() -> { first.close(); return null; });
        repos.delete("zeta");
        DeploymentRecord zetaRow = rows.read().await().records().get(1);
        assertEquals("zeta", zetaRow.name(), "fixture: zeta's row");
        Deployments second = deployments(tmp.resolve("node-2"), rows, true);
        OnVirtualThread.call(() -> { second.restore(); return null; });
        int at = serve(Optional.of(second));

        Answer a = request(at, HttpMethod.GET, "/admin/packages", AUTH, null);

        assertJson(200, a, "GET /admin/packages");
        JsonArray items = a.json().getJsonArray("items");
        assertEquals(List.of("alpha", "zeta"), items.stream().map(i -> ((JsonObject) i).getString("name")).toList(), "sorted by name: " + a.json());
        Set<String> fields = Set.of("name", "version", "repository", "commit", "deployedAt", "flows", "files", "folders", "artifacts", "state", "problems",
                "content", "dependencies");
        JsonObject running = items.getJsonObject(0);
        assertEquals(fields, running.fieldNames(), "an item: " + running);
        assertEquals("RUNNING", running.getString("state"), running.encode());
        assertEquals(new JsonArray(), running.getJsonArray("problems"), "a RUNNING package lists no problems: " + running.encode());
        assertEquals(new JsonArray().add("alpha/orders"), running.getJsonArray("flows"), running.encode());

        JsonObject failed = items.getJsonObject(1);
        assertEquals(fields, failed.fieldNames(), "a FAILED item has the same fields: " + failed);
        assertEquals("FAILED", failed.getString("state"), failed.encode());
        assertEquals(new JsonArray().add("zeta at " + zeta + ": not found"), failed.getJsonArray("problems"), "its problem names the commit: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("flows"), "a FAILED package has no flows: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("files"), "nor files: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("folders"), "nor folders: " + failed.encode());
        assertEquals(new JsonArray(), failed.getJsonArray("artifacts"), "nor artifacts: " + failed.encode());
        assertTrue(failed.containsKey("content") && failed.getValue("content") == null, "nor a content digest: null: " + failed.encode());
        assertEquals("3.0.0", failed.getString("version"), failed.encode());
        assertEquals("zeta", failed.getString("repository"), failed.encode());
        assertEquals(zeta, failed.getString("commit"), failed.encode());
        assertEquals(zetaRow.deployedAt(), Instant.parse(failed.getString("deployedAt")), "deployedAt from its row: " + failed.encode());

        JsonArray flows = request(at, HttpMethod.GET, "/admin/flows", AUTH, null).json().getJsonArray("items");
        assertEquals(List.of("alpha/orders"), flows.stream().map(i -> ((JsonObject) i).getString("id")).toList(), "only alpha's flow runs: " + flows.encode());
    }

    // --- DELETE /admin/packages/{name} undeploys; the deploy's guards, its status map plus 404

    static final String UNDEPLOY_FORBIDDEN = "manual undeploy is non-production only (ARCHITECTURE §9)";

    Answer delete(int at, String path, String authorization) throws Exception {
        return request(at, HttpMethod.DELETE, path, authorization, null);
    }

    Answer delete(String path) throws Exception {
        return delete(port, path, AUTH);
    }

    static List<String> names(Answer listing) {
        return listing.json().getJsonArray("items").stream().map(i -> ((JsonObject) i).getString("name")).toList();
    }

    static List<String> ids(Answer listing) {
        return listing.json().getJsonArray("items").stream().map(i -> ((JsonObject) i).getString("id")).toList();
    }

    /**
     * DELETE of shop (two flows) answers 200 {name, version, flowsRemoved} with its flows by qualified id, sorted; shop's consumers
     * are stopped, GET /admin/packages and GET /admin/flows no longer list it (store stays), its flow answers as an unknown flow.
     */
    @Test
    void deleteUndeploysAndAnswers200WithTheFlowsRemoved() throws Exception {
        assertJson(200, deploy(pkg("shop", "1.2.3", Map.of("orders", "sync", "audit", "at-least-once"))), "fixture");
        assertJson(200, deploy(pkg("store", "1.0.0", Map.of("ping", "sync"))), "fixture");
        assertJson(200, get("/admin/flows/shop%2Forders/cpu"), "fixture: shop/orders is a flow of this node");

        Answer a = delete("/admin/packages/shop");

        assertJson(200, a, "an undeploy");
        assertEquals(new JsonObject().put("name", "shop").put("version", "1.2.3").put("flowsRemoved", new JsonArray().add("shop/audit").add("shop/orders")),
                a.json(), "the 200 body: the package, its version, its flows sorted");
        assertTrue(doors.door("shop/orders").stopped() && doors.door("shop/audit").stopped(), "shop's consumers are stopped: " + doors.events());
        assertTrue(!doors.door("store/ping").stopped(), "store's runs on");
        assertEquals(List.of("store"), names(get("/admin/packages")), "GET /admin/packages no longer lists shop");
        assertEquals(List.of("store/ping"), ids(get("/admin/flows")), "GET /admin/flows no longer lists its flows");
        Answer unknown = get("/admin/flows/shop%2Forders/cpu");
        assertError(404, unknown, "shop/orders after the undeploy");
        assertEquals("no flow shop/orders deployed on this node", unknown.json().getString("error"), "it answers as an unknown flow");
    }

    /** DELETE of a package the node does not have — never deployed, or undeployed already — is 404 {error}. */
    @Test
    void deleteOfAPackageNotDeployedIs404() throws Exception {
        Answer none = delete("/admin/packages/ghost");

        assertError(404, none, "no package ghost");
        assertEquals("no package 'ghost' is deployed", none.json().getString("error"));

        assertJson(200, deploy(shop()), "fixture");
        assertJson(200, delete("/admin/packages/shop"), "fixture: shop undeploys");
        Answer again = delete("/admin/packages/shop");
        assertError(404, again, "shop undeployed already");
        assertEquals("no package 'shop' is deployed", again.json().getString("error"));
    }

    /** base, pinned by mid (direct) and top (through mid's lock), undeploys: 200; mid and top stay listed, top runs on. */
    @Test
    void deleteOfAPackageOthersDependOnUndeploysIt() throws Exception {
        PackageDraft base = PackageDraft.of("base", "1.0.0").file("xslt/base.xsl", "<xsl:stylesheet/>");
        assertJson(200, deploy(base), "fixture");
        PackageDraft mid = PackageDraft.of("mid", "2.1.0").file("xslt/mid.xsl", "<xsl:stylesheet/>").dependingOn(base);
        assertJson(200, deploy(mid), "fixture");
        assertJson(200, deploy(pkg("top", "1.0.0", Map.of("orders", "sync")).dependingOn(mid)), "fixture");

        Answer a = delete("/admin/packages/base");

        assertJson(200, a, "base undeploys");
        assertEquals(new JsonObject().put("name", "base").put("version", "1.0.0").put("flowsRemoved", new JsonArray()), a.json());
        assertEquals(List.of("mid", "top"), names(get("/admin/packages")), "its dependents stay listed");
        assertTrue(!doors.door("top/orders").stopped(), "top runs on");
    }

    /**
     * shop's at-least-once flow audit holds an item (its send held at the partner): DELETE shop is 409 {error} with the flow's
     * line, and shop stays listed and runs; the item done, DELETE undeploys.
     */
    @Test
    void deleteOfAPackageWhoseAtLeastOnceFlowHasQueueItemsIs409() throws Exception {
        assertJson(200, deploy(pkg("shop", "1.0.0", Map.of("orders", "sync", "audit", "at-least-once"))), "fixture");
        CountDownLatch release = doors.partner().holdNext(1);
        try {
            Ack accepted = doors.door("shop/audit").deliver(rt.ingress("shop/audit", Headers.empty(), Buffer.buffer("<order/>"))).await(20, TimeUnit.SECONDS);
            assertTrue(accepted instanceof Ack.Accepted, "fixture: audit accepts: " + accepted);
            doors.partner().awaitSent(1);

            Answer a = delete("/admin/packages/shop");

            assertError(409, a, "audit's item is in the work queue");
            assertEquals("flow 'audit' runs at-least-once and the work queue holds items of it (queued, leased, held or stopped): undeploy it once they are done",
                    a.json().getString("error"));
            assertEquals(List.of("shop"), names(get("/admin/packages")), "shop stays listed");
            assertTrue(!doors.door("shop/audit").stopped() && !doors.door("shop/orders").stopped(), "shop runs on: " + doors.events());
        } finally {
            release.countDown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Answer done;
        while ((done = delete("/admin/packages/shop")).status() == 409 && System.nanoTime() < deadline) {
            Thread.onSpinWait();   // the item finishes on its worker once released: retried until it is gone, bounded
        }
        assertJson(200, done, "witness: the item done, DELETE undeploys");
    }

    /** A node in a deployed environment answers DELETE 403 {error}, as it answers a deploy. */
    @Test
    void deleteInADeployedEnvironmentIs403() throws Exception {
        int prod = serve(Optional.of(deployments("prod")));

        Answer a = delete(prod, "/admin/packages/shop", AUTH);

        assertError(403, a, "FORBIDDEN");
        assertEquals(UNDEPLOY_FORBIDDEN, a.json().getString("error"));
    }

    /** Without the token, or with a wrong one, DELETE is 401 and undeploys nothing; with it, 200. */
    @Test
    void deleteNeedsTheToken() throws Exception {
        assertJson(200, deploy(shop()), "fixture");

        for (String auth : java.util.Arrays.asList(null, "Bearer wrong", TOKEN)) {
            assertError(401, delete(port, "/admin/packages/shop", auth), "DELETE with Authorization " + auth);
        }

        assertEquals(List.of("shop"), names(get("/admin/packages")), "nothing was undeployed without the token");
        assertTrue(!doors.door("shop/orders").stopped(), "shop runs on");
        assertJson(200, delete("/admin/packages/shop"), "with the token it undeploys");
        assertEquals(List.of(), names(get("/admin/packages")));
    }

    /**
     * The refusals of an undeploy by the deploy's status map: a bad name 400, another deploy holding the node's lock 409, a closing
     * node 503; a record delete that fails is the node's failure, 500 {error}, and the package stays listed.
     */
    @Test
    void deleteRefusalsMapToTheirStatus() throws Exception {
        assertError(400, delete("/admin/packages/Shop"), "BAD_REQUEST");

        assertJson(200, deploy(shop()), "fixture");
        Running running = aDeployHoldingTheLock();
        assertError(409, delete("/admin/packages/shop"), "CONFLICT: a deploy holds the node's lock");
        running.gate().release();
        assertJson(200, running.answer().await(20, TimeUnit.SECONDS), "fixture: the deploy that held the lock completes");
        assertEquals(List.of("shop", "store"), names(get("/admin/packages")), "shop was not undeployed");

        Deployments closed = deployments("dev");
        OnVirtualThread.call(() -> { closed.close(); return null; });
        assertError(503, delete(serve(Optional.of(closed)), "/admin/packages/shop", AUTH), "STOPPING");

        InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
        DeploymentStore failingDelete = new DeploymentStore() {
            @Override public Future<Snapshot> read() { return rows.read(); }
            @Override public Future<Optional<PoolLock>> lock() {
                return rows.lock().map(held -> held.map(lock -> new PoolLock() {
                    @Override public Snapshot snapshot() { return lock.snapshot(); }
                    @Override public Future<Long> save(DeploymentRecord record) { return lock.save(record); }
                    @Override public Future<Long> delete(String name) { return Future.failedFuture(new IllegalStateException("connection reset by peer")); }
                    @Override public Future<Void> close() { return lock.close(); }
                }));
            }
            @Override public Future<Void> audit(String actor, String repository, JsonObject detail) { return rows.audit(actor, repository, detail); }
            @Override public Future<Void> endMarks() { return rows.endMarks(); }
        };
        Deployments failing = deployments(tmp.resolve("node-3"), failingDelete, true);
        String solo = repos.commit(pkg("solo", "1.0.0", Map.of("one", "sync")));
        OnVirtualThread.call(() -> failing.deploy("solo", solo, "test"));
        int at = serve(Optional.of(failing));

        assertError(500, delete(at, "/admin/packages/solo", AUTH), "a record delete that fails");
        assertEquals(List.of("solo"), names(request(at, HttpMethod.GET, "/admin/packages", AUTH, null)), "solo stays listed");
    }

    // --- every item lists its content digest and its dependencies, the closure, each marked direct or not

    /**
     * base; mid -> base; top -> mid, all deployed: top lists mid (direct) and base (through mid's lock), each with its version,
     * content digest, repository and commit; mid lists base (direct); base none. Each item's content is the digest of its files.
     */
    @Test
    void packagesListsContentAndDependencies() throws Exception {
        PackageDraft base = PackageDraft.of("base", "1.0.0").file("xslt/base.xsl", "<xsl:stylesheet/>");
        assertJson(200, deploy(base), "fixture");
        PackageDraft mid = PackageDraft.of("mid", "2.1.0").file("xslt/mid.xsl", "<xsl:stylesheet/>").dependingOn(base);
        assertJson(200, deploy(mid), "fixture");
        PackageDraft top = pkg("top", "1.0.0", Map.of("orders", "sync")).dependingOn(mid);
        assertJson(200, deploy(top), "fixture");

        Answer a = get("/admin/packages");

        assertJson(200, a, "GET /admin/packages");
        JsonArray items = a.json().getJsonArray("items");
        assertEquals(List.of("base", "mid", "top"), items.stream().map(i -> ((JsonObject) i).getString("name")).toList(), a.json().encode());
        JsonObject baseDep = new JsonObject().put("name", "base").put("version", "1.0.0").put("content", base.content())
                .put("repository", "base").put("commit", base.commit());
        JsonObject midDep = new JsonObject().put("name", "mid").put("version", "2.1.0").put("content", mid.content())
                .put("repository", "mid").put("commit", mid.commit());
        assertEquals(base.content(), items.getJsonObject(0).getString("content"));
        assertEquals(new JsonArray(), items.getJsonObject(0).getJsonArray("dependencies"), "base depends on nothing");
        assertEquals(mid.content(), items.getJsonObject(1).getString("content"));
        assertEquals(new JsonArray().add(baseDep.copy().put("direct", true)), items.getJsonObject(1).getJsonArray("dependencies"), "mid: base, direct");
        assertEquals(top.content(), items.getJsonObject(2).getString("content"));
        assertEquals(Set.of(midDep.copy().put("direct", true), baseDep.copy().put("direct", false)), set(items.getJsonObject(2).getJsonArray("dependencies")),
                "top: mid direct, base through mid: " + items.getJsonObject(2).encode());
        assertEquals(2, items.getJsonObject(2).getJsonArray("dependencies").size(), items.getJsonObject(2).encode());
    }

    // --- every item lists its folders and artifacts; a FAILED package lists none

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
     * FAILED — its commit loads with folders and a flow, but its test names a fixture next to itself that is not at that path from
     * the package root — lists {@code folders: []} and {@code artifacts: []}.
     */
    @Test
    void packagesListsFoldersAndArtifacts() throws Exception {
        PackageDraft shop = pkg("shop", "1.0.0", Map.of("orders", "sync")).move("flows/orders.yaml", "urn-a/orders.yaml")
                .file("urn-a/folder.yaml", FOLDER_ONLY + "namespace: \" urn:Example:A\"\ndescription: namespace a\n")
                .file("urn-b/v2/folder.yaml", FOLDER_ONLY)
                .file("urn-b/v2/orders.test.yaml", "schemaVersion: 1\nkind: test\nflow: orders\ncases: [ { name: c, send: fixtures/in.xml, expect: { steps: 1 } } ]\n")
                .file("fixtures/in.xml", "<in/>");
        assertJson(200, deploy(shop), "fixture");

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
        PackageDraft legacy = pkg("legacy", "1.0.0", Map.of("beta", "sync")).move("flows/beta.yaml", "urn-a/beta.yaml")
                .file("urn-a/folder.yaml", FOLDER_ONLY + "namespace: urn:legacy\n")
                .file("urn-a/beta.test.yaml", "schemaVersion: 1\nkind: test\nflow: beta\ncases: [ { name: c, send: in.xml, expect: { steps: 1 } } ]\n")
                .file("urn-a/in.xml", "<in/>");
        String commit = repos.commit(legacy);
        DeploymentStore.PoolLock seeding = rows.lock().await(10, TimeUnit.SECONDS).orElseThrow();
        seeding.save(new DeploymentRecord("legacy", "1.0.0", "legacy", commit, legacy.content(), rt.clock().instant(), List.of(), List.of())).await(10, TimeUnit.SECONDS);
        seeding.close().await(10, TimeUnit.SECONDS);
        Deployments restoring = deployments(tmp.resolve("node-2"), rows, true);
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
