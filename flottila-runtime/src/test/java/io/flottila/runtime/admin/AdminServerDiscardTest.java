package io.flottila.runtime.admin;

import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.WorkSignals;
import io.flottila.core.flow.Workers;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.queue.WorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-141 (D-126 L3, L4) without a database: what the discard route hands the queue and does with its answer. A blank
 * {@code X-Flottila-Actor} is {@code unknown} as an absent one is; the remote is the client's address; a discard whose key has no
 * next row (an unkeyed one) announces nothing, one with a next row announces that row's node for its flow. The lost route over a
 * queue that keeps {@link WorkQueue#lost}'s default lists nothing and refuses a bad limit as /held does.
 */
@Timeout(60)
class AdminServerDiscardTest {

    static Vertx vertx;
    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryWorkQueue rows = new InMemoryWorkQueue(rt.clock());
    final List<String> asked = new CopyOnWriteArrayList<>();
    final List<String> announced = new CopyOnWriteArrayList<>();
    final WorkSignals signals = (target, flow) -> announced.add(target + " " + flow);
    volatile Optional<WorkQueue.Discarded> answer = Optional.empty();
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

    /** The in-memory rows, their discard recorded ({@code seq actor remote}) and answered with {@link #answer}. */
    void start() throws Exception {
        WorkQueue queue = (WorkQueue) Proxy.newProxyInstance(WorkQueue.class.getClassLoader(), new Class<?>[] {WorkQueue.class}, (p, m, args) -> {
            if (m.getName().equals("discard")) {
                asked.add(args[0] + " " + args[1] + " " + args[2]);
                return Future.succeededFuture(answer);
            }
            try {
                return m.invoke(rows, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        admin = new AdminServer(new AdminServer.Config("127.0.0.1", 0, Optional.empty()), new InMemoryMessageStore(), new InMemoryDlq(), rt.blobs(),
                new AdminServer.HealthProbe(Map.of(), () -> 0L), Optional.empty(), queue, new Workers(), signals);
        port = admin.start(vertx).await(10, TimeUnit.SECONDS);
    }

    HttpResponse<String> send(String method, String path, String actor) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (actor != null) req.header("X-Flottila-Actor", actor);
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aBlankActorIsUnknownAndTheRemoteIsTheClientsAddress() throws Exception {
        start();
        answer = Optional.of(new WorkQueue.Discarded(41, "orders", Optional.empty()));
        HttpResponse<String> blank = send("POST", "/admin/work/9/discard", "");
        assertEquals(200, blank.statusCode(), blank.body());
        HttpResponse<String> named = send("POST", "/admin/work/9/discard", "ops-anna");
        assertEquals(200, named.statusCode(), named.body());
        assertEquals(List.of("9 unknown 127.0.0.1", "9 ops-anna 127.0.0.1"), asked, "blank: unknown; the client's address as the remote");
        assertEquals(new JsonObject().put("seq", 9).put("auditId", 41), new JsonObject(named.body()), "{seq, auditId}");
    }

    @Test
    void aDiscardAnnouncesTheNodeOfItsKeysNextRowAndNothingWithoutOne() throws Exception {
        start();
        answer = Optional.of(new WorkQueue.Discarded(41, "orders", Optional.empty()));
        assertEquals(200, send("POST", "/admin/work/9/discard", "ops-anna").statusCode());
        assertEquals(List.of(), announced, "no next row of its key: nothing announced");
        answer = Optional.of(new WorkQueue.Discarded(42, "invoices", Optional.of("n-b")));
        assertEquals(200, send("POST", "/admin/work/10/discard", "ops-anna").statusCode());
        assertEquals(List.of("n-b invoices"), announced, "the next row's node, for the discarded row's flow");
        answer = Optional.empty();
        HttpResponse<String> notLost = send("POST", "/admin/work/11/discard", "ops-anna");
        assertEquals(404, notLost.statusCode(), notLost.body());
        assertEquals(List.of("n-b invoices"), announced, "a 404 announces nothing");
    }

    @Test
    void theLostRouteOverTheDefaultListsNothingAndRefusesABadLimit() throws Exception {
        start();
        rows.enqueue("m-1", "orders", 1, "K").await();
        HttpResponse<String> listed = send("GET", "/admin/work/lost", null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(new JsonObject().put("items", new io.vertx.core.json.JsonArray()).putNull("next"), new JsonObject(listed.body()));
        HttpResponse<String> bad = send("GET", "/admin/work/lost?limit=0", null);
        assertEquals(400, bad.statusCode(), bad.body());
    }
}
