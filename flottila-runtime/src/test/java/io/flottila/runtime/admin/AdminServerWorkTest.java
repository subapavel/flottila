package io.flottila.runtime.admin;

import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Page;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Context;
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
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c1 (D-067): the held work items over the admin API — {@code GET /admin/work/held?limit&after} pages them as
 * {@code {items:[{seq, messageId, flow, step, partitionKey, attempts, heldAt}], next|null}}, {@code POST /admin/work/{seq}/release}
 * answers 200 {@code {seq}}, 404 for a seq unknown or not held, 400 for a seq that is not a long; both need the token.
 *
 * <p>The shape these tests assume (P1-09c1 test-author; the packet leaves the constructor open, c2 adds the IdGenerator):
 * {@code AdminServer(Config, MessageStore, Dlq, BlobStore, HealthProbe, Optional<Deployments>, WorkQueue)}.
 */
class AdminServerWorkTest {

    static final String TOKEN = "s3cret-token";
    static final Instant T0 = Instant.parse("2026-01-01T00:00:00.123456Z");
    static final Duration LEASE = Duration.ofSeconds(30);
    static final String FLOW = "invoice-sync";

    static Vertx vertx;
    static HttpClientAgent client;

    final FakeRuntime rt = FakeRuntime.create();
    final FakeClock clock = FakeClock.at(T0);
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(clock);
    final RecordingQueue queue = new RecordingQueue(rows);
    final List<AdminServer> started = new ArrayList<>();
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

    @AfterEach
    void stop() throws Exception {
        for (AdminServer s : started) s.stop().await(10, TimeUnit.SECONDS);
    }

    // --- fixture

