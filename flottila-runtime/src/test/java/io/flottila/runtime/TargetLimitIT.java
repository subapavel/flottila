package io.flottila.runtime;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-09b (D-099) end to end: a node on Postgres with the real http component, two packages whose sync flows send to two paths of
 * one local partner — one target, {@code http://127.0.0.1:<port>} — and {@code FLOTTILA_TARGET_LIMITS} giving it 2 calls (its key
 * written upper-case, as the node canonicalises it), {@code FLOTTILA_TARGET_MAX_WAIT} PT4S. The partner ({@link CountingPartner})
 * holds every request until the case answers it and counts the requests it holds at once; the node's {@code GET /admin/targets}
 * shows the limit's gauge.
 *
 * <p>Six messages, three per flow: the partner never holds more than two at once — the two flows' sends share the one limit —
 * and every message replies once its turn came. Then two held and one more: past maxWait that one answers 503 with Retry-After,
 * the partner never saw it, and the gauge counts it refused. Waits are bounded spins on what the partner and the gauge show; the
 * one wait on time is the node's own maxWait on its real timer.
 */
class TargetLimitIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17");
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path data;
    String database;
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    final List<CountingPartner> partners = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void createTheCaseDatabase() throws SQLException {
        database = "targets_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
    }

    @AfterEach
    void closeEverything() throws Exception {
        partners.forEach(CountingPartner::releaseAll);   // no held request outlives its case
        for (Node n : nodes) NodeIT.closeNode(n);
        for (HttpServer s : servers) s.close().await();
    }

    // --- fixture

    /** A partner that holds every request until released; counts the requests it holds at once and their peak. */
    static final class CountingPartner {
        final AtomicInteger current = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final List<String> arrivals = new CopyOnWriteArrayList<>();
        private final List<Promise<Void>> held = new ArrayList<>();   // guarded by this
        int port;

        void arrived(String path, Promise<Void> answer) {
            peak.accumulateAndGet(current.incrementAndGet(), Math::max);
            arrivals.add(path);
            synchronized (this) {
                held.add(answer);
            }
        }

        synchronized int held() {
            return held.size();
        }

        /** Answers every request held now; answers the number answered. */
        int releaseAll() {
            List<Promise<Void>> all;
            synchronized (this) {
                all = new ArrayList<>(held);
                held.clear();
            }
            all.forEach(Promise::tryComplete);
            return all.size();
        }

        long arrivedAt(String path) {
            return arrivals.stream().filter(path::equals).count();
        }
    }

    CountingPartner partner() {
        CountingPartner partner = new CountingPartner();
        HttpServer server = vertx.createHttpServer().requestHandler(req -> {
            Promise<Void> answer = Promise.promise();
            // counted down before the answer is written: a permit the client gives back after it is never still counted here
            answer.future().onComplete(v -> {
                partner.current.decrementAndGet();
                req.response().putHeader("content-type", "application/xml").end("<ok/>");
            });
            partner.arrived(req.path(), answer);
        }).listen(0, "127.0.0.1").await();
        servers.add(server);
        partners.add(partner);
        partner.port = server.actualPort();
        return partner;
    }

    Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", data.toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", database);
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        return env;
    }

    Node start(Map<String, String> env) throws Exception {
        NodeConfig config = NodeConfig.from(env);
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(new HttpComponent(ctx.vertx(), ctx.blobs(), ctx.ids())), List.of(), List.of()));
        nodes.add(node);
        return node;
    }

    static CompletableFuture<HttpResponse<String>> post(HttpClient client, int inPort, String text) {
        return client.sendAsync(NodeIT.request("POST", inPort, "/in", ("<order>" + text + "</order>").getBytes(StandardCharsets.UTF_8), "application/xml"),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The answer {@code f} completes with, within 30 s; else the case fails naming {@code what}. */
    static HttpResponse<String> answer(CompletableFuture<HttpResponse<String>> f, String what) throws Exception {
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException stuck) {
            throw new AssertionError(what + ": no answer within 30 s", stuck);
        }
    }

    /** The node's gauge of {@code target} as {@code GET /admin/targets} lists it. */
    static JsonObject gauge(int admin, String target) throws Exception {
        JsonObject item = NodeIT.listed(admin, "targets", "target").get(target);
        if (item == null) throw new AssertionError("GET /admin/targets lists no " + target + ": " + NodeIT.get(admin, "/admin/targets").body());
        return item;
    }

    static boolean gaugeIs(int admin, String target, String field, int value) {
        try {
            return gauge(admin, target).getInteger(field) == value;
        } catch (Exception | AssertionError notYet) {
            return false;
        }
    }

    /** Spins (yielding, never sleeping) until {@code condition} holds; after 20 s fails with {@code what}, read then. */
    static void until(java.util.function.BooleanSupplier condition, java.util.function.Supplier<String> what) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("not within 20 s: " + what.get());
            Thread.onSpinWait();
            Thread.yield();
        }
    }

    /** {@code GET /admin/targets} as status and body, for a failure message. */
    static String targets(int admin) {
        try {
            HttpResponse<String> r = NodeIT.get(admin, "/admin/targets");
            return r.statusCode() + " " + r.body();
        } catch (Exception e) {
            return e.toString();
        }
    }

    // --- the case

    @Test
    void twoDeployedFlowsCallingOneLocalPartnerNeverExceedTheLimit() throws Exception {
        CountingPartner partner = partner();
        String target = "http://127.0.0.1:" + partner.port;
        Map<String, String> env = env();
        env.put("FLOTTILA_TARGET_LIMITS", "HTTP://127.0.0.1:" + partner.port + "=2");
        env.put("FLOTTILA_TARGET_MAX_WAIT", "PT4S");
        Node node = start(env);
        int admin = node.adminPort();
        int inOne = NodeIT.freePort();
        NodeIT.putOk(admin, "one", NodeIT.relay("one", inOne, target + "/one").zip());
        int inTwo = NodeIT.freePort();
        NodeIT.putOk(admin, "two", NodeIT.relay("two", inTwo, target + "/two").zip());

        try (HttpClient client = NodeIT.http()) {
            List<CompletableFuture<HttpResponse<String>>> answers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                answers.add(post(client, inOne, "one-" + i));
                answers.add(post(client, inTwo, "two-" + i));
            }
            until(() -> partner.held() == 2 && gaugeIs(admin, target, "waiting", 4),
                    () -> "two sends at the partner, four waiting at the limit; partner holds " + partner.held() + ", arrivals " + partner.arrivals + ", targets " + targets(admin));
            assertEquals(new JsonObject().put("target", target).put("maxCalls", 2).put("maxWaitMs", 4000).put("inFlight", 2).put("waiting", 4).put("refused", 0),
                    gauge(admin, target), "the limit of FLOTTILA_TARGET_LIMITS under its canonical key, maxWait of FLOTTILA_TARGET_MAX_WAIT");
            int answered = partner.releaseAll();
            while (answered < 6) {
                int expected = Math.min(2, 6 - answered);
                until(() -> partner.held() == expected, () -> "the next " + expected + " sends reach the partner; arrivals " + partner.arrivals);
                answered += partner.releaseAll();
            }
            for (CompletableFuture<HttpResponse<String>> f : answers) {
                HttpResponse<String> r = answer(f, "a reply");
                assertEquals(200, r.statusCode(), "every message replies once its turn came: " + r.body());
            }
            assertEquals(2, partner.peak.get(), "the partner never held more than the limit's 2 requests at once, and held 2; arrivals " + partner.arrivals);
            assertEquals(3, partner.arrivedAt("/one"), "flow one's three sends, each once: " + partner.arrivals);
            assertEquals(3, partner.arrivedAt("/two"), "flow two's three sends, each once: " + partner.arrivals);

            CompletableFuture<HttpResponse<String>> one = post(client, inOne, "held-1");
            CompletableFuture<HttpResponse<String>> two = post(client, inTwo, "held-2");
            until(() -> partner.held() == 2, () -> "two sends held at the partner; arrivals " + partner.arrivals);
            CompletableFuture<HttpResponse<String>> late = post(client, inTwo, "late");
            until(() -> gaugeIs(admin, target, "waiting", 1), () -> "the third send waits at the limit; arrivals " + partner.arrivals + ", targets " + targets(admin));

            HttpResponse<String> refused = answer(late, "the answer past maxWait");
            assertEquals(503, refused.statusCode(), "past maxWait the sync flow answers 503: " + refused.body());
            assertTrue(refused.headers().firstValue("retry-after").isPresent(), "with Retry-After: " + refused.headers().map());
            assertEquals(8, partner.arrivals.size(), "the refused message never reached the partner: " + partner.arrivals);
            JsonObject busy = gauge(admin, target);
            assertEquals(List.of(2, 0, 1), List.of(busy.getInteger("inFlight"), busy.getInteger("waiting"), busy.getInteger("refused")),
                    "two in flight, none waiting, one refused: " + busy);

            partner.releaseAll();
            assertEquals(200, answer(one, "held 1").statusCode(), "the held messages reply");
            assertEquals(200, answer(two, "held 2").statusCode(), "the held messages reply");
            until(() -> gaugeIs(admin, target, "inFlight", 0), () -> "every permit given back; targets " + targets(admin));
            assertEquals(2, partner.peak.get(), "never more than 2 at once; arrivals " + partner.arrivals);
        }
    }
}
