package dev.rail8.runtime;

import dev.rail8.components.http.HttpComponent;
import dev.rail8.runtime.deploy.PackageDraft;
import dev.rail8.runtime.node.Node;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.plugin.Plugins;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.runtime.node.TestJournalKey;
import dev.rail8.testkit.SharedPostgres;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls between flows on a node against a real Postgres: the node ships the {@code direct} component, so a flow's {@code to} an
 * out endpoint of scheme {@code direct} calls the flow whose in endpoint serves the same address, in another package or the same.
 * What the caller gets is decided by the callee's delivery mode: a sync callee's reply, an at-least-once callee's saved arrival
 * (its own run later, to its partner), an at-most-once callee's start. A sync callee's invalid input fails the caller's step as
 * invalid input: a sync caller's sender gets 422, an at-least-once caller is parked at once, its failed row naming the callee's
 * message. A caller's run retry hands its message over again: the callee runs twice, under one {@code rail8.ref}. Every message
 * of a chain carries the key {@code rail8.ref}, the id of the chain's first message, and the admin API finds them all by it. A
 * chain deeper than 8 calls is refused for good at the ninth: the first caller's sender gets 502 and the last flow never runs.
 *
 * <p>The node runs on its modules ({@link Plugins}), the shipped components and steps. Each sender is an http in endpoint on a
 * free loopback port; each partner a local HTTP server that records what it saw. Every case runs on a database of its own.
 */
class CallsBetweenFlowsIT {

    static final SharedPostgres pg = SharedPostgres.database();
    static final AtomicInteger cases = new AtomicInteger();
    static Vertx vertx;

