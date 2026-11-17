package dev.rail8.runtime.admin;

import dev.rail8.core.dlq.FailedMessages;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.dlq.Parked;
import dev.rail8.core.flow.WorkSignals;
import dev.rail8.core.flow.Workers;
import dev.rail8.core.queue.InMemoryFailedMessages;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.queue.WorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.queue.ParkedRows;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /admin/dlq/{id}/cancel} and {@code GET /admin/work/stopped} over the in-memory queue and acts, on a server with no
 * deployments: a cancel needs no deployed flow, and without one there is no {@code /retry}. A message that stopped its key is
 * listed as stopped with the rows behind it and its failure, is not a held row and is not released; its cancel answers
 * {@code {id, auditId, cancelledAt}}, frees the key — its next row is claimed — and announces nothing when the act names no node.
 * The body is the reason: none, or {@code {"reason"}} alone with 1 to 1024 characters after stripping; any other body is 400 and one
 * above 8 KiB 413, both before the act. The actor is {@code X-Rail8-Actor} ({@code unknown} without one) and the remote the
 * client's address; an actor over 128 characters is 400. An unknown id is 404, one beside an unsettled work row 409.
 */
@Timeout(60)
class AdminServerCancelTest {

    static final Duration LEASE = Duration.ofSeconds(30);
    static Vertx vertx;
    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock(), dlq);
    final FailedMessages real = new InMemoryFailedMessages(rows);
    /** Every cancel the route asked for: {@code id actor remote reason}. */
    final List<String> asked = new CopyOnWriteArrayList<>();
    final List<String> announced = new CopyOnWriteArrayList<>();
    final WorkSignals signals = (target, flow) -> announced.add(target + " " + flow);
    /** What a cancel answers instead of the in-memory acts' answer; null: theirs. */
    volatile FailedMessages.Act answer;
    final FailedMessages acts = new FailedMessages() {
        @Override public Future<Act> retry(String messageId, String flow, String partitionKey, int attempt, Actor by) {
            return real.retry(messageId, flow, partitionKey, attempt, by);
        }
        @Override public Future<Act> cancel(String messageId, Actor by, Optional<String> reason) {
            asked.add(messageId + " " + by.name() + " " + by.remote() + " " + reason.orElse("-"));
            Act fixed = answer;
            return fixed == null ? real.cancel(messageId, by, reason) : Future.succeededFuture(fixed);
        }
        @Override public Future<Act> end(String messageId, Actor by, Optional<String> reason) {
            return real.end(messageId, by, reason);
        }
    };
    AdminServer admin;
    int port;

    @BeforeAll
    static void up() { vertx = Vertx.vertx(); }

    @AfterAll
    static void down() { vertx.close().await(); }

    @AfterEach
    void stop() throws Exception {
        if (admin != null) admin.stop().await(10, TimeUnit.SECONDS);
    }

    void start(Optional<String> token) throws Exception {
        admin = new AdminServer(new AdminServer.Config("127.0.0.1", 0, token), new InMemoryMessageStore(), dlq, rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.empty(), rows, acts, Optional.empty(), new Workers(), signals);
        port = admin.start(vertx).await(10, TimeUnit.SECONDS);
    }

    HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(req::header);
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> cancel(String id, String body) throws Exception {
        return send("POST", "/admin/dlq/" + id + "/cancel", body, Map.of());
    }

    /** {@code id} of flow t, the head of key K, stopped by its worker after attempt 1; its hand-out. */
    WorkQueue.Item stopped(String id) {
        WorkQueue.Item item = rows.enqueueLeased(id, "t", 1, "K", "n1", LEASE).await().orElseThrow();
        assertTrue(rows.stop(item.seq(), item.attempts(), ParkedRows.byTheWorker(id, "t", 1, Optional.of("K"))).await(), "fixture: " + id + " stopped");
        return item;
    }

    @Test
    void aStoppedKeyIsListedNotHeldAndACancelFreesIt() throws Exception {
        start(Optional.empty());
        WorkQueue.Item k1 = stopped("k1");
        rows.enqueue("k2", "t", 1, "K").await();

        HttpResponse<String> listed = send("GET", "/admin/work/stopped", null, Map.of());
        assertEquals(200, listed.statusCode(), listed.body());
        JsonObject item = new JsonObject(listed.body()).getJsonArray("items").getJsonObject(0);
        item.remove("stoppedAt");
        assertEquals(new JsonObject().put("seq", k1.seq()).put("messageId", "k1").put("flow", "t").put("step", 1).put("partitionKey", "K")
                .put("attempts", 1).put("blocks", 1).put("error", "partner down (test)"), item, "the stop, the row behind it and its failure");
        assertEquals(new JsonArray(), new JsonObject(send("GET", "/admin/work/held", null, Map.of()).body()).getJsonArray("items"), "not a held row");
        assertEquals(404, send("POST", "/admin/work/" + k1.seq() + "/release", null, Map.of()).statusCode(), "and not released");

        HttpResponse<String> cancelled = send("POST", "/admin/dlq/k1/cancel", "{\"reason\": \" sent by hand \"}", Map.of("X-Rail8-Actor", "ops-anna"));
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        JsonObject body = new JsonObject(cancelled.body());
        assertEquals("k1", body.getString("id"));
        assertEquals(1L, body.getLong("auditId"), "the in-memory acts count events from 1");
        assertEquals(rt.clock().instant(), Instant.parse(body.getString("cancelledAt")), "when it committed");
        assertEquals(List.of("k1 ops-anna 127.0.0.1 sent by hand"), asked, "the actor, the client's address and the reason, stripped");
        assertEquals(Optional.empty(), dlq.get("k1").await(), "its DLQ row is gone");
        assertEquals(new JsonArray(), new JsonObject(send("GET", "/admin/work/stopped", null, Map.of()).body()).getJsonArray("items"), "K is free");
        assertEquals(List.of("k2"), rows.claim("n2", "t", 10, LEASE).await().stream().map(WorkQueue.Item::messageId).toList(), "its next row runs");
        assertEquals(List.of(), announced, "the act named no node: nothing announced");
    }

    @Test
    void aCancelAnnouncesTheNodeTheActNames() throws Exception {
        start(Optional.empty());
        answer = new FailedMessages.Done(new FailedMessages.Event(7, Instant.parse("2026-11-17T10:00:00Z"), "k1", "t", "unknown",
                FailedMessages.Kind.CANCEL, OptionalInt.empty(), Optional.empty(), Optional.of("down")), Optional.of("n-b"));
        HttpResponse<String> res = cancel("k1", null);
        assertEquals(200, res.statusCode(), res.body());
        assertEquals(new JsonObject().put("id", "k1").put("auditId", 7).put("cancelledAt", "2026-11-17T10:00:00Z"), new JsonObject(res.body()));
        assertEquals(List.of("k1 unknown 127.0.0.1 -"), asked, "no actor: unknown; no body: no reason");
        assertEquals(List.of("n-b t"), announced, "the node of the key's next row, for the message's flow");
    }

    @Test
    void anyBodyButNoneOrAReasonIs400AndChangesNothing() throws Exception {
        start(Optional.empty());
        stopped("k1");
        Parked kept = dlq.get("k1").await().orElseThrow();
        List<String> bodies = List.of("nope", "null", "[]", "\"sent\"", "{}", "{\"reason\": null}", "{\"reason\": \"\"}", "{\"reason\": \"   \"}",
                "{\"reason\": 5}", "{\"reason\": \"x\", \"more\": 1}", "{\"reason\": \"" + "x".repeat(FailedMessages.REASON_MAX + 1) + "\"}");
        for (String body : bodies) {
            HttpResponse<String> res = cancel("k1", body);
            assertEquals(400, res.statusCode(), body + ": " + res.body());
            assertTrue(new JsonObject(res.body()).getString("error").startsWith("bad request: "), body + ": " + res.body());
        }
        assertEquals(List.of(), asked, "no act was asked");
        assertEquals(Optional.of(kept), dlq.get("k1").await(), "k1 stays parked");
        HttpResponse<String> longest = cancel("k1", "{\"reason\": \"" + "x".repeat(FailedMessages.REASON_MAX) + "\"}");
        assertEquals(200, longest.statusCode(), "witness: 1024 characters are a reason: " + longest.body());
    }

    /**
     * A form body is not buffered as JSON, so it never reads as "no body": multipart form data is 400 and changes nothing, as an
     * empty form is; a request that declares no content (an empty body with Content-Length 0) is a cancel without a reason.
     */
    @Test
    void aFormBodyIs400AndAnEmptyBodyIsNoReason() throws Exception {
        start(Optional.empty());
        stopped("k1");
        Parked kept = dlq.get("k1").await().orElseThrow();
        String boundary = "rail8-test-boundary";
        String form = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"reason\"\r\n\r\nduplicate\r\n--" + boundary + "--\r\n";

        HttpResponse<String> multipart = send("POST", "/admin/dlq/k1/cancel", form, Map.of("Content-Type", "multipart/form-data; boundary=" + boundary));
        assertEquals(400, multipart.statusCode(), multipart.body());
        assertTrue(new JsonObject(multipart.body()).getString("error").startsWith("bad request: "), multipart.body());
        HttpResponse<String> urlencoded = send("POST", "/admin/dlq/k1/cancel", "reason=duplicate", Map.of("Content-Type", "application/x-www-form-urlencoded"));
        assertEquals(400, urlencoded.statusCode(), urlencoded.body());
        assertEquals(List.of(), asked, "no act was asked");
        assertEquals(Optional.of(kept), dlq.get("k1").await(), "k1 stays parked");

        HttpResponse<String> empty = send("POST", "/admin/dlq/k1/cancel", "", Map.of("Content-Type", "application/x-www-form-urlencoded"));
        assertEquals(200, empty.statusCode(), "witness: an empty body is no body: " + empty.body());
        assertEquals(List.of("k1 unknown 127.0.0.1 -"), asked, "no reason");
    }

    @Test
    void aBodyAbove8KiBIs413() throws Exception {
        start(Optional.empty());
        stopped("k1");
        String body = "{\"reason\": \"" + "x".repeat(AdminServer.CANCEL_BODY_MAX) + "\"}";
        HttpResponse<String> res = cancel("k1", body);
        assertEquals(413, res.statusCode(), res.body());
        assertEquals(new JsonObject().put("error", "the request body is above 8192 bytes"), new JsonObject(res.body()));
        assertEquals(List.of(), asked, "no act was asked");
        assertTrue(dlq.get("k1").await().isPresent(), "k1 stays parked");
    }

    @Test
    void anActorOver128CharactersIs400() throws Exception {
        start(Optional.empty());
        stopped("k1");
        HttpResponse<String> res = send("POST", "/admin/dlq/k1/cancel", null, Map.of("X-Rail8-Actor", "a".repeat(AdminServer.ACTOR_MAX + 1)));
        assertEquals(400, res.statusCode(), res.body());
        assertEquals(List.of(), asked, "no act was asked");
    }

    @Test
    void anUnknownIdIs404AndOneBesideAnUnsettledRowIs409() throws Exception {
        start(Optional.empty());
        HttpResponse<String> unknown = cancel("m-0", null);
        assertEquals(404, unknown.statusCode(), unknown.body());
        assertEquals("no DLQ row for message m-0", new JsonObject(unknown.body()).getString("error"));

        rows.enqueue("m-1", "t", 1, null).await();
        long seq = rows.claim("n1", "t", 1, LEASE).await().get(0).seq();
        dlq.park(ParkedRows.byTheWorker("m-1", "t", 1)).await();
        HttpResponse<String> unsettled = cancel("m-1", null);
        assertEquals(409, unsettled.statusCode(), unsettled.body());
        assertEquals("message m-1 is not cancelled: its work item " + seq + " is not settled yet", new JsonObject(unsettled.body()).getString("error"));
        assertTrue(dlq.get("m-1").await().isPresent(), "m-1 stays parked");
    }

    @Test
    void withoutDeploymentsThereIsNoRetry() throws Exception {
        start(Optional.empty());
        HttpResponse<String> res = send("POST", "/admin/dlq/m-1/retry", null, Map.of());
        assertEquals(404, res.statusCode(), res.body());
        assertEquals(new JsonObject().put("error", "no such route"), new JsonObject(res.body()));
    }

    @Test
    void aCancelNeedsTheToken() throws Exception {
        start(Optional.of("s3cret-token"));
        stopped("k1");
        assertEquals(401, cancel("k1", null).statusCode(), "no token");
        assertEquals(List.of(), asked, "no act was asked");
        HttpResponse<String> ok = send("POST", "/admin/dlq/k1/cancel", null, Map.of("Authorization", "Bearer s3cret-token"));
        assertEquals(200, ok.statusCode(), "witness: with the token it is cancelled: " + ok.body());
    }
}
