package io.flottila.runtime.admin;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.api.Body;
import io.flottila.api.Hash;
import io.flottila.api.Hasher;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Key;
import io.flottila.api.Keys;
import io.flottila.api.Props;
import io.flottila.core.dlq.Dlq;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.dlq.Parked;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.MessageStore;
import io.flottila.core.record.Page;
import io.flottila.core.record.Snapshot;
import io.flottila.testkit.InMemoryBlobStore;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * D-057 (a), GATE check 6: the admin read API over the stores — the timeline with wall and CPU nanos per step, the payload
 * after step n, key search and the DLQ paged as {@code {items, next|null}}, {@code /health}, the bearer token on
 * {@code /admin/**}, every route body on a virtual thread, every error body {@code {error}}.
 *
 * <p>The shape these tests assume (P1-09a test-author): {@code AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe)},
 * {@code Config(String host, int port, Optional<String> token)}, {@code HealthProbe(Map<String, Supplier<Future<Health>>> checks,
 * LongSupplier snapshotWriteFailures)}, {@code Future<Integer> start(Vertx)} answering the bound port (port 0 = any),
 * {@code Future<Void> stop()}.
 */
class AdminServerTest {

    static final String TOKEN = "s3cret-token";
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00.123456Z");
    static final long MAX = 1 << 20;

    static Vertx vertx;
    static HttpClientAgent client;

    final RecordingStore messages = new RecordingStore();
    final RecordingDlq dlq = new RecordingDlq();
    final RecordingBlobs blobs = new RecordingBlobs();
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final AtomicReference<Future<Health>> postgres = new AtomicReference<>(Future.succeededFuture(Health.up()));
    final AtomicLong writeFailures = new AtomicLong();
    final List<AdminServer> started = new ArrayList<>();
    int port;

    final Buffer xml = Buffer.buffer("<Invoice><Id>4711</Id></Invoice>");
    final Buffer big = bigBody();
    Body in;
    Body out;

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
        messages.release.countDown();
        for (AdminServer s : started) s.stop().await(10, TimeUnit.SECONDS);
    }

    // --- fixture

    static Buffer bigBody() {
        byte[] b = new byte[300_000];
        for (int i = 0; i < b.length; i++) b[i] = (byte) ('a' + i % 26);
        return Buffer.buffer(b);
    }

    AdminServer.HealthProbe probe() {
        Map<String, Supplier<Future<Health>>> checks = Map.of("postgres", () -> {
            saw("health");
            return postgres.get();
        });
        return new AdminServer.HealthProbe(checks, writeFailures::get);
    }

    int start(String host, Optional<String> token) throws Exception {
        AdminServer server = new AdminServer(new AdminServer.Config(host, 0, token), messages, dlq, blobs, probe());
        int bound = server.start(vertx).await(10, TimeUnit.SECONDS);
        started.add(server);
        assertTrue(bound > 0, "port 0 asks for any free port; start answers the one bound: " + bound);
        port = bound;
        return bound;
    }

    int start() throws Exception {
        return start("127.0.0.1", Optional.of(TOKEN));
    }

    static Snapshot snapshot(String id, int step, String name, Snapshot.Outcome outcome, Optional<String> error, long wall, long cpu, Headers headers,
                             Keys keys, Body body, boolean kept) {
        return new Snapshot(id, "invoice-sync", step, name, outcome, error, T0.plusSeconds(step), wall, cpu, headers,
                Props.empty().with(new Key<String>("PARTNER"), "domestic"), keys, body.hash(), body.size(), kept ? Optional.of(body.key()) : Optional.empty(),
                Map.of("flow", "invoice-sync@0.1.0"));
    }

    /** m-1: ingress (xml kept), validate (metadata only, CPU unknown), map (300 kB JSON kept, nanos above int range), partner FAILED (map's body shared). */
    void recordInvoice() {
        in = blobs.write(BlobKey.body("m-1", 0), xml, MAX).await();
        out = blobs.write(BlobKey.body("m-1", 2), big, MAX).await();
        Headers xmlType = Headers.empty().with("content-type", "application/xml");
        Keys invoice = Keys.empty().with("invoiceId", "4711");
        messages.record(snapshot("m-1", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), 1_500_000L, 20_000L, xmlType, Keys.empty(), in, true)).await();
        messages.record(snapshot("m-1", 1, "validate", Snapshot.Outcome.OK, Optional.empty(), 2_000_000L, Snapshot.CPU_UNKNOWN, xmlType, invoice, in, false)).await();
        messages.record(snapshot("m-1", 2, "map", Snapshot.Outcome.OK, Optional.empty(), 5_000_000_123L, 4_000_000_007L,
                Headers.empty().with("content-type", "application/json"), invoice, out, true)).await();
        messages.record(snapshot("m-1", 3, "partner", Snapshot.Outcome.FAILED, Optional.of("java.lang.IllegalStateException: 503 from partner"), 7L, 3L,
                Headers.empty().with("x-partner", "acme"), invoice, out, true)).await();
    }

    void recordKeyed(String id, Keys keys) {
        Body b = blobs.write(BlobKey.body(id, 0), xml, MAX).await();
        messages.record(snapshot(id, 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), 1L, 1L, Headers.empty(), keys, b, true)).await();
    }

    static Parked parked(String id, Instant at) {
        return new Parked(id, "invoice-sync", 3, "partner", "java.lang.IllegalStateException: 503", at, Headers.empty(), Props.empty(),
                Keys.empty().with("invoiceId", "4711"), new Hash("sha256-64", 9), 187, BlobKey.body(id, 2), Map.of("flow", "invoice-sync@0.1.0"));
    }

    // --- HTTP (vertx-core client)

    record Answer(int status, String contentType, Buffer body, String contentLength) {
        JsonObject json() { return new JsonObject(body); }
        String error() { return json().getString("error"); }
    }

    Future<Answer> send(String path, String authorization) {
        return send(HttpMethod.GET, path, authorization);
    }

    Future<Answer> send(HttpMethod method, String path, String authorization) {
        return client.request(new RequestOptions().setMethod(method).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    return req.send();
                })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b, res.getHeader("content-length"))));
    }

    Answer get(String path, String authorization) throws Exception {
        return send(path, authorization).await(10, TimeUnit.SECONDS);
    }

    Answer admin(String path) throws Exception {
        return get(path, "Bearer " + TOKEN);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static void assertJsonError(int status, Answer a, String what) {
        assertEquals(status, a.status(), what + ": " + a.body());
        assertNotNull(a.contentType(), what + ": a content-type");
        assertTrue(a.contentType().startsWith("application/json"), what + ": " + a.contentType());
        String error = a.error();
        assertTrue(error != null && !error.isBlank(), what + ": {error} names the failure: " + a.body());
    }

    // --- GET /admin/messages/{id}

    @Test
    void timelineHasWallAndCpuNanosPerStep() throws Exception {
        start();
        recordInvoice();
        Answer a = admin("/admin/messages/m-1");
        assertEquals(200, a.status(), a.body().toString());
        assertTrue(a.contentType().startsWith("application/json"), a.contentType());
        JsonObject t = a.json();
        assertEquals("m-1", t.getString("id"));
        assertEquals("invoice-sync", t.getString("flow"));
        JsonArray steps = t.getJsonArray("steps");
        assertEquals(4, steps.size(), "one entry per step, in step order");
        Set<String> fields = Set.of("step", "name", "outcome", "error", "startedAt", "wallNanos", "cpuNanos", "headers", "props", "keys", "body", "versions");
        for (int i = 0; i < 4; i++) {
            JsonObject s = steps.getJsonObject(i);
            assertEquals(fields, s.fieldNames(), "step " + i + ": the D-057 fields, cpuNanos and error present even when null");
            assertEquals(i, s.getInteger("step"));
            assertEquals(T0.plusSeconds(i), Instant.parse(s.getString("startedAt")), "startedAt as ISO-8601, microseconds kept");
            assertEquals(Set.of("hash", "size", "kept"), s.getJsonObject("body").fieldNames());
            assertEquals(new JsonObject().put("flow", "invoice-sync@0.1.0"), s.getJsonObject("versions"));
            assertEquals(new JsonObject().put("PARTNER", "domestic"), s.getJsonObject("props"));
        }
        JsonObject ingress = steps.getJsonObject(0);
        assertEquals("ingress", ingress.getString("name"));
        assertEquals("OK", ingress.getString("outcome"));
        assertNull(ingress.getValue("error"));
        assertEquals(1_500_000L, ingress.getLong("wallNanos"), "GATE check 6: wall time per step, in nanos");
        assertEquals(20_000L, ingress.getLong("cpuNanos"), "GATE check 6: CPU time per step, in nanos");
        assertEquals(new JsonObject().put("content-type", "application/xml"), ingress.getJsonObject("headers"));
        assertEquals(new JsonObject(), ingress.getJsonObject("keys"));
        JsonObject inBody = ingress.getJsonObject("body");
        assertEquals(in.hash().algorithm() + ":" + in.hash().hex(), inBody.getString("hash"), "algorithm:hex");
        assertEquals(xml.length(), inBody.getLong("size"));
        assertEquals(true, inBody.getBoolean("kept"));
        JsonObject map = steps.getJsonObject(2);
        assertEquals("map", map.getString("name"));
        assertEquals(5_000_000_123L, map.getLong("wallNanos"), "nanos beyond the int range are not truncated");
        assertEquals(4_000_000_007L, map.getLong("cpuNanos"));
        assertEquals(new JsonObject().put("invoiceId", "4711"), map.getJsonObject("keys"));
        assertEquals(big.length(), map.getJsonObject("body").getLong("size"));
        assertEquals(out.hash().algorithm() + ":" + out.hash().hex(), map.getJsonObject("body").getString("hash"));
        JsonObject failed = steps.getJsonObject(3);
        assertEquals("FAILED", failed.getString("outcome"));
        assertEquals("java.lang.IllegalStateException: 503 from partner", failed.getString("error"));
        assertEquals(7L, failed.getLong("wallNanos"));
        assertEquals(3L, failed.getLong("cpuNanos"));
    }

    @Test
    void unknownCpuIsNull() throws Exception {
        start();
        recordInvoice();
        JsonArray steps = admin("/admin/messages/m-1").json().getJsonArray("steps");
        JsonObject validate = steps.getJsonObject(1);
        assertTrue(validate.containsKey("cpuNanos"), "present, null: " + validate);
        assertNull(validate.getValue("cpuNanos"), "CPU_UNKNOWN (-1) is null, never -1: " + validate);
        assertEquals(2_000_000L, validate.getLong("wallNanos"), "the wall time is still there");
        assertEquals(20_000L, steps.getJsonObject(0).getLong("cpuNanos"), "a measured CPU time stays a number");
        JsonObject body = validate.getJsonObject("body");
        assertEquals(false, body.getBoolean("kept"), "metadata only: hash and size, no bytes kept");
        assertEquals(xml.length(), body.getLong("size"));
    }

    @Test
    void unknownMessageIs404() throws Exception {
        start();
        recordInvoice();
        assertJsonError(404, admin("/admin/messages/nobody"), "unknown message");
        assertEquals(200, admin("/admin/messages/m-1").status(), "a known one is there");
    }

    // --- GET /admin/messages/{id}/steps/{n}/body

    @Test
    void stepBodyStreamsThePayloadAfterThatStep() throws Exception {
        start();
        recordInvoice();
        Answer ingress = admin("/admin/messages/m-1/steps/0/body");
        assertEquals(200, ingress.status());
        assertEquals(xml, ingress.body());
        assertEquals("application/xml", ingress.contentType(), "the content-type the snapshot recorded");
        Answer map = admin("/admin/messages/m-1/steps/2/body");
        assertEquals(200, map.status());
        assertEquals(big.length(), map.body().length(), "every byte of a 300 kB body");
        assertEquals(big, map.body(), "the payload as it was after step 2, not the ingress one");
        assertEquals("application/json", map.contentType());
        assertEquals(String.valueOf(big.length()), map.contentLength(), "content-length is the recorded size");
        assertEquals(String.valueOf(xml.length()), ingress.contentLength());
        Answer partner = admin("/admin/messages/m-1/steps/3/body");
        assertEquals(200, partner.status());
        assertEquals(big, partner.body(), "step 3 shares step 2's body by reference");
        assertEquals("application/octet-stream", partner.contentType(), "no content-type in the snapshot: octet-stream");
    }

    @Test
    void aBlobStreamFailingAtTheFirstReadIs500() throws Exception {
        start();
        recordInvoice();
        blobs.failAfter.put(BlobKey.body("m-1", 2), Buffer.buffer());
        assertJsonError(500, admin("/admin/messages/m-1/steps/2/body"), "nothing was read: the head is not out yet, so a 500 {error}");
        assertEquals(200, admin("/admin/messages/m-1/steps/0/body").status(), "another body still streams");
    }

    @Test
    void aBlobStreamFailingMidBodyCutsTheResponse() throws Exception {
        start();
        recordInvoice();
        blobs.failAfter.put(BlobKey.body("m-1", 2), big.slice(0, 65_536));
        AtomicInteger head = new AtomicInteger();
        Future<Buffer> body = client.request(new RequestOptions().setMethod(HttpMethod.GET).setAbsoluteURI("http://127.0.0.1:" + port + "/admin/messages/m-1/steps/2/body"))
                .compose(req -> req.putHeader("Authorization", "Bearer " + TOKEN).send())
                .compose(res -> {
                    head.set(res.statusCode());
                    return res.body();
                });
        Throwable cut = assertThrows(Throwable.class, () -> body.await(10, TimeUnit.SECONDS), "a body that broke after the head is never a complete answer");
        assertFalse(cut instanceof TimeoutException, "the connection is reset or closed, the client does not hang: " + cut);
        assertTrue(head.get() == 0 || head.get() == 200, "if the head arrived, it was the 200 sent before the failure: " + head.get());
        assertEquals(200, get("/health", null).status(), "the server goes on");
    }

    @Test
    void aBodyWhoseBytesAreNoLongerStoredIs404() throws Exception {
        start();
        recordInvoice();
        Body gone = blobs.write(BlobKey.body("m-gone", 0), xml, MAX).await();
        messages.record(new Snapshot("m-gone", "invoice-sync", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), T0, 1L, 1L, Headers.empty(), Props.empty(), Keys.empty(),
                gone.hash(), gone.size(), Optional.of(BlobKey.body("m-gone", 1)), Map.of())).await();   // a key the blob store does not hold (retention took it)
        Answer missing = admin("/admin/messages/m-gone/steps/0/body");
        assertJsonError(404, missing, "the snapshot names a key whose bytes are gone");
        assertTrue(missing.error().contains("no longer stored"), "says the bytes are no longer stored: " + missing.error());
        Answer metadataOnly = admin("/admin/messages/m-1/steps/1/body");
        assertFalse(metadataOnly.error().contains("no longer stored"), "metadata-only is another reason: " + metadataOnly.error());
        assertNotEquals(metadataOnly.error(), missing.error());
    }

    @Test
    void aContentTypeVertxRefusesIs500() throws Exception {
        start();
        Body b = blobs.write(BlobKey.body("m-crlf", 0), xml, MAX).await();
        messages.record(new Snapshot("m-crlf", "invoice-sync", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), T0, 1L, 1L,
                Headers.empty().with("content-type", "application/xml\r\nx-injected: yes"), Props.empty(), Keys.empty(), b.hash(), b.size(), Optional.of(b.key()), Map.of())).await();
        Answer a = admin("/admin/messages/m-crlf/steps/0/body");
        assertJsonError(500, a, "a recorded content-type no response may carry: a 500, not a hang");
        assertEquals(200, get("/health", null).status(), "the server goes on");
    }

    @Test
    void aNonNumericStepIs400() throws Exception {
        start();
        recordInvoice();
        for (String step : List.of("x", "1.5", "99999999999")) {
            assertJsonError(400, admin("/admin/messages/m-1/steps/" + step + "/body"), "step " + step + " is not a step number");
        }
        assertJsonError(404, admin("/admin/messages/m-1/steps/-1/body"), "-1 is a number: no step -1 recorded, the unknown-step rule");
        assertEquals(200, admin("/admin/messages/m-1/steps/0/body").status(), "a step number is a search");
    }

    @Test
    void metadataOnlyBodyIs404WithTheReason() throws Exception {
        start();
        recordInvoice();
        Answer metadataOnly = admin("/admin/messages/m-1/steps/1/body");
        assertJsonError(404, metadataOnly, "a metadata-only step");
        assertTrue(metadataOnly.error().contains("metadata"), "the reason names metadata-only: " + metadataOnly.error());
        Answer unknownStep = admin("/admin/messages/m-1/steps/9/body");
        assertJsonError(404, unknownStep, "a step the message never reached");
        assertFalse(unknownStep.error().contains("metadata"), "unknown is not metadata-only: " + unknownStep.error());
        Answer unknownMessage = admin("/admin/messages/nobody/steps/0/body");
        assertJsonError(404, unknownMessage, "an unknown message");
        assertFalse(unknownMessage.error().contains("metadata"), unknownMessage.error());
        assertNotEquals(metadataOnly.error(), unknownStep.error());
    }

    // --- GET /admin/messages?key=name:value

    @Test
    void searchByKeyFindsInvoice4711() throws Exception {
        start();
        recordInvoice();
        recordKeyed("m-2", Keys.empty().with("invoiceId", "4712"));
        recordKeyed("m-3", Keys.empty().with("invoiceId", "4711"));
        Answer a = admin("/admin/messages?key=invoiceId:4711");
        assertEquals(200, a.status(), a.body().toString());
        assertTrue(a.contentType().startsWith("application/json"), a.contentType());
        JsonObject page = a.json();
        assertEquals(Set.of("items", "next"), page.fieldNames(), "{items, next|null}");
        assertEquals(new JsonArray().add("m-1").add("m-3"), page.getJsonArray("items"), "GATE check 6: found by business key, each once");
        assertNull(page.getValue("next"), "everything fit: next is null");
    }

    @Test
    void searchByKeyPagesAtFiftyAndFollowsNext() throws Exception {
        start();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String id = String.format("k-%03d", i);
            recordKeyed(id, Keys.empty().with("orderNo", "o-1"));
            all.add(id);
        }
        JsonObject first = admin("/admin/messages?key=orderNo:o-1").json();
        assertEquals(50, first.getJsonArray("items").size(), "no limit: 50");
        String next = first.getString("next");
        assertNotNull(next);
        JsonObject second = admin("/admin/messages?key=orderNo:o-1&after=" + enc(next)).json();
        List<Object> walked = new ArrayList<>(first.getJsonArray("items").getList());
        walked.addAll(second.getJsonArray("items").getList());
        assertEquals(all, walked, "the second page continues where the first stopped");
        assertNull(second.getValue("next"));
        assertEquals(7, admin("/admin/messages?key=orderNo:o-1&limit=7").json().getJsonArray("items").size());
    }

    @Test
    void aKeyWithoutColonIs400() throws Exception {
        start();
        recordInvoice();
        assertJsonError(400, admin("/admin/messages?key=invoiceId"), "no colon");
        assertJsonError(400, admin("/admin/messages?key="), "empty key");
        assertEquals(200, admin("/admin/messages?key=invoiceId:4711").status(), "with the colon it is a search");
    }

    @Test
    void aKeyValueContainingAColonSplitsAtTheFirst() throws Exception {
        start();
        recordKeyed("m-a", Keys.empty().with("ref", "a:b:c"));
        recordKeyed("m-b", Keys.empty().with("ref:a:b", "c"));
        recordKeyed("m-c", Keys.empty().with("ref:a", "b:c"));
        JsonObject page = admin("/admin/messages?key=" + enc("ref:a:b:c")).json();
        assertEquals(new JsonArray().add("m-a"), page.getJsonArray("items"), "name ref, value a:b:c — not ref:a:b = c nor ref:a = b:c");
    }

    // --- GET /admin/dlq

    @Test
    void dlqWithoutLimitReturnsAtMost50AndFollowsNext() throws Exception {
        start();
        List<String> oldestFirst = new ArrayList<>();
        for (int i = 0; i < 120; i++) oldestFirst.add(String.format("d-%03d", i));
        for (int k = 0; k < 120; k++) {
            int i = k * 37 % 120;   // parked out of order
            dlq.park(parked(oldestFirst.get(i), T0.plusSeconds(i))).await();
        }
        List<String> walked = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            Answer a = admin("/admin/dlq" + (after == null ? "" : "?after=" + enc(after)));
            assertEquals(200, a.status(), a.body().toString());
            JsonObject page = a.json();
            assertEquals(Set.of("items", "next"), page.fieldNames(), "{items, next|null}");
            JsonArray items = page.getJsonArray("items");
            assertEquals(pages < 2 ? 50 : 20, items.size(), "page " + pages + ": at most 50 without a limit");
            for (int i = 0; i < items.size(); i++) walked.add(items.getJsonObject(i).getString("id"));
            after = page.getString("next");
            pages++;
        } while (after != null && pages < 10);
        assertEquals(3, pages, "50 + 50 + 20, next null on the last");
        assertEquals(oldestFirst, walked, "oldest first, without gaps or repeats");
        JsonObject seven = admin("/admin/dlq?limit=7").json();
        assertEquals(7, seven.getJsonArray("items").size());
        assertNotNull(seven.getString("next"));
    }

    @Test
    void aLimitAbove500Is400() throws Exception {
        start();
        recordInvoice();
        dlq.park(parked("d-1", T0)).await();
        for (String limit : List.of("501", "0", "-3", "abc")) {
            assertJsonError(400, admin("/admin/dlq?limit=" + limit), "dlq limit " + limit);
            assertJsonError(400, admin("/admin/messages?key=invoiceId:4711&limit=" + limit), "key search limit " + limit);
        }
        assertEquals(200, admin("/admin/dlq?limit=500").status(), "500 is the largest page");
        assertEquals(200, admin("/admin/messages?key=invoiceId:4711&limit=500").status());
        assertEquals(200, admin("/admin/dlq?limit=1").status(), "1 is the smallest");
    }

    @Test
    void aGarbledAfterIs400() throws Exception {
        start();
        recordInvoice();
        dlq.park(parked("d-1", T0)).await();
        assertJsonError(400, admin("/admin/dlq?after=" + enc("!!!")), "garbled dlq cursor");
        assertJsonError(400, admin("/admin/messages?key=invoiceId:4711&after=" + enc("!!!")), "garbled key cursor");
    }

    // --- auth, bind, health

    @Test
    void adminWithoutOrWithWrongTokenIs401HealthIsOpen() throws Exception {
        start();
        recordInvoice();
        dlq.park(parked("d-1", T0)).await();
        List<String> paths = List.of("/admin/messages/m-1", "/admin/messages/m-1/steps/0/body", "/admin/messages?key=invoiceId:4711", "/admin/dlq");
        List<String> wrong = new ArrayList<>(List.of("Bearer wrong", "Bearer " + TOKEN.substring(0, TOKEN.length() - 1), "Bearer " + TOKEN + "x",
                "Basic " + TOKEN, TOKEN, "Bearer "));
        wrong.add(null);
        for (String path : paths) {
            for (String auth : wrong) {
                Answer a = get(path, auth);
                assertJsonError(401, a, path + " with Authorization " + auth);
                assertEquals(Set.of("error"), a.json().fieldNames(), "nothing but the error: " + a.body());
            }
            assertEquals(200, admin(path).status(), path + " with the token");
        }
        assertEquals(200, get("/health", null).status(), "/health needs no token");
    }

    @Test
    void theBearerSchemeIsCaseInsensitive() throws Exception {
        start();
        recordInvoice();
        for (String scheme : List.of("bearer", "BEARER", "bEaReR")) {
            assertEquals(200, get("/admin/messages/m-1", scheme + " " + TOKEN).status(), "RFC 7235: the auth-scheme is case-insensitive (" + scheme + ")");
            assertJsonError(401, get("/admin/messages/m-1", scheme + " wrong"), "a wrong token stays wrong (" + scheme + ")");
        }
    }

    @Test
    void nonLoopbackBindWithoutTokenRefusesToStart() throws Exception {
        for (Optional<String> none : List.of(Optional.<String>empty(), Optional.of(""))) {
            Throwable refused = assertThrows(IllegalArgumentException.class, () -> new AdminServer.Config("0.0.0.0", 0, none),
                    "0.0.0.0 without a token (" + none + "): the Config refuses it");
            assertTrue(String.valueOf(refused.getMessage()).toLowerCase().contains("token"), "the refusal names the token: " + refused);
        }
        start("0.0.0.0", Optional.of(TOKEN));
        assertEquals(200, get("/health", null).status(), "a non-loopback bind with a token starts");
        start("127.0.0.1", Optional.empty());
        recordInvoice();
        assertEquals(200, get("/health", null).status(), "a loopback bind needs no token");
        assertEquals(200, get("/admin/messages/m-1", null).status(), "loopback without a token configured: /admin is open (the dev default)");
    }

    @Test
    void aHostNameIsNotLoopbackEvenLocalhost() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> new AdminServer.Config("localhost", 0, Optional.empty()),
                "only an IP literal is known to be loopback; where a name resolves is not known at configuration");
        assertTrue(refused.getMessage().contains("token"), refused.getMessage());
        assertEquals("localhost", new AdminServer.Config("localhost", 0, Optional.of(TOKEN)).host(), "with a token any host binds");
        assertEquals("::1", new AdminServer.Config("::1", 0, Optional.empty()).host(), "an IPv6 loopback literal needs no token");
        assertEquals("127.0.0.1", new AdminServer.Config("127.0.0.1", 0, Optional.empty()).host());
    }

    @Test
    void stopBeforeStartAndASecondStopSucceed() throws Exception {
        AdminServer server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), messages, dlq, blobs, probe());
        server.stop().await(10, TimeUnit.SECONDS);   // nothing started: nothing to do, no failure
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
        assertEquals(200, get("/health", null).status(), "started after a stop before start");
        server.stop().await(10, TimeUnit.SECONDS);
        assertThrows(Exception.class, () -> get("/health", null), "the first stop closed the socket");
        server.stop().await(10, TimeUnit.SECONDS);   // a second stop has nothing to do
    }

    @Test
    void propsKeepTheirJsonTypesInTheTimeline() throws Exception {
        start();
        Body b = blobs.write(BlobKey.body("m-p", 0), xml, MAX).await();
        Props props = Props.empty().with(new Key<Integer>("n"), 7).with(new Key<Long>("big"), 5_000_000_000L).with(new Key<Double>("ratio"), 0.5)
                .with(new Key<Boolean>("flag"), true).with(new Key<String>("PARTNER"), "domestic").with(new Key<java.time.Duration>("deadline"), java.time.Duration.ofSeconds(3));
        messages.record(new Snapshot("m-p", "invoice-sync", 0, "ingress", Snapshot.Outcome.OK, Optional.empty(), T0, 1L, 1L, Headers.empty(), props, Keys.empty(),
                b.hash(), b.size(), Optional.of(b.key()), Map.of())).await();
        Answer a = admin("/admin/messages/m-p");
        assertEquals(200, a.status(), a.body().toString());
        JsonObject p = a.json().getJsonArray("steps").getJsonObject(0).getJsonObject("props");
        assertEquals(new JsonObject().put("n", 7).put("big", 5_000_000_000L).put("ratio", 0.5).put("flag", true).put("PARTNER", "domestic").put("deadline", "PT3S"), p,
                "numbers and booleans stay JSON numbers and booleans, anything else is its string form: " + p);
        assertTrue(p.getValue("n") instanceof Number && p.getValue("big") instanceof Number && p.getValue("ratio") instanceof Number, "numbers, not strings: " + p);
        assertEquals(Boolean.TRUE, p.getValue("flag"), "a boolean, not \"true\": " + p);
    }

    @Test
    void dlqItemIsTheParkedRowByReference() throws Exception {
        start();
        Parked row = new Parked("d-1", "invoice-sync", 3, "partner", "java.lang.IllegalStateException: 503", T0, Headers.empty().with("content-type", "application/xml"),
                Props.empty().with(new Key<Integer>("n"), 7).with(new Key<Boolean>("flag"), true).with(new Key<java.time.Duration>("odd"), java.time.Duration.ofSeconds(2)),
                Keys.empty().with("invoiceId", "4711"), new Hash("sha256-64", 9), 187, BlobKey.body("d-1", 2), Map.of("flow", "invoice-sync@0.1.0"));
        dlq.park(row).await();
        JsonObject item = admin("/admin/dlq").json().getJsonArray("items").getJsonObject(0);
        assertEquals(Set.of("id", "flow", "step", "name", "error", "parkedAt", "headers", "props", "keys", "body", "versions"), item.fieldNames());
        assertEquals("d-1", item.getString("id"));
        assertEquals("invoice-sync", item.getString("flow"));
        assertEquals(3, item.getInteger("step"));
        assertEquals("partner", item.getString("name"));
        assertEquals("java.lang.IllegalStateException: 503", item.getString("error"));
        assertEquals(T0, Instant.parse(item.getString("parkedAt")));
        assertEquals(new JsonObject().put("content-type", "application/xml"), item.getJsonObject("headers"));
        assertEquals(new JsonObject().put("n", 7).put("flag", true).put("odd", "PT2S"), item.getJsonObject("props"));
        assertEquals(new JsonObject().put("invoiceId", "4711"), item.getJsonObject("keys"));
        assertEquals(new JsonObject().put("hash", "sha256-64:" + new Hash("sha256-64", 9).hex()).put("size", 187), item.getJsonObject("body"), "by reference: hash and size, never bytes");
        assertEquals(new JsonObject().put("flow", "invoice-sync@0.1.0"), item.getJsonObject("versions"));
    }

    @Test
    void healthIsDownWithPostgresDown() throws Exception {
        start();
        writeFailures.set(3);
        postgres.set(Future.succeededFuture(Health.down("connection refused: 10.9.8.7:5432 password=hunter2")));
        Answer down = get("/health", null);
        assertEquals(503, down.status(), down.body().toString());
        assertTrue(down.contentType().startsWith("application/json"), down.contentType());
        JsonObject d = down.json();
        assertEquals(Set.of("status", "checks", "snapshotWriteFailures"), d.fieldNames());
        assertEquals("DOWN", d.getString("status"));
        assertEquals(new JsonObject().put("postgres", "DOWN"), d.getJsonObject("checks"));
        assertEquals(3L, d.getLong("snapshotWriteFailures"));
        assertFalse(down.body().toString().contains("hunter2") || down.body().toString().contains("10.9.8.7"), "status only, no detail: " + down.body());

        postgres.set(Future.failedFuture(new IllegalStateException("pool closed")));
        Answer failing = get("/health", null);
        assertEquals(503, failing.status(), "a check that fails is DOWN: " + failing.body());
        assertEquals(new JsonObject().put("postgres", "DOWN"), failing.json().getJsonObject("checks"));

        postgres.set(Future.succeededFuture(Health.up()));
        writeFailures.set(7);
        Answer up = get("/health", null);
        assertEquals(200, up.status(), up.body().toString());
        JsonObject u = up.json();
        assertEquals("UP", u.getString("status"));
        assertEquals(new JsonObject().put("postgres", "UP"), u.getJsonObject("checks"));
        assertEquals(7L, u.getLong("snapshotWriteFailures"), "UP, and the failed snapshot writes are counted (read per request)");
    }

    // --- threads and errors

    @Test
    void noAdminHandlerRunsOnAnEventLoop() throws Exception {
        start();
        recordInvoice();
        dlq.park(parked("d-1", T0)).await();
        calls.clear();
        assertEquals(200, admin("/admin/messages/m-1").status());
        assertEquals(200, admin("/admin/messages/m-1/steps/0/body").status());
        assertFalse(blobs.attachedOnEventLoop.isEmpty(), "the body route attached to the blob stream");
        assertTrue(blobs.attachedOnEventLoop.stream().allMatch(onLoop -> onLoop), "the answer is written on the verticle's context: the pipe attaches on its event loop " + blobs.attachedOnEventLoop);
        assertEquals(200, admin("/admin/messages?key=invoiceId:4711").status());
        assertEquals(200, admin("/admin/dlq").status());
        assertEquals(200, get("/health", null).status());
        Set<String> seen = new java.util.TreeSet<>();
        for (Call c : calls) seen.add(c.what());
        assertTrue(seen.containsAll(Set.of("timeline", "blob", "findByKey", "list", "health")), "every route reached its store: " + calls);
        for (Call c : calls) {
            assertTrue(c.virtual() && !c.eventLoop(), "a route body runs on a virtual thread, never on the event loop: " + c);
        }

        // a route body that blocks does not hold the socket: /health answers meanwhile
        messages.hold();
        Future<Answer> held = send("/admin/messages/m-1", "Bearer " + TOKEN);
        assertTrue(messages.reached.await(10, TimeUnit.SECONDS), "the timeline call was reached");
        try {
            assertEquals(200, get("/health", null).status(), "answered while another route body blocks");
        } finally {
            messages.release.countDown();
        }
        assertEquals(200, held.await(10, TimeUnit.SECONDS).status(), "the held request completes once released");
    }

    @Test
    void everyErrorBodyIsJsonWithError() throws Exception {
        start();
        recordInvoice();
        messages.failTimeline.set("m-boom");
        assertJsonError(401, get("/admin/messages/m-1", null), "no token");
        assertJsonError(404, admin("/admin/messages/nobody"), "unknown message");
        assertJsonError(404, admin("/admin/messages/m-1/steps/1/body"), "metadata-only body");
        assertJsonError(404, admin("/admin/nope"), "unknown admin route");
        assertJsonError(404, get("/nope", null), "unknown route");
        assertJsonError(400, admin("/admin/messages?key=nocolon"), "bad key");
        assertJsonError(400, admin("/admin/dlq?limit=501"), "limit");
        assertJsonError(400, admin("/admin/dlq?after=" + enc("!!!")), "cursor");
        assertJsonError(500, admin("/admin/messages/m-boom"), "a store failure");
        assertJsonError(405, send(HttpMethod.POST, "/admin/dlq", "Bearer " + TOKEN).await(10, TimeUnit.SECONDS), "a method the route does not take (the router's own error)");
    }

    // --- store-boundary fakes: delegate to the in-memory stores, record the calling thread

    record Call(String what, String thread, boolean virtual, boolean eventLoop) {}

    void saw(String what) {
        Thread t = Thread.currentThread();
        calls.add(new Call(what, t.getName(), t.isVirtual(), Context.isOnEventLoopThread()));
    }

    final class RecordingStore implements MessageStore {
        final InMemoryMessageStore inner = new InMemoryMessageStore();
        final AtomicReference<String> failTimeline = new AtomicReference<>();
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean holding;

        void hold() { holding = true; }

        @Override public Future<Void> record(Snapshot snapshot) { return inner.record(snapshot); }

        @Override public Future<List<Snapshot>> timeline(String messageId) {
            saw("timeline");
            if (messageId.equals(failTimeline.get())) return Future.failedFuture(new IllegalStateException("store down"));
            if (holding) {
                holding = false;
                reached.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) fail("never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Future.failedFuture(e);
                }
            }
            return inner.timeline(messageId);
        }

        @Override public Future<Page<String>> findByKey(String name, String value, Optional<String> after, int limit) {
            saw("findByKey");
            return inner.findByKey(name, value, after, limit);
        }
    }

    final class RecordingDlq implements Dlq {
        final InMemoryDlq inner = new InMemoryDlq();

        @Override public Future<Void> park(Parked parked) { return inner.park(parked); }

        @Override public Future<Page<Parked>> list(Optional<String> after, int limit) {
            saw("list");
            return inner.list(after, limit);
        }

        @Override public Future<Optional<Parked>> get(String messageId) {
            saw("get");
            return inner.get(messageId);
        }

        @Override public Future<Void> remove(String messageId) { return inner.remove(messageId); }
    }

    final class RecordingBlobs implements BlobStore {
        final InMemoryBlobStore inner = new InMemoryBlobStore(Hasher.sha256(), MAX);

        @Override public long treeLimit() { return inner.treeLimit(); }

        @Override public Future<Body> write(BlobKey key, Buffer whole, long maxSize) { return inner.write(key, whole, maxSize); }

        @Override public Future<Body> write(BlobKey key, ReadStream<Buffer> stream, long maxSize) { return inner.write(key, stream, maxSize); }

        /** Where a pipe attached its data handler to an opened stream: on an event loop, or not. */
        final List<Boolean> attachedOnEventLoop = new CopyOnWriteArrayList<>();
        /** Keys whose stream fails: the bytes delivered first (empty: the first read fails), then an IOException. */
        final Map<BlobKey, Buffer> failAfter = new ConcurrentHashMap<>();

        @Override public ReadStream<Buffer> open(BlobKey key) {
            saw("blob");
            Buffer before = failAfter.get(key);
            return new AttachWatch(before != null ? new BrokenStream(before) : inner.open(key));
        }

        @Override public Future<Buffer> read(BlobKey key) {
            saw("blob");
            return inner.read(key);
        }
    }

    /** Delegates; records whether the data handler was attached on an event loop (the answer is written on the verticle's context). */
    final class AttachWatch implements ReadStream<Buffer> {
        final ReadStream<Buffer> inner;

        AttachWatch(ReadStream<Buffer> inner) { this.inner = inner; }

        @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { inner.exceptionHandler(h); return this; }
        @Override public ReadStream<Buffer> handler(Handler<Buffer> h) {
            if (h != null) blobs.attachedOnEventLoop.add(Context.isOnEventLoopThread());
            inner.handler(h);
            return this;
        }
        @Override public ReadStream<Buffer> pause() { inner.pause(); return this; }
        @Override public ReadStream<Buffer> resume() { inner.resume(); return this; }
        @Override public ReadStream<Buffer> fetch(long amount) { inner.fetch(amount); return this; }
        @Override public ReadStream<Buffer> endHandler(Handler<Void> h) { inner.endHandler(h); return this; }
    }

    /**
     * A store-boundary stream that breaks: once read (a data handler on a flowing stream, a resume or a fetch) it delivers
     * {@code before} and then fails with an IOException, both on the reader's context. Never ends.
     */
    static final class BrokenStream implements ReadStream<Buffer> {
        final Buffer before;
        volatile Handler<Buffer> data;
        volatile Handler<Throwable> failure;
        volatile boolean paused;
        boolean started;

        BrokenStream(Buffer before) { this.before = before; }

        @Override public ReadStream<Buffer> exceptionHandler(Handler<Throwable> h) { failure = h; return this; }
        @Override public ReadStream<Buffer> handler(Handler<Buffer> h) { data = h; if (!paused) read(); return this; }
        @Override public ReadStream<Buffer> pause() { paused = true; return this; }
        @Override public ReadStream<Buffer> resume() { paused = false; read(); return this; }
        @Override public ReadStream<Buffer> fetch(long amount) { if (amount > 0) read(); return this; }
        @Override public ReadStream<Buffer> endHandler(Handler<Void> h) { return this; }

        private synchronized void read() {
            if (started || data == null) return;
            started = true;
            Context reader = Vertx.currentContext() != null ? Vertx.currentContext() : vertx.getOrCreateContext();
            reader.runOnContext(v -> {
                Handler<Buffer> d = data;
                if (before.length() > 0 && d != null) d.handle(before);
                Handler<Throwable> f = failure;
                if (f != null) f.handle(new java.io.IOException("the blob store could not read the object"));
            });
        }
    }
}
