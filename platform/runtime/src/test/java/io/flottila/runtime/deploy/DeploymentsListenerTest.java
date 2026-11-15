package io.flottila.runtime.deploy;

import io.flottila.api.Component;
import io.flottila.api.Health;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.flottila.runtime.deploy.DeploymentsRedeployTest.assertResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09g (D-072): one listener per host:port on a node. {@code Deployments.check} asks each component where a consumer of each
 * {@code in} endpoint that feeds a flow would listen ({@code Component.listensOn}, on the config as checked: schema-valid, this
 * node's environment laid over it) and refuses — one {@link Kind#INVALID} line each, before anything is built or stopped — an
 * address that overlaps (same port, not 0; equal hosts, or either a wildcard) another of the same new version (once per pair, on
 * the later flow id), the node's admin listener ({@code FLOTTILA_ADMIN_HOST/PORT}), or a running flow of another RUNNING package.
 * The deploying package's current version and FAILED flows and packages hold no port. A restore runs the same check: the later
 * package of a clashing pair is FAILED with that line.
 *
 * <p>The line (D-072): {@code flow '<flow>' (endpoint '<endpoint>') listens on <host>:<port>, which overlaps <host>:<port> of
 * <holder>: one listener per port on a node (D-072)}, the deploying package's flows by local id, another package's qualified,
 * the admin listener {@code the node's admin API (FLOTTILA_ADMIN_HOST/PORT)}, each host as configured, IPv6 in brackets.
 *
 * <p>Every endpoint is a {@link Doors} one: its {@code listensOn} answers {@code host} (default 127.0.0.1) and {@code port} when the
 * endpoint sets a port, and records every call; nothing binds, so any port serves. A second {@link Doors} serves the scheme
 * {@value #GATE}. Every node's admin port is {@value #ADMIN_PORT} unless a case says otherwise.
 */
class DeploymentsListenerTest {

    static final int ADMIN_PORT = 8181;
    static final String GATE = "gate";
    static final String ADMIN = "the node's admin API (FLOTTILA_ADMIN_HOST/PORT)";
    static final DeployedPackage.State RUNNING = DeployedPackage.State.RUNNING;
    static final DeployedFlow.State FLOW_RUNNING = DeployedFlow.State.RUNNING;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final Doors gates = new Doors(GATE);
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final List<Deployments> nodes = new CopyOnWriteArrayList<>();
    final List<Doors> components = new CopyOnWriteArrayList<>();
    Deployments deployments;

    @BeforeEach
    void wire() {
        deployments = node(env("node-1"));
    }

    @AfterEach
    void closeEveryNode() throws Exception {
        for (Doors d : components) d.releaseAll();
        for (Deployments d : nodes) OnVirtualThread.call(() -> { d.close(); return null; });
    }

    // --- fixture

    /** A dev node {@code nodeId}: data under {@code <tmp>/<nodeId>}, its admin API on 127.0.0.1:{@value #ADMIN_PORT}. */
    Map<String, String> env(String nodeId) {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", nodeId);
        env.put("FLOTTILA_DATA_DIR", tmp.resolve(nodeId).toString());
        env.put("FLOTTILA_STOP_DEADLINE", "PT37S");
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        env.put("FLOTTILA_ADMIN_PORT", Integer.toString(ADMIN_PORT));
        return env;
    }

    /** A node of {@code env} over the pool's {@code rows} and {@code archives}, {@code serving} as its components (by scheme). */
    Deployments node(Map<String, String> env, DeploymentStore rows, ArchiveStore archives, Doors... serving) {
        Map<String, Component> byScheme = new HashMap<>();
        for (Doors d : serving) {
            byScheme.put(d.scheme(), d);
            components.add(d);
        }
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), byScheme);
        Deployments d = new Deployments(NodeConfig.from(env), runtime, byScheme, rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                new InMemoryWorkQueue(rt.clock()), () -> 0L, rows, archives);
        nodes.add(d);
        return d;
    }

    /** A node of its own records and archive directory, serving {@link #doors} ({@value Doors#SCHEME}) and {@link #gates} ({@value #GATE}). */
    Deployments node(Map<String, String> env) {
        return node(env, new InMemoryDeploymentStore(), new DirArchiveStore(Path.of(env.get("FLOTTILA_DATA_DIR")).resolve("archives")), doors, gates);
    }

    /** {@code name} 1.0.0 with only its out endpoint {@code partner}. */
    static PackageDraft pkg(String name) {
        return PackageDraft.of(name, "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
    }

    /** Adds the sync flow {@code flow} to {@code partner}, from its own in endpoint {@code <flow>-in} of {@code scheme} with {@code config}. */
    static PackageDraft listening(PackageDraft p, String flow, String scheme, JsonObject config) {
        return p.endpoint(flow + "-in", scheme, "in", config).flow(flow, "1.0.0", "sync", flow + "-in", 1024, PackageDraft.toPartner());
    }

    static PackageDraft listening(PackageDraft p, String flow, JsonObject config) {
        return listening(p, flow, Doors.SCHEME, config);
    }

    /** An in endpoint's config on {@code port}, the host left to the component (127.0.0.1). */
    static JsonObject port(int port) {
        return new JsonObject().put("path", "/in").put("port", port);
    }

    static JsonObject on(String host, int port) {
        return port(port).put("host", host);
    }

    /** The refusal line of D-072. */
    static String line(String flow, String endpoint, String listensOn, String overlaps, String holder) {
        return "flow '" + flow + "' (endpoint '" + endpoint + "') listens on " + listensOn + ", which overlaps " + overlaps + " of " + holder
                + ": one listener per port on a node (D-072)";
    }

    /** A holder of a line: a flow and its endpoint. */
    static String flowOf(String flow, String endpoint) {
        return "flow '" + flow + "' (endpoint '" + endpoint + "')";
    }

    DeployResult deploy(Deployments d, PackageDraft p) throws Exception {
        Path archive = p.zipTo(tmp.resolve("uploads"));
        String name = p.manifest().getString("name");
        return OnVirtualThread.call(() -> d.deploy(name, Optional.empty(), archive));
    }

    /** The deploy of {@code p} on {@code d}, refused as a 422. */
    DeployRefusedException refused(Deployments d, PackageDraft p) {
        Path archive = p.zipTo(tmp.resolve("uploads"));
        String name = p.manifest().getString("name");
        DeployRefusedException r = assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> d.deploy(name, Optional.empty(), archive)),
                "the deploy of " + name + " is refused");
        assertEquals(Kind.INVALID, r.kind(), "a 422: " + r.error() + " " + r.problems());
        return r;
    }

    /** Every problem line of {@code r}, sorted: compared whole, so a line too many or reported twice shows. */
    static List<String> lines(DeployRefusedException r) {
        return r.problems().stream().sorted().toList();
    }

    static List<String> sorted(String... lines) {
        return Stream.of(lines).sorted().toList();
    }

    static Map<String, DeployedPackage.State> packages(Deployments d) {
        Map<String, DeployedPackage.State> m = new TreeMap<>();
        d.packages().forEach(p -> m.put(p.name(), p.state()));
        return m;
    }

    static Map<String, DeployedFlow.State> flows(Deployments d) {
        Map<String, DeployedFlow.State> m = new TreeMap<>();
        d.flows().forEach(f -> m.put(f.id(), f.state()));
        return m;
    }

    /** The consumers {@code d} built for flows of the package {@code pkg}. */
    static List<String> consumersOf(Doors d, String pkg) {
        return d.events().stream().filter(e -> e.startsWith("consumer " + pkg + "/")).toList();
    }

    // --- another package

    @Test
    void aSecondPackageOnTheFirstsPortIs422NamingBothFlowsAndEndpoints() throws Exception {
        deploy(deployments, listening(pkg("billing"), "invoices", on("127.0.0.1", 8080)));
        Doors.Door invoices = doors.door("billing/invoices");
        int producers = doors.producers().size();
        PackageDraft shop = listening(pkg("shop"), "orders", on("0.0.0.0", 8080))
                .endpoint("spare-in", "in", port(8080));   // an in endpoint no flow consumes: nothing listens there
        shop.endpointDoc("partner").getJsonObject("config").put("port", 8080);   // an out endpoint connects out

        DeployRefusedException r = refused(deployments, shop);

        assertEquals(List.of(line("orders", "orders-in", "0.0.0.0:8080", "127.0.0.1:8080", flowOf("billing/invoices", "invoices-in"))), r.problems(),
                "one line: the new flow by its own id and endpoint where it listens (the wildcard 0.0.0.0 as configured), the running flow it overlaps qualified");
        assertEquals(Map.of("billing", RUNNING), packages(deployments), "the first package runs on, alone");
        assertEquals(Map.of("billing/invoices", FLOW_RUNNING), flows(deployments), "only the first package's flow is listed");
        assertTrue(invoices.started() && !invoices.stopped(), "the first package's door was never stopped: " + invoices.history());
        assertEquals(List.of(), consumersOf(doors, "shop"), "no consumer of the refused package was built: " + doors.events());
        assertEquals(producers, doors.producers().size(), "nothing of the refused package was built: the check runs before its flows are");
        List<String> asked = doors.listensOnAsked();
        assertTrue(asked.contains("orders-in"), "the component was asked where the new flow's in endpoint listens: " + asked);
        assertFalse(asked.contains("spare-in"), "an in endpoint no flow consumes is never asked: " + asked);
        assertFalse(asked.contains("partner"), "an out endpoint is never asked: " + asked);

        DeployRefusedException v6 = refused(deployments, listening(pkg("shop"), "orders", on("::", 8080)));

        assertEquals(List.of(line("orders", "orders-in", "[::]:8080", "127.0.0.1:8080", flowOf("billing/invoices", "invoices-in"))), v6.problems(),
                ":: is the IPv6 wildcard, shown in brackets");
    }

    @Test
    void aClashingRedeployIsRefusedBeforeTheRunningVersionIsStopped() throws Exception {
        deploy(deployments, listening(pkg("billing"), "invoices", port(8080)));
        PackageDraft shop = listening(pkg("shop"), "orders", port(7070));
        String v1 = deploy(deployments, shop).pkg().archiveSha256();
        Doors.Door orders = doors.door("shop/orders");
        List<String> events = doors.events();
        int producers = doors.producers().size();
        shop.flowDoc("orders").getJsonObject("limits").put("maxBodyBytes", 2048);   // changed: a deploy that went on would stop it
        listening(shop, "refunds", port(8080));

        DeployRefusedException r = refused(deployments, shop);

        assertEquals(List.of(line("refunds", "refunds-in", "127.0.0.1:8080", "127.0.0.1:8080", flowOf("billing/invoices", "invoices-in"))), r.problems());
        assertFalse(orders.stopped(), "the running version's changed flow was never stopped: " + orders.history());
        assertEquals(events, doors.events(), "no consumer was built, started or stopped");
        assertEquals(producers, doors.producers().size(), "nothing of the new version was built");
        assertEquals(v1, deployments.packages().stream().filter(p -> p.name().equals("shop")).findFirst().orElseThrow().archiveSha256(),
                "shop runs its first version");
    }

    @Test
    void aListenerOfAnotherSchemeOnTheSamePortIs422() throws Exception {
        deploy(deployments, listening(pkg("billing"), "invoices", port(8080)));

        DeployRefusedException r = refused(deployments, listening(pkg("shop"), "orders", GATE, port(8080)));

        assertEquals(List.of(line("orders", "orders-in", "127.0.0.1:8080", "127.0.0.1:8080", flowOf("billing/invoices", "invoices-in"))), r.problems(),
                "a port is the node's, whichever component listens on it");
        assertTrue(gates.listensOnAsked().contains("orders-in"), "the endpoint's own component was asked: " + gates.listensOnAsked());
        assertEquals(List.of(), gates.events(), "no consumer of the other scheme was built: " + gates.events());
    }

    @Test
    void aPortFreedByAnotherPackagesRedeployCanBeTaken() throws Exception {
        PackageDraft billing = listening(listening(pkg("billing"), "invoices", port(8080)), "credits", port(8085));
        deploy(deployments, billing);
        billing.endpointDoc("invoices-in").put("config", port(8090));                   // moved
        billing.without("flows/credits.yaml").without("endpoints/credits-in.yaml");   // removed
        assertResult(List.of(), List.of("billing/invoices"), List.of("billing/credits"), deploy(deployments, billing));

        deploy(deployments, listening(listening(pkg("shop"), "orders", port(8080)), "audit", port(8085)));

        assertEquals(Map.of("billing/invoices", FLOW_RUNNING, "shop/audit", FLOW_RUNNING, "shop/orders", FLOW_RUNNING), flows(deployments),
                "the ports billing's redeploy left are free");
        assertTrue(doors.door("shop/orders").started() && doors.door("shop/audit").started(), doors.events().toString());
        assertEquals(List.of(line("x", "x-in", "127.0.0.1:8090", "127.0.0.1:8090", flowOf("billing/invoices", "invoices-in"))),
                refused(deployments, listening(pkg("other"), "x", port(8090))).problems(), "the port billing moved to is billing's now");
    }

    // --- the same package

    @Test
    void twoFlowsOfOnePackageOnOnePortAre422() throws Exception {
        PackageDraft shop = pkg("shop");
        listening(shop, "audit", port(7070));
        listening(shop, "orders", port(7070));
        listening(shop, "refunds", on("0.0.0.0", 7070));
        listening(shop, "ping", port(7071));

        DeployRefusedException r = refused(deployments, shop);

        assertEquals(sorted(
                        line("orders", "orders-in", "127.0.0.1:7070", "127.0.0.1:7070", flowOf("audit", "audit-in")),
                        line("refunds", "refunds-in", "0.0.0.0:7070", "127.0.0.1:7070", flowOf("audit", "audit-in")),
                        line("refunds", "refunds-in", "0.0.0.0:7070", "127.0.0.1:7070", flowOf("orders", "orders-in"))),
                lines(r), "a line per overlapping pair, once, on the later flow id, the other one named by its local id; ping's port is its own");
        assertEquals(List.of(), deployments.packages(), "nothing of it is listed");
        assertEquals(List.of(), doors.events(), "no consumer was built");
        assertEquals(List.of(), doors.producers(), "nothing was built");

        PackageDraft apart = pkg("shop");
        listening(apart, "audit", port(7070));
        listening(apart, "orders", port(7072));
        listening(apart, "refunds", on("0.0.0.0", 7073));
        listening(apart, "ping", port(7071));
        deploy(deployments, apart);

        assertEquals(Map.of("shop/audit", FLOW_RUNNING, "shop/orders", FLOW_RUNNING, "shop/ping", FLOW_RUNNING, "shop/refunds", FLOW_RUNNING),
                flows(deployments), "each on a port of its own, the same flows deploy");
    }

    @Test
    void aRedeployKeepingItsOwnPortIsNotAClash() throws Exception {
        PackageDraft shop = listening(listening(pkg("shop"), "orders", port(7070)), "audit", port(7071));
        assertResult(List.of("shop/audit", "shop/orders"), List.of(), List.of(), deploy(deployments, shop));

        assertResult(List.of(), List.of(), List.of(), deploy(deployments, shop));   // unchanged
        shop.flowDoc("orders").getJsonObject("limits").put("maxBodyBytes", 2048);
        assertResult(List.of(), List.of("shop/orders"), List.of(), deploy(deployments, shop));   // changed, on its port
        shop.without("flows/orders.yaml").without("endpoints/orders-in.yaml");
        listening(shop, "sales", port(7070));
        assertResult(List.of("shop/sales"), List.of(), List.of("shop/orders"), deploy(deployments, shop));   // renamed onto the old flow's port

        assertEquals(Map.of("shop/audit", FLOW_RUNNING, "shop/sales", FLOW_RUNNING), flows(deployments));
        assertEquals(sorted(
                        line("x", "x-in", "127.0.0.1:7070", "127.0.0.1:7070", flowOf("shop/sales", "sales-in")),
                        line("y", "y-in", "127.0.0.1:7071", "127.0.0.1:7071", flowOf("shop/audit", "audit-in"))),
                lines(refused(deployments, listening(listening(pkg("other"), "x", port(7070)), "y", port(7071)))),
                "the version a package replaces is all a deploy leaves out: for another package the running version's ports are taken");
    }

    // --- what is compared

    @Test
    void theAdminApiAddressIsTaken() throws Exception {
        PackageDraft shop = listening(listening(pkg("shop"), "orders", port(ADMIN_PORT)), "audit", port(ADMIN_PORT + 1));

        assertEquals(List.of(line("orders", "orders-in", "127.0.0.1:" + ADMIN_PORT, "127.0.0.1:" + ADMIN_PORT, ADMIN)), refused(deployments, shop).problems(),
                "the node's admin listener (FLOTTILA_ADMIN_HOST, 127.0.0.1 by default, and FLOTTILA_ADMIN_PORT) holds its port, and only that one");

        Map<String, String> env = env("node-2");
        env.put("FLOTTILA_ADMIN_HOST", "::");
        env.put("FLOTTILA_ADMIN_TOKEN", "a-token-for-the-wildcard");
        Deployments wildcard = node(env);

        assertEquals(List.of(line("orders", "orders-in", "[::1]:" + ADMIN_PORT, "[::]:" + ADMIN_PORT, ADMIN)),
                refused(wildcard, listening(pkg("shop"), "orders", on("::1", ADMIN_PORT))).problems(),
                "an admin API on the wildcard :: overlaps any host on its port; both hosts as configured, IPv6 in brackets");
        assertEquals(List.of(), consumersOf(doors, "shop"), "no consumer was built: " + doors.events());
    }

    @Test
    void anEphemeralPortIsNeverCompared() throws Exception {
        Map<String, String> env = env("node-0");
        env.put("FLOTTILA_ADMIN_PORT", "0");
        Deployments ephemeral = node(env);

        deploy(ephemeral, listening(listening(pkg("shop"), "orders", port(0)), "audit", port(0)));
        deploy(ephemeral, listening(pkg("billing"), "invoices", on("0.0.0.0", 0)));

        assertEquals(Map.of("billing/invoices", FLOW_RUNNING, "shop/audit", FLOW_RUNNING, "shop/orders", FLOW_RUNNING), flows(ephemeral),
                "port 0 (any free port) is never compared: not with the same package, another package or an admin API on port 0");
        assertEquals(List.of(line("y", "y-in", "127.0.0.1:7070", "127.0.0.1:7070", flowOf("x", "x-in"))),
                refused(ephemeral, listening(listening(pkg("other"), "x", port(7070)), "y", port(7070))).problems(),
                "the check runs on this node: a real port is compared");
    }

    @Test
    void theEnvironmentOverlaidPortIsCompared() throws Exception {
        PackageDraft billing = listening(pkg("billing"), "invoices", port(8080));
        billing.endpointDoc("invoices-in").put("environments", new JsonObject()
                .put("dev", new JsonObject().put("config", new JsonObject().put("port", 9090)))
                .put("prod", new JsonObject().put("config", new JsonObject().put("port", 7070))));
        deploy(deployments, billing);   // the node is dev: invoices listens on 9090
        PackageDraft shop = listening(pkg("shop"), "orders", port(7070));
        shop.endpointDoc("orders-in").put("environments", new JsonObject().put("dev", new JsonObject().put("config", new JsonObject().put("port", 9090))));

        assertEquals(List.of(line("orders", "orders-in", "127.0.0.1:9090", "127.0.0.1:9090", flowOf("billing/invoices", "invoices-in"))),
                refused(deployments, shop).problems(), "both sides are compared as this node's environment lays its keys over the config");

        deploy(deployments, listening(listening(pkg("shop"), "orders", port(8080)), "audit", port(7070)));

        assertEquals(Map.of("billing/invoices", FLOW_RUNNING, "shop/audit", FLOW_RUNNING, "shop/orders", FLOW_RUNNING), flows(deployments),
                "billing's base port and its prod port are free on a dev node");
    }

    @Test
    void anEndpointConfigItsSchemaRefusesIsNotAskedWhereItListens() throws Exception {
        deploy(deployments, listening(pkg("billing"), "invoices", port(8080)));
        PackageDraft shop = listening(listening(pkg("shop"), "orders", port(8080).put("level", "wrong")), "audit", port(8080));

        DeployRefusedException r = refused(deployments, shop);

        List<String> listeners = r.problems().stream().filter(l -> l.endsWith("(D-072)")).toList();
        List<String> others = r.problems().stream().filter(l -> !l.endsWith("(D-072)")).toList();
        assertEquals(List.of(line("audit", "audit-in", "127.0.0.1:8080", "127.0.0.1:8080", flowOf("billing/invoices", "invoices-in"))), listeners,
                "the valid endpoint is compared, with the other problems; the one its schema refuses is not");
        assertFalse(others.isEmpty(), "fixture: orders-in's config is refused: " + r.problems());
        assertTrue(others.stream().allMatch(l -> l.startsWith("endpoint 'orders-in' config (environment dev)")), "only orders-in's schema problems: " + others);
        assertFalse(doors.listensOnAsked().contains("orders-in"), "a config its schema refuses is never asked: " + doors.listensOnAsked());
    }

    @Test
    void aListensOnThatThrowsIsAProblemLine() throws Exception {
        PackageDraft shop = listening(listening(pkg("shop"), "orders", new JsonObject().put("path", "/in").put("listenRefuse", "no address on this host")),
                "audit", port(7071));

        DeployRefusedException r = refused(deployments, shop);

        assertEquals(1, r.problems().size(), "one line: " + r.problems());
        String line = r.problems().getFirst();
        assertTrue(line.contains("'orders-in'") && Pattern.compile("\\b" + Doors.SCHEME + "\\b").matcher(line).find() && line.contains("no address on this host"),
                "the line names the endpoint, its scheme and what the component threw: " + line);
        assertEquals(List.of(), doors.events(), "no consumer was built");
        assertEquals(List.of(), doors.producers(), "nothing was built");
    }

    // --- FAILED holds nothing; a restore checks too

    @Test
    void aFailedFlowOrFailedPackageHoldsNoPort() throws Exception {
        InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
        DirArchiveStore bucket = new DirArchiveStore(tmp.resolve("bucket"));
        Deployments first = node(env("node-a"), rows, bucket, new Doors());
        deploy(first, listening(listening(pkg("alpha"), "a1", port(8080)), "a2", port(8082)));
        String omega = deploy(first, listening(pkg("omega"), "w", port(8083))).pkg().archiveSha256();
        Doors restoring = new Doors();
        restoring.failStartOf("alpha/a1", "the door is stuck");
        DeploymentsRestoreTest.Fetches fetches = new DeploymentsRestoreTest.Fetches(bucket);
        fetches.failAfterWriting(omega, "connection reset");
        Deployments restored = node(env("node-b"), rows, fetches, restoring);
        OnVirtualThread.call(() -> { restored.restore(); return null; });
        assertEquals(Map.of("alpha", RUNNING, "omega", DeployedPackage.State.FAILED), packages(restored), "fixture: omega could not be fetched");
        assertEquals(Map.of("alpha/a1", DeployedFlow.State.FAILED, "alpha/a2", FLOW_RUNNING), flows(restored), "fixture: a1 did not start");

        assertEquals(List.of(line("d", "d-in", "127.0.0.1:8082", "127.0.0.1:8082", flowOf("alpha/a2", "a2-in"))),
                refused(restored, listening(pkg("delta"), "d", port(8082))).problems(), "the restored flow that runs holds its port");
        deploy(restored, listening(listening(pkg("gamma"), "g1", port(8080)), "g3", port(8083)));

        assertEquals(DeployedPackage.State.RUNNING, packages(restored).get("gamma"), "a1 (FAILED) and omega (a FAILED package) hold no port");
        assertTrue(restoring.door("gamma/g1").started() && restoring.door("gamma/g3").started(), restoring.events().toString());
    }

    @Test
    void aRestoredPackageClashingWithOneRestoredBeforeIsFailed() throws Exception {
        InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
        DirArchiveStore bucket = new DirArchiveStore(tmp.resolve("bucket"));
        deploy(node(env("node-a"), rows, bucket, new Doors()), listening(pkg("alpha"), "invoices", port(8080)));
        deploy(node(env("node-b"), rows, bucket, new Doors()), listening(pkg("beta"), "orders", on("0.0.0.0", 8080)));   // node b runs nothing else
        Doors third = new Doors();
        Deployments restored = node(env("node-c"), rows, bucket, third);

        OnVirtualThread.call(() -> { restored.restore(); return null; });

        assertEquals(Map.of("alpha", RUNNING, "beta", DeployedPackage.State.FAILED), packages(restored),
                "restored in name order, the later package of the clashing pair is FAILED");
        DeployedPackage beta = restored.packages().stream().filter(p -> p.name().equals("beta")).findFirst().orElseThrow();
        assertTrue(beta.problems().contains(line("orders", "orders-in", "0.0.0.0:8080", "127.0.0.1:8080", flowOf("alpha/invoices", "invoices-in"))),
                "its problems carry the line: " + beta.problems());
        assertEquals(Map.of("alpha/invoices", FLOW_RUNNING), flows(restored), "nothing of beta is listed as a flow");
        Doors.Door invoices = third.door("alpha/invoices");
        assertTrue(invoices.started() && !invoices.stopped(), "alpha runs: " + invoices.history());
        assertEquals(List.of(), consumersOf(third, "beta"), "no consumer of beta was built: " + third.events());
        Health health = restored.health();
        assertEquals(Health.Status.DOWN, health.status(), health.detail());
        assertTrue(health.detail().contains("package beta"), "/health names it: " + health.detail());
    }
}