    void start() throws Exception {
        AdminServer server = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.of(TOKEN)), new InMemoryMessageStore(), new InMemoryDlq(),
                rt.blobs(), new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.empty(), queue);
        port = server.start(vertx).await(10, TimeUnit.SECONDS);
        started.add(server);
    }

    /** Claims {@code flow}'s claimable rows and holds those {@code hold} picks, each a second after the last; the instant of each hold. */
    Map<String, Instant> claimAndHold(String flow, java.util.function.Predicate<String> hold) {
        Map<String, Instant> at = new HashMap<>();
        for (WorkQueue.Item it : rows.claim("w1", flow, 1_000, LEASE).await()) {
            if (!hold.test(it.messageId())) continue;
            clock.advance(Duration.ofSeconds(1));
            assertTrue(rows.hold(it.seq(), it.attempts()).await(), "fixture: " + it.messageId() + " held");
            at.put(it.messageId(), clock.instant());
        }
        return at;
    }

    long seqOf(String id) {
        return rows.snapshot().stream().filter(i -> i.messageId().equals(id)).findFirst().orElseThrow().seq();
    }

    List<String> heldNow() {
        return rows.held(Optional.empty(), Page.MAX_LIMIT).await().items().stream().map(h -> h.item().messageId()).toList();
    }

    record Answer(int status, String contentType, Buffer body) {
        JsonObject json() { return new JsonObject(body); }
    }

    Answer send(HttpMethod method, String path, String authorization) throws Exception {
        return client.request(new RequestOptions().setMethod(method).setAbsoluteURI("http://127.0.0.1:" + port + path))
                .compose(req -> {
                    if (authorization != null) req.putHeader("Authorization", authorization);
                    return req.send();
                })
                .compose(res -> res.body().map(b -> new Answer(res.statusCode(), res.getHeader("content-type"), b)))
                .await(10, TimeUnit.SECONDS);
    }

    Answer get(String path) throws Exception {
        return send(HttpMethod.GET, path, "Bearer " + TOKEN);
    }

    Answer post(String path) throws Exception {
        return send(HttpMethod.POST, path, "Bearer " + TOKEN);
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static void assertJsonError(int status, Answer a, String what) {
        assertEquals(status, a.status(), what + ": " + a.body());
        assertNotNull(a.contentType(), what + ": a content-type");
        assertTrue(a.contentType().startsWith("application/json"), what + ": " + a.contentType());
        String error = a.json().getString("error");
        assertTrue(error != null && !error.isBlank(), what + ": {error} names the failure: " + a.body());
    }

    void assertOnVirtualThreads(String what) {
        assertTrue(queue.calls.stream().anyMatch(c -> c.what().equals(what)), what + " reached the queue: " + queue.calls);
        for (Call c : queue.calls) assertTrue(c.virtual() && !c.eventLoop(), "a route body runs on a virtual thread, never on the event loop: " + c);
    }

    // --- GET /admin/work/held

    /**
     * 70 rows claimed, 60 of them held a second apart (every 7th only leased), one more held at its second attempt in another flow:
     * without a limit the pages are 50 then 11, in seq order, only held rows, next null on the last; each item carries exactly the
     * seven fields with heldAt the hold's instant (microseconds kept) and partitionKey null when the row has none; limit=7 pages by 7;
     * a limit outside 1..500 or not a number, or a garbled after, is 400.
     */
    @Test
    void heldPagesWithHeldAt() throws Exception {
        start();
        List<String> expectedOrder = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            String id = String.format("w-%03d", i);
            rows.enqueue(id, FLOW, 1 + i % 3, i % 2 == 0 ? null : "K" + i).await();
            if (i % 7 != 0) expectedOrder.add(id);
        }
        rows.enqueue("r-1", "orders", 4, "PO-9").await();
        expectedOrder.add("r-1");
        Map<String, Instant> heldAt = claimAndHold(FLOW, id -> Integer.parseInt(id.substring(2)) % 7 != 0);
        WorkQueue.Item r1 = rows.claim("w2", "orders", 1, Duration.ofSeconds(1)).await().get(0);
        clock.advance(Duration.ofSeconds(1));
        WorkQueue.Item r1Again = rows.claim("w2", "orders", 1, LEASE).await().get(0);
        assertEquals(r1.seq(), r1Again.seq(), "fixture");
        clock.advance(Duration.ofMillis(1));
        assertTrue(rows.hold(r1Again.seq(), r1Again.attempts()).await(), "fixture");
        heldAt.put("r-1", clock.instant());

        List<String> walked = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        Map<String, JsonObject> byId = new HashMap<>();
        String after = null;
        do {
            Answer a = get("/admin/work/held" + (after == null ? "" : "?after=" + enc(after)));
            assertEquals(200, a.status(), a.body().toString());
            assertTrue(a.contentType().startsWith("application/json"), a.contentType());
            JsonObject page = a.json();
            assertEquals(Set.of("items", "next"), page.fieldNames(), "{items, next|null}");
            JsonArray items = page.getJsonArray("items");
            sizes.add(items.size());
            for (int i = 0; i < items.size(); i++) {
                JsonObject item = items.getJsonObject(i);
                walked.add(item.getString("messageId"));
                byId.put(item.getString("messageId"), item);
            }
            after = page.getString("next");
        } while (after != null && sizes.size() < 10);
        assertEquals(List.of(50, 11), sizes, "at most 50 without a limit; next null on the last page");
        assertEquals(expectedOrder, walked, "only held rows, every flow's, in seq order, without gaps or repeats");

        Set<String> fields = Set.of("seq", "messageId", "flow", "step", "partitionKey", "attempts", "heldAt");
        JsonObject keyless = byId.get("w-002");
        assertEquals(fields, keyless.fieldNames(), "the seven fields, partitionKey present when null: " + keyless);
        assertEquals(seqOf("w-002"), keyless.getLong("seq"));
        assertEquals(FLOW, keyless.getString("flow"));
        assertEquals(3, keyless.getInteger("step"));
        assertNull(keyless.getValue("partitionKey"), "no key: null");
        assertEquals(1, keyless.getInteger("attempts"));
        assertEquals(heldAt.get("w-002"), Instant.parse(keyless.getString("heldAt")), "heldAt as ISO-8601, the hold's instant");
        JsonObject retried = byId.get("r-1");
        assertEquals(fields, retried.fieldNames());
        assertEquals(new JsonObject().put("seq", r1.seq()).put("messageId", "r-1").put("flow", "orders").put("step", 4).put("partitionKey", "PO-9")
                .put("attempts", 2).put("heldAt", retried.getString("heldAt")), retried, "as last handed out: the second attempt");
        assertEquals(heldAt.get("r-1"), Instant.parse(retried.getString("heldAt")), "microseconds and the millisecond after the claim kept");
        for (String id : expectedOrder) {
            assertEquals(heldAt.get(id), Instant.parse(byId.get(id).getString("heldAt")), id + ": each row its own hold instant");
        }

        JsonObject seven = get("/admin/work/held?limit=7").json();
        assertEquals(expectedOrder.subList(0, 7), seven.getJsonArray("items").stream().map(o -> ((JsonObject) o).getString("messageId")).toList());
        String next = seven.getString("next");
        assertNotNull(next, "more held rows");
        JsonObject following = get("/admin/work/held?limit=7&after=" + enc(next)).json();
        assertEquals(expectedOrder.subList(7, 14), following.getJsonArray("items").stream().map(o -> ((JsonObject) o).getString("messageId")).toList(),
                "the next page starts after the cursor");
        assertEquals(61, get("/admin/work/held?limit=500").json().getJsonArray("items").size(), "500 is a limit: all 61 held rows on one page");

        for (String limit : List.of("501", "0", "-3", "abc")) assertJsonError(400, get("/admin/work/held?limit=" + limit), "limit " + limit);
        assertJsonError(400, get("/admin/work/held?after=" + enc("!!!")), "a garbled cursor");
        assertOnVirtualThreads("held");
    }

    // --- POST /admin/work/{seq}/release

    /**
     * A held row released over the API: 200 {@code {seq}} and nothing else, the row no longer listed, claimable at once (its lease
     * cleared) at its next attempt (attempts kept). Released again, 404; an unknown seq, a negative one and a leased row that is not
     * held, 404 — the leased row's lease untouched.
     */
    @Test
    void releaseIs200Then404() throws Exception {
        start();
        rows.enqueue("h-1", FLOW, 2, "K").await();
        rows.enqueue("h-2", FLOW, 2, "K").await();
        rows.enqueue("l-1", FLOW, 1, null).await();
        claimAndHold(FLOW, id -> id.equals("h-1"));
        long h1 = seqOf("h-1");
        long l1 = seqOf("l-1");
        assertEquals(List.of("h-1"), heldNow(), "fixture");

        Answer released = post("/admin/work/" + h1 + "/release");
        assertEquals(200, released.status(), released.body().toString());
        assertTrue(released.contentType().startsWith("application/json"), released.contentType());
        assertEquals(new JsonObject().put("seq", h1), released.json(), "{seq}");
        assertEquals(List.of(), heldNow(), "released: no longer held");
        assertEquals(List.of(new WorkQueue.Item(h1, "h-1", FLOW, 2, "K", 2)), rows.claim("w2", FLOW, 10, LEASE).await(),
                "claimable at once, before h-2 behind it, its attempts kept: the next hand-out is the second");

        assertJsonError(404, post("/admin/work/" + h1 + "/release"), "released already: not held");
        assertJsonError(404, post("/admin/work/999/release"), "an unknown seq");
        assertJsonError(404, post("/admin/work/-1/release"), "a negative seq is a long, unknown");
        assertJsonError(404, post("/admin/work/" + l1 + "/release"), "a leased row that is not held");
        assertEquals("w1", rows.leaseHolder(l1).orElseThrow(), "the refused release left its lease alone");
        assertEquals(List.of(h1, h1, 999L, -1L, l1), queue.releases, "every 404 is the queue's answer, asked with the path's seq");
        assertOnVirtualThreads("release");
    }

    @Test
    void aSeqNotANumberIs400() throws Exception {
        start();
        rows.enqueue("h-1", FLOW, 1, null).await();
        claimAndHold(FLOW, id -> true);
        long h1 = seqOf("h-1");
        for (String seq : List.of("abc", "1.5", "99999999999999999999", "0x1F", enc(" "))) {
            assertJsonError(400, post("/admin/work/" + seq + "/release"), "seq " + seq);
        }
        assertEquals(List.of(), queue.releases, "a seq that is not a long never reaches the queue");
        assertEquals(List.of("h-1"), heldNow(), "still held");
        assertEquals(200, post("/admin/work/" + h1 + "/release").status(), "witness: the route releases a seq that is a long");
    }

    // --- the token

    @Test
    void workRoutesNeedTheToken() throws Exception {
        start();
        rows.enqueue("h-1", FLOW, 1, null).await();
        claimAndHold(FLOW, id -> true);
        long h1 = seqOf("h-1");
        List<String> wrong = new ArrayList<>(List.of("Bearer wrong", "Bearer " + TOKEN + "x", "Basic " + TOKEN, TOKEN, "Bearer "));
        wrong.add(null);
        for (String auth : wrong) {
            Answer list = send(HttpMethod.GET, "/admin/work/held", auth);
            assertJsonError(401, list, "GET held with Authorization " + auth);
            assertEquals(Set.of("error"), list.json().fieldNames(), "nothing but the error: " + list.body());
            Answer release = send(HttpMethod.POST, "/admin/work/" + h1 + "/release", auth);
            assertJsonError(401, release, "POST release with Authorization " + auth);
            assertEquals(Set.of("error"), release.json().fieldNames(), "nothing but the error: " + release.body());
        }
        assertEquals(List.of(), queue.calls, "no request without the token reached the queue");
        assertEquals(List.of("h-1"), heldNow(), "still held");

        Answer list = get("/admin/work/held");
        assertEquals(200, list.status(), "with the token: " + list.body());
        assertEquals(1, list.json().getJsonArray("items").size());
        assertEquals(200, post("/admin/work/" + h1 + "/release").status(), "with the token");
        assertEquals(List.of(), heldNow());
    }

    // --- the store boundary: delegates to the in-memory queue, records the calling thread of the routes' calls

    record Call(String what, String thread, boolean virtual, boolean eventLoop) {}

    static final class RecordingQueue implements WorkQueue {
        final WorkQueue delegate;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<Long> releases = new CopyOnWriteArrayList<>();

        RecordingQueue(WorkQueue delegate) { this.delegate = delegate; }

        private void saw(String what) {
            Thread t = Thread.currentThread();
            calls.add(new Call(what, t.getName(), t.isVirtual(), Context.isOnEventLoopThread()));
        }

        @Override public Future<Page<Held>> held(Optional<String> after, int limit) {
            saw("held");
            return delegate.held(after, limit);
        }

        @Override public Future<Boolean> release(long seq) {
            saw("release");
            releases.add(seq);
            return delegate.release(seq);
        }

        @Override public Future<Void> enqueue(String messageId, String flow, int step, String partitionKey) { return delegate.enqueue(messageId, flow, step, partitionKey); }
        @Override public Future<List<Item>> claim(String workerId, String flow, int max, Duration lease) { return delegate.claim(workerId, flow, max, lease); }
        @Override public Future<Boolean> complete(long seq, int attempts) { return delegate.complete(seq, attempts); }
        @Override public Future<Completion> completeAndTakeNext(long seq, int attempts, String workerId, Duration lease) {
            return delegate.completeAndTakeNext(seq, attempts, workerId, lease);
        }
        @Override public Future<Boolean> renew(long seq, int attempts, Duration lease) { return delegate.renew(seq, attempts, lease); }
        @Override public Future<Boolean> hold(long seq, int attempts) { return delegate.hold(seq, attempts); }
        @Override public Future<Boolean> hasItems(String flow) { return delegate.hasItems(flow); }
    }
}