    @TempDir Path data;
    String database;
    final List<Node> nodes = new CopyOnWriteArrayList<>();
    final List<HttpServer> servers = new CopyOnWriteArrayList<>();

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
        database = "calls_" + cases.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement create = admin.createStatement()) {
            create.execute("CREATE DATABASE \"" + database + "\"");
        }
    }

    @AfterEach
    void closeEverything() throws Exception {
        for (Node n : nodes) OnVirtualThread.call(() -> { n.close(); return null; });
        for (HttpServer s : servers) s.close().await();
    }

    // --- fixture

    /** A ci node on the case database: admin on any free port, data in a directory of its own, its modules. */
    Node startNode() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "ci");   // ci is no deployed environment: plain-http endpoints and manual deploys are allowed
        env.put("RAIL8_NODE_ID", "node-calls");
        env.put("RAIL8_ADMIN_PORT", "0");
        env.put("RAIL8_PEER_PORT", "0");
        env.put("RAIL8_DATA_DIR", Files.createDirectories(data.resolve("node-calls")).toString());
        env.put("RAIL8_PG_HOST", pg.getHost());
        env.put("RAIL8_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("RAIL8_PG_DATABASE", database);
        env.put("RAIL8_PG_USER", pg.getUsername());
        env.put("RAIL8_PG_PASSWORD", pg.getPassword());
        env.put("RAIL8_PG_POOL_SIZE", "4");
        TestJournalKey.with(env);
        NodeConfig config = NodeConfig.from(env);
        Plugins shipped = Plugins.fromEnv(env);
        Node node = OnVirtualThread.call(() -> Node.start(config, vertx, shipped::components, shipped.steps(), shipped.languages()));
        nodes.add(node);
        return node;
    }

    /** A partner that records each body it gets and answers 200 with it, except 503 for the first {@code refusals} requests. */
    HttpServer partner(List<String> saw, int refusals) {
        AtomicInteger seen = new AtomicInteger();
        HttpServer server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(b -> {
            saw.add(b.toString());
            if (seen.incrementAndGet() <= refusals) req.response().setStatusCode(503).end("not now");
            else req.response().putHeader("content-type", req.getHeader("content-type")).end(b);
        })).listen(0, "127.0.0.1").await();
        servers.add(server);
        return server;
    }

    /** {@code name} 1.0.0 with no flow yet. */
    static PackageDraft pkg(String name) {
        return PackageDraft.of(name, "1.0.0");
    }

    /** Adds the http in endpoint {@code id} on 127.0.0.1:{@code port}, path {@code /in}. */
    static PackageDraft door(PackageDraft p, String id, int port) {
        return p.endpoint(id, "http", "in", new JsonObject().put("host", "127.0.0.1").put("port", port).put("path", "/in"));
    }

    /** Adds the direct endpoint {@code id} of {@code direction} on {@code address}. */
    static PackageDraft direct(PackageDraft p, String id, String direction, String address) {
        return p.endpoint(id, "direct", direction, new JsonObject().put("address", address));
    }

    /** Adds the http out endpoint {@code id} to the partner {@code server}. */
    static PackageDraft toPartner(PackageDraft p, String id, HttpServer server) {
        return p.endpoint(id, "http", "out", new JsonObject().put("url", "http://127.0.0.1:" + server.actualPort() + "/partner").put("maxBodyBytes", 4096));
    }

    static JsonObject to(String name, String endpoint) {
        return new JsonObject().put("type", "to").put("name", name).put("endpoint", endpoint);
    }

    static JsonObject jq(String name, String filter) {
        return new JsonObject().put("type", "jq").put("name", name).put("filter", filter);
    }

    static JsonArray steps(JsonObject... steps) {
        JsonArray all = new JsonArray();
        for (JsonObject s : steps) all.add(s);
        return all;
    }

    /** Deploys {@code p} on the node's admin API: 200. */
    static void deploy(Node node, PackageDraft p) throws Exception {
        HttpResponse<String> put = NodeIT.send(NodeIT.http(), "PUT", node.adminPort(), "/admin/packages/" + p.name(), p.zip(), "application/zip");
        assertEquals(200, put.statusCode(), "fixture: the package " + p.name() + " deploys: " + put.body());
    }

    static HttpResponse<String> post(int port, String body, String contentType) throws IOException, InterruptedException {
        return NodeIT.send(NodeIT.http(), "POST", port, "/in", body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    /** The ids {@code GET /admin/messages?key=rail8.ref:<ref>} lists. */
    static Set<String> chain(int admin, String ref) {
        try {
            HttpResponse<String> r = NodeIT.get(admin, "/admin/messages?key=rail8.ref:" + ref);
            assertEquals(200, r.statusCode(), r.body());
            Set<String> ids = new HashSet<>();
            new JsonObject(r.body()).getJsonArray("items").forEach(id -> ids.add((String) id));
            return ids;
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("the admin API did not answer", e);
        }
    }

    /** The ids of the messages that arrived at {@code flow}, as {@code GET /admin/messages?flow=<flow>&step=ingress} lists them. */
    static List<String> messagesOf(int admin, String flow) {
        try {
            HttpResponse<String> r = NodeIT.get(admin, "/admin/messages?flow=" + flow + "&step=ingress");
            assertEquals(200, r.statusCode(), r.body());
            List<String> ids = new ArrayList<>();
            new JsonObject(r.body()).getJsonArray("items").forEach(id -> ids.add((String) id));
            return ids;
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("the admin API did not answer", e);
        }
    }

    /** The message {@code id} as {@code GET /admin/messages/{id}} shows it. */
    static JsonObject message(int admin, String id) {
        try {
            HttpResponse<String> r = NodeIT.get(admin, "/admin/messages/" + id);
            assertEquals(200, r.statusCode(), r.body());
            return new JsonObject(r.body());
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("the admin API did not answer", e);
        }
    }

    /**
     * The id of the one message that arrived at {@code flow}, once it is recorded: a sync answer names no message, and after a
     * call its headers are the callee's reply's.
     */
    static String onlyMessageOf(int admin, String flow) {
        NodeIT.until(() -> messagesOf(admin, flow).size() == 1, "one message of " + flow + " is recorded: " + messagesOf(admin, flow));
        return messagesOf(admin, flow).get(0);
    }

    static String messageId(HttpResponse<String> r) {
        return r.headers().firstValue(HttpComponent.H_MESSAGE_ID).orElseThrow(() -> new AssertionError("the answer names its message: " + r.headers()));
    }

    // --- cases

    /**
     * Three sync flows in two packages: {@code shop/front} calls {@code pricing/price}, which calls {@code pricing/tax}. The
     * sender gets the reply of the whole chain, and every message of it is found by the key {@code rail8.ref} of the first.
     */
    @Test
    void aSyncChainAnswersTheSenderAndEveryMessageOfItIsFoundByItsRef() throws Exception {
        Node node = startNode();
        int door = NodeIT.freePort();
        PackageDraft shop = direct(door(pkg("shop"), "in", door), "pricing", "out", "/pricing")
                .flow("front", "1.0.0", "sync", "in", 4096, steps(to("price", "pricing")));
        PackageDraft pricing = direct(direct(pkg("pricing"), "price-in", "in", "/pricing"), "tax", "out", "/tax")
                .flow("price", "1.0.0", "sync", "price-in", 4096, steps(jq("priced", "{id: .id, price: 100}"), to("taxed", "tax")));
        pricing = direct(pricing, "tax-in", "in", "/tax").flow("tax", "1.0.0", "sync", "tax-in", 4096, steps(jq("vat", ". + {vat: (.price / 5)}")));
        deploy(node, shop);   // before its callee: a call no flow serves is no refusal
        deploy(node, pricing);

        HttpResponse<String> answer = post(door, "{\"id\": 7}", "application/json");

        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals(new JsonObject().put("id", 7).put("price", 100).put("vat", 20), new JsonObject(answer.body()), "the reply of the whole chain");
        int admin = node.adminPort();
        String first = onlyMessageOf(admin, "shop/front");
        NodeIT.until(() -> chain(admin, first).size() == 3, "the caller and both callees carry rail8.ref " + first + ": " + chain(admin, first));
        Set<String> ids = chain(admin, first);
        assertTrue(ids.contains(first), "the first caller carries its own id as its ref: " + ids);
        Map<String, String> flows = new HashMap<>();
        for (String id : ids) flows.put(message(admin, id).getString("flow"), id);
        assertEquals(Set.of("shop/front", "pricing/price", "pricing/tax"), flows.keySet(), "one message of each flow: " + flows);
        assertEquals(first, ingressHeader(admin, flows.get("pricing/price"), HttpComponent.H_MESSAGE_ID), "a callee's x-message-id is its caller's id");
        assertEquals(flows.get("pricing/price"), ingressHeader(admin, flows.get("pricing/tax"), HttpComponent.H_MESSAGE_ID));
    }

    /** The header {@code name} of the message {@code id} as it arrived. */
    static String ingressHeader(int admin, String id, String name) {
        JsonObject ingress = message(admin, id).getJsonArray("steps").getJsonObject(0);
        assertEquals("ingress", ingress.getString("name"), "the first row is the arrival: " + ingress);
        return ingress.getJsonObject("headers").getString(name);
    }

    /**
     * An at-least-once callee: the sync caller goes on with its message as it sent it once the callee's arrival is saved, and the
     * callee runs later, on its own, to its partner.
     */
    @Test
    void anAtLeastOnceCalleeIsSavedAndRunsOnItsOwnWhileTheCallerGoesOn() throws Exception {
        List<String> saw = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(saw, 0);
        Node node = startNode();
        int door = NodeIT.freePort();
        PackageDraft shop = direct(door(pkg("shop"), "in", door), "audit", "out", "/audit")
                .flow("front", "1.0.0", "sync", "in", 4096, steps(to("audited", "audit"), jq("done", ". + {done: true}")));
        PackageDraft audit = toPartner(direct(pkg("audit"), "audit-in", "in", "/audit"), "partner", partner)
                .flow("keep", "1.0.0", "at-least-once", "audit-in", 4096, steps(to("send", "partner")));
        deploy(node, audit);
        deploy(node, shop);

        HttpResponse<String> answer = post(door, "{\"id\": 8}", "application/json");

        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals(new JsonObject().put("id", 8).put("done", true), new JsonObject(answer.body()), "the caller went on with its message as it sent it");
        NodeIT.until(() -> saw.size() == 1, "the callee's worker sent the saved arrival to its partner");
        assertEquals(new JsonObject().put("id", 8), new JsonObject(saw.get(0)), "the callee got the caller's body");
        int admin = node.adminPort();
        String first = onlyMessageOf(admin, "shop/front");
        NodeIT.until(() -> messagesOf(admin, "audit/keep").size() == 1 && chain(admin, first).size() == 2, "the callee is recorded under the caller's ref");
        String callee = messagesOf(admin, "audit/keep").get(0);
        assertNotEquals(first, callee, "the callee's message has an id of its own");
        assertEquals(first, ingressHeader(admin, callee, HttpComponent.H_MESSAGE_ID));
    }

    /** An at-most-once callee: the caller goes on at once and the callee runs on its own thread; nothing of it is queued. */
    @Test
    void anAtMostOnceCalleeRunsOnItsOwnWhileTheCallerGoesOn() throws Exception {
        List<String> saw = new CopyOnWriteArrayList<>();
        HttpServer partner = partner(saw, 0);
        Node node = startNode();
        int door = NodeIT.freePort();
        PackageDraft shop = direct(door(pkg("shop"), "in", door), "notify", "out", "/notify")
                .flow("front", "1.0.0", "sync", "in", 4096, steps(to("notified", "notify")));
        PackageDraft notify = toPartner(direct(pkg("notify"), "notify-in", "in", "/notify"), "partner", partner)
                .flow("tell", "1.0.0", "at-most-once", "notify-in", 4096, steps(to("send", "partner")));
        deploy(node, notify);
        deploy(node, shop);

        HttpResponse<String> answer = post(door, "{\"id\": 9}", "application/json");

        assertEquals(200, answer.statusCode(), answer.body());
        assertEquals(new JsonObject().put("id", 9), new JsonObject(answer.body()), "the caller's message as it sent it");
        NodeIT.until(() -> saw.size() == 1, "the callee sent the caller's body to its partner");
        assertEquals(new JsonObject().put("id", 9), new JsonObject(saw.get(0)));
        int admin = node.adminPort();
        String first = onlyMessageOf(admin, "shop/front");
        NodeIT.until(() -> chain(admin, first).size() == 2, "the callee is recorded under the caller's ref");
    }

    /**
     * A sync callee that refuses its input as invalid fails its caller's step as invalid input: the sync caller's sender gets 422,
     * the at-least-once caller is parked at once (no retry), its failed row naming the callee's message.
     */
    @Test
    void aSyncCalleesInvalidInputAnswersTheSender422AndParksAnAtLeastOnceCaller() throws Exception {
        Node node = startNode();
        int syncDoor = NodeIT.freePort();
        int asyncDoor = NodeIT.freePort();
        PackageDraft check = direct(pkg("check"), "check-in", "in", "/check")
                .flow("order", "1.0.0", "sync", "check-in", 4096, steps(new JsonObject().put("type", "validate").put("name", "hasId").put("required", "/Order/Id")));
        PackageDraft shop = direct(door(door(pkg("shop"), "in", syncDoor), "in-async", asyncDoor), "check", "out", "/check")
                .flow("front", "1.0.0", "sync", "in", 4096, steps(to("checked", "check")))
                .flow("queued", "1.0.0", "at-least-once", "in-async", 4096, steps(to("checked", "check")));
        shop.flowDoc("queued").getJsonObject("delivery").put("retries", new JsonObject().put("count", 3).put("interval", "1h"));
        deploy(node, check);
        deploy(node, shop);

        HttpResponse<String> fine = post(syncDoor, "<Order><Id>1</Id></Order>", "application/xml");
        assertEquals(200, fine.statusCode(), "control: a valid order passes the callee: " + fine.body());
        HttpResponse<String> invalid = post(syncDoor, "<Order/>", "application/xml");
        assertEquals(422, invalid.statusCode(), "the callee's invalid input is the sender's: " + invalid.body());

        HttpResponse<String> accepted = post(asyncDoor, "<Order/>", "application/xml");
        assertEquals(202, accepted.statusCode(), accepted.body());
        String caller = messageId(accepted);
        int admin = node.adminPort();
        NodeIT.until(() -> NodeIT.dlqItems(admin).stream().map(JsonObject.class::cast).anyMatch(r -> caller.equals(r.getString("id"))),
                "the caller is parked at once, an hour before its first retry would be due");
        NodeIT.until(() -> chain(admin, caller).size() == 1, "the callee's message carries the caller's ref");
        String callee = chain(admin, caller).iterator().next();
        JsonObject row = NodeIT.dlqItems(admin).stream().map(JsonObject.class::cast).filter(r -> caller.equals(r.getString("id"))).findFirst().orElseThrow();
        assertEquals("shop/queued", row.getString("flow"), row.encode());
        assertEquals("checked", row.getString("name"), "parked at the call: " + row.encode());
        assertTrue(row.getString("error").contains(callee), "the failed row names the callee's message " + callee + ": " + row.encode());
    }

    /**
     * An at-least-once caller whose step after the call fails retryably runs again later and hands its message over again: the
     * callee runs twice, two messages under the one {@code rail8.ref} of the caller.
     */
    @Test
    void aCallersRetryHandsTheMessageOverTwiceUnderOneRef() throws Exception {
        List<String> calleeSaw = new CopyOnWriteArrayList<>();
        List<String> callerSaw = new CopyOnWriteArrayList<>();
        HttpServer calleePartner = partner(calleeSaw, 0);
        HttpServer callerPartner = partner(callerSaw, 1);   // 503 once: the caller's first run fails after its call
        Node node = startNode();
        int door = NodeIT.freePort();
        PackageDraft audit = toPartner(direct(pkg("audit"), "audit-in", "in", "/audit"), "partner", calleePartner)
                .flow("keep", "1.0.0", "at-least-once", "audit-in", 4096, steps(to("send", "partner")));
        PackageDraft shop = toPartner(direct(door(pkg("shop"), "in", door), "audit", "out", "/audit"), "partner", callerPartner)
                .flow("queued", "1.0.0", "at-least-once", "in", 4096, steps(to("audited", "audit"), to("send", "partner")));
        shop.flowDoc("queued").getJsonObject("delivery").put("retries", new JsonObject().put("count", 1).put("interval", "1s"));
        deploy(node, audit);
        deploy(node, shop);

        HttpResponse<String> accepted = post(door, "{\"id\": 10}", "application/json");

        assertEquals(202, accepted.statusCode(), accepted.body());
        String caller = messageId(accepted);
        NodeIT.until(() -> callerSaw.size() == 2 && calleeSaw.size() == 2, "the caller ran twice, and so did the callee: " + callerSaw + " " + calleeSaw);
        int admin = node.adminPort();
        NodeIT.until(() -> chain(admin, caller).size() == 3, "the caller and its two hand-overs carry its ref: " + chain(admin, caller));
        List<String> callees = new ArrayList<>(chain(admin, caller));
        assertTrue(callees.remove(caller), "the caller carries its own ref: " + callees);
        assertNotEquals(callees.get(0), callees.get(1), "each hand-over is a message of its own");
        for (String id : callees) assertEquals("audit/keep", message(admin, id).getString("flow"));
    }

    /**
     * A package whose call closes a cycle with a flow of another package is refused at deploy, the admin API's 422 naming the path;
     * the first package runs on.
     */
    @Test
    void aDeployThatClosesACycleOfCallsIs422NamingThePath() throws Exception {
        Node node = startNode();
        deploy(node, direct(direct(pkg("billing"), "charge-in", "in", "/charge"), "orders", "out", "/orders")
                .flow("charge", "1.0.0", "sync", "charge-in", 4096, steps(to("back", "orders"))));
        PackageDraft shop = direct(direct(pkg("shop"), "orders-in", "in", "/orders"), "charge", "out", "/charge")
                .flow("orders", "1.0.0", "sync", "orders-in", 4096, steps(to("pay", "charge")));

        HttpResponse<String> put = NodeIT.send(NodeIT.http(), "PUT", node.adminPort(), "/admin/packages/shop", shop.zip(), "application/zip");

        assertEquals(422, put.statusCode(), put.body());
        assertEquals(List.of("calls between flows would form a cycle: flow 'orders' -> direct /charge -> flow 'billing/charge' -> direct /orders -> flow 'orders'"),
                new JsonObject(put.body()).getJsonArray("problems").getList(), put.body());
        HttpResponse<String> packages = NodeIT.get(node.adminPort(), "/admin/packages");
        assertEquals(List.of("billing"), new JsonObject(packages.body()).getJsonArray("items").stream().map(i -> ((JsonObject) i).getString("name")).toList());
    }

    /**
     * Ten sync flows in a line, {@code chain/d0} to {@code chain/d9}, each calling the next: the call of {@code d8} would be the
     * ninth deep, so it is refused for good. The sender of {@code d0} gets 502, {@code d9} never runs, and the eight callees that
     * did run are found by the ref of the first.
     */
    @Test
    void aChainDeeperThanEightCallsIsRefusedForGood() throws Exception {
        Node node = startNode();
        int door = NodeIT.freePort();
        PackageDraft chain = door(pkg("chain"), "in", door);
        for (int i = 0; i <= 9; i++) {
            if (i > 0) chain = direct(chain, "d" + i + "-in", "in", "/d" + i);
            if (i < 9) chain = direct(chain, "d" + (i + 1), "out", "/d" + (i + 1));
            JsonArray steps = i < 9 ? steps(to("next", "d" + (i + 1))) : steps(jq("last", "."));
            chain = chain.flow("d" + i, "1.0.0", "sync", i == 0 ? "in" : "d" + i + "-in", 4096, steps);
        }
        deploy(node, chain);

        HttpResponse<String> answer = post(door, "{\"id\": 11}", "application/json");

        assertEquals(502, answer.statusCode(), "a final refusal of a partner: " + answer.body());
        assertTrue(answer.body().contains("more than the 8"), "the answer names the cap: " + answer.body());
        int admin = node.adminPort();
        String d0 = onlyMessageOf(admin, "chain/d0");
        NodeIT.until(() -> chain(admin, d0).size() == 8, "d1 to d8 ran, each under the first's ref: " + chain(admin, d0));
        assertEquals(List.of(), messagesOf(admin, "chain/d9"), "d9 never ran");
    }
}
