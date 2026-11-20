package dev.rail8.runtime.deploy;

import dev.rail8.api.Component;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static dev.rail8.runtime.deploy.DeploymentsDependenciesTest.writeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls between flows at deploy. {@code Deployments.check} asks the component of each in endpoint that feeds a flow, and of each
 * out endpoint a step of a flow sends to ({@code to.endpoint}, every {@code byProp} choice, the steps of the processes it calls),
 * which node-local address it serves or calls ({@code Component.internalAddress}), and refuses ({@link Kind#INVALID}) a flow on an
 * address another flow serves — a running flow of another package, or a flow of the same version before it in id order — and a
 * cycle of calls through a flow of the new version, a self call included. The version a deploy replaces, a FAILED package and a
 * FAILED flow serve nothing. A restore checks the same: a package that closes a cycle is FAILED. Once a deploy has replaced the
 * listing, each call of its flows that no flow on the node serves, and each call of another package's flow to an address its
 * replaced version served and no flow serves now, is a WARNING; once a restore is done, each such call of every flow is.
 *
 * <p>Every endpoint is a {@link Doors} one of the scheme {@value Doors#SCHEME}: its {@code internalAddress} answers the endpoint's
 * {@code address} option, and records every call.
 */
class DeploymentsCallsTest {

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final List<Deployments> nodes = new CopyOnWriteArrayList<>();
    final List<Doors> components = new CopyOnWriteArrayList<>();
    final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    /** Strongly held: JUL keeps loggers weakly, and a collected logger loses its handler. */
    final Logger rail8 = Logger.getLogger("dev.rail8");
    final Handler capture = new Handler() {
        @Override public void publish(LogRecord r) { logged.add(r); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    Deployments deployments;
    GitRepos repos;

    @BeforeEach
    void wire() {
        capture.setLevel(Level.ALL);
        rail8.addHandler(capture);
        repos = new GitRepos(tmp.resolve("repos"));
        deployments = node(env("node-1"), new InMemoryDeploymentStore(), doors);
    }

    @AfterEach
    void closeEveryNode() throws Exception {
        try {
            for (Doors d : components) d.releaseAll();
            for (Deployments d : nodes) OnVirtualThread.call(() -> { d.close(); return null; });
        } finally {
            rail8.removeHandler(capture);
        }
    }

    // --- fixture

    Map<String, String> env(String nodeId) {
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", nodeId);
        env.put("RAIL8_DATA_DIR", tmp.resolve(nodeId).toString());
        env.put("RAIL8_STOP_DEADLINE", "PT5S");
        env.put("RAIL8_BLOB_DURABLE_MAX", "4096");
        env.put("RAIL8_ADMIN_PORT", "8181");
        env.put("RAIL8_GIT_BASE", repos.base().toString());
        return env;
    }

    Deployments node(Map<String, String> env, DeploymentStore rows, Doors serving) {
        components.add(serving);
        Map<String, Component> byScheme = Map.of(serving.scheme(), serving);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), byScheme);
        Deployments d = new Deployments(NodeConfig.from(env), runtime, byScheme, rt.clock(), rt.timer(), List.of(), List.of(), new InMemoryMessageStore(),
                new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L, rows);
        nodes.add(d);
        return d;
    }

    /** {@code name} 1.0.0 with the out endpoint {@code partner}, which names no node-local address. */
    static PackageDraft pkg(String name) {
        return PackageDraft.of(name, "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
    }

    /** Adds the out endpoint {@code id} that calls {@code address}. */
    static PackageDraft callee(PackageDraft p, String id, String address) {
        return p.endpoint(id, "out", new JsonObject().put("url", "rec://" + id).put("address", address));
    }

    /**
     * Adds the sync flow {@code flow} from its own in endpoint {@code <flow>-in}, which serves {@code serves} (none when null), its
     * steps a {@code to} each of {@code sends} in order, or {@code partner} when none is given.
     */
    static PackageDraft flow(PackageDraft p, String flow, String serves, String... sends) {
        JsonObject in = new JsonObject().put("path", "/in");
        if (serves != null) in.put("address", serves);
        JsonArray steps = new JsonArray();
        for (String endpoint : sends.length == 0 ? new String[] {"partner"} : sends) {
            steps.add(new JsonObject().put("type", "to").put("name", "send-" + endpoint).put("endpoint", endpoint));
        }
        return p.endpoint(flow + "-in", "in", in).flow(flow, "1.0.0", "sync", flow + "-in", 1024, steps);
    }

    static String clash(String flow, String endpoint, String address, String holder, String holderEndpoint) {
        return "flow '" + flow + "' (endpoint '" + endpoint + "') serves rec " + address + ", which flow '" + holder + "' (endpoint '" + holderEndpoint
                + "') serves: one flow per address on a node";
    }

    static String unserved(String flow, String address) {
        return "flow '" + flow + "' calls rec " + address + ", which no flow on this node serves";
    }

    /** Commits {@code p} to the repository of its name and deploys that commit on {@code d}, which writes its record. */
    DeployResult deployCommitted(Deployments d, PackageDraft p) throws Exception {
        String commit = repos.commit(p);
        return OnVirtualThread.call(() -> d.deploy(p.name(), commit, "test"));
    }

    DeployResult deploy(Deployments d, PackageDraft p) throws Exception {
        Path folder = p.folderIn(tmp.resolve("folders"));
        return OnVirtualThread.call(() -> d.deploy(p.name(), folder));
    }

    List<String> refused(Deployments d, PackageDraft p) {
        Path folder = p.folderIn(tmp.resolve("folders"));
        DeployRefusedException r = assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> d.deploy(p.name(), folder)),
                "the deploy of " + p.name() + " is refused");
        assertEquals(Kind.INVALID, r.kind(), "a 422: " + r.error() + " " + r.problems());
        return r.problems();
    }

    /** Every WARNING about a call no flow serves, in the order logged. */
    List<String> warnings() {
        return logged.stream().filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains("which no flow on this node serves"))
                .map(LogRecord::getMessage).toList();
    }

    static Map<String, DeployedPackage.State> packages(Deployments d) {
        Map<String, DeployedPackage.State> m = new TreeMap<>();
        d.packages().forEach(p -> m.put(p.name(), p.state()));
        return m;
    }

    static List<String> consumersOf(Doors d, String pkg) {
        return d.events().stream().filter(e -> e.startsWith("consumer " + pkg + "/")).toList();
    }

    // --- one flow per address

    @Test
    void aSecondFlowOnAnAddressIs422NamingTheFlowThatServesIt() throws Exception {
        deploy(deployments, flow(pkg("billing"), "invoices", "/billing"));

        assertEquals(List.of(clash("orders", "orders-in", "/billing", "billing/invoices", "invoices-in")),
                refused(deployments, flow(pkg("shop"), "orders", "/billing")), "the new flow by its local id, the running one qualified");
        assertEquals(List.of(clash("b", "b-in", "/x", "a", "a-in")), refused(deployments, flow(flow(pkg("shop"), "b", "/x"), "a", "/x")),
                "two flows of one version: the line goes on the later id");
        assertEquals(Map.of("billing", DeployedPackage.State.RUNNING), packages(deployments), "billing runs on, alone");
        assertEquals(List.of(), consumersOf(doors, "shop"), "nothing of shop was built: " + doors.events());
    }

    @Test
    void aRedeployKeepsItsAddressAndAnAddressItFreesCanBeTaken() throws Exception {
        PackageDraft billing = flow(pkg("billing"), "invoices", "/billing");
        deploy(deployments, billing);
        billing.flowDoc("invoices").getJsonObject("limits").put("maxBodyBytes", 2048);

        deploy(deployments, billing);   // the version it replaces serves nothing
        billing.endpointDoc("invoices-in").getJsonObject("config").put("address", "/billing-2");
        deploy(deployments, billing);
        deploy(deployments, flow(pkg("shop"), "orders", "/billing"));

        assertEquals(Map.of("billing", DeployedPackage.State.RUNNING, "shop", DeployedPackage.State.RUNNING), packages(deployments));
    }

    // --- no cycle

    @Test
    void aCycleThroughAFlowOfAnotherPackageIs422NamingThePath() throws Exception {
        deploy(deployments, flow(callee(pkg("billing"), "orders", "/orders"), "charge", "/charge", "orders"));

        assertEquals(List.of("calls between flows would form a cycle: flow 'orders' -> rec /charge -> flow 'billing/charge' -> rec /orders -> flow 'orders'"),
                refused(deployments, flow(callee(pkg("shop"), "charge", "/charge"), "orders", "/orders", "charge")));
        assertEquals(List.of(), consumersOf(doors, "shop"), "nothing of shop was built: " + doors.events());
    }

    @Test
    void aFlowCallingTheAddressItServesIs422() {
        assertEquals(List.of("calls between flows would form a cycle: flow 'echo' -> rec /echo -> flow 'echo'"),
                refused(deployments, flow(callee(pkg("shop"), "again", "/echo"), "echo", "/echo", "again")));
    }

    @Test
    void aByPropChoiceAndAStepOfAProcessAreCalls() throws Exception {
        PackageDraft billing = flow(callee(pkg("billing"), "orders", "/orders"), "charge", "/charge");
        billing.flowDoc("charge").put("steps", new JsonArray().add(new JsonObject().put("type", "to").put("name", "route").put("byProp", "region")
                .put("endpoints", new JsonObject().put("eu", "partner").put("us", "orders"))));
        deploy(deployments, billing);
        PackageDraft shop = flow(callee(pkg("shop"), "charge", "/charge"), "orders", "/orders");
        shop.flowDoc("orders").put("steps", new JsonArray().add(new JsonObject().put("type", "process").put("name", "pay").put("process", "bill")));
        shop.flowDoc("orders").put("processes", new JsonObject().put("bill", new JsonObject().put("steps", new JsonArray()
                .add(new JsonObject().put("type", "to").put("name", "send").put("endpoint", "charge")))));

        assertEquals(List.of("calls between flows would form a cycle: flow 'orders' -> rec /charge -> flow 'billing/charge' -> rec /orders -> flow 'orders'"),
                refused(deployments, shop), "billing's call is a byProp choice, shop's a step of the process its flow calls");
    }

    // --- asking the components

    @Test
    void anEndpointWhoseComponentCannotNameItsAddressIsALineAndEachEndpointIsAskedOnce() {
        PackageDraft shop = callee(callee(pkg("shop"), "shared", "/x"), "unused", "/y");
        flow(shop, "a", null, "shared");
        flow(shop, "b", null, "shared");
        flow(shop, "c", null);
        shop.endpointDoc("c-in").getJsonObject("config").put("addressRefuse", "no address here");
        flow(shop, "d", null);
        shop.endpointDoc("d-in").getJsonObject("config").put("addressNull", true);
        shop.endpoint("bad", "out", new JsonObject().put("level", "middle").put("address", "/z"));   // its config is refused
        shop.flowDoc("a").getJsonArray("steps").add(new JsonObject().put("type", "to").put("name", "spoilt").put("endpoint", "bad"));

        List<String> problems = refused(deployments, shop);

        assertTrue(problems.contains("endpoint 'c-in' (scheme 'rec'): its component cannot say which node-local address it serves or calls: no address here"),
                problems.toString());
        assertTrue(problems.contains("endpoint 'd-in' (scheme 'rec'): its component cannot say which node-local address it serves or calls: "
                + "internalAddress answered null"), problems.toString());
        assertEquals(List.of(), problems.stream().filter(l -> !l.startsWith("endpoint 'bad' config") && !l.contains("node-local address")).toList(),
                "besides the bad endpoint's config, one line for each answer that failed: " + problems);
        List<String> asked = doors.internalAddressAsked();
        assertEquals(1, asked.stream().filter("shared"::equals).count(), "an endpoint two flows call is asked once: " + asked);
        assertFalse(asked.contains("unused"), "an out endpoint no step sends to is never asked: " + asked);
        assertFalse(asked.contains("bad"), "an endpoint whose config is refused is never asked: " + asked);
    }

    // --- the WARNING

    @Test
    void aDeployWarnsOfItsCallsNoFlowServesAndOfCallsItLeavesUnserved() throws Exception {
        deploy(deployments, flow(callee(pkg("shop"), "billing", "/billing"), "orders", null, "billing"));
        assertEquals(List.of(unserved("shop/orders", "/billing")), warnings(), "its own call that no flow serves");

        deploy(deployments, flow(pkg("audit"), "trail", "/audit"));
        assertEquals(1, warnings().size(), "a deploy that serves and calls nothing shop calls warns of nothing: " + warnings());

        PackageDraft billing = flow(pkg("billing"), "invoices", "/billing");
        deploy(deployments, billing);
        assertEquals(1, warnings().size(), "a flow serves it now: " + warnings());

        billing.endpointDoc("invoices-in").getJsonObject("config").put("address", "/billing-2");
        deploy(deployments, billing);
        assertEquals(List.of(unserved("shop/orders", "/billing"), unserved("shop/orders", "/billing")), warnings(),
                "billing's new version no longer serves the address shop calls");
    }

    @Test
    void aRefusedDeployAndAValidateWarnOfNothing() throws Exception {
        PackageDraft shop = flow(flow(callee(pkg("shop"), "billing", "/billing"), "orders", "/orders", "billing"), "refunds", "/orders");

        refused(deployments, shop);
        Path dir = tmp.resolve("checkout");
        writeTo(dir, flow(callee(pkg("shop"), "billing", "/billing"), "orders", null, "billing"));
        assertEquals(List.of(), OnVirtualThread.call(() -> deployments.validate("shop", dir)), "the validate finds no problem");

        assertEquals(List.of(), warnings());
    }

    // --- restore

    @Test
    void aRestoredPackageThatClosesACycleIsFailedAndOneWarningNamesTheCallLeftUnserved() throws Exception {
        InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
        deployCommitted(node(env("node-a"), rows, new Doors()), flow(callee(pkg("alpha"), "beta", "/b"), "a", "/a", "beta"));
        deployCommitted(node(env("node-b"), rows, new Doors()), flow(callee(pkg("beta"), "alpha", "/a"), "b", "/b", "alpha"));
        logged.clear();
        Doors third = new Doors();
        Deployments restored = node(env("node-c"), rows, third);

        OnVirtualThread.call(() -> { restored.restore(); return null; });

        assertEquals(Map.of("alpha", DeployedPackage.State.RUNNING, "beta", DeployedPackage.State.FAILED), packages(restored),
                "restored in name order, beta closes the cycle");
        DeployedPackage beta = restored.packages().stream().filter(p -> p.name().equals("beta")).findFirst().orElseThrow();
        assertTrue(beta.problems().contains("calls between flows would form a cycle: flow 'b' -> rec /a -> flow 'alpha/a' -> rec /b -> flow 'b'"),
                beta.problems().toString());
        assertEquals(List.of(), consumersOf(third, "beta"), "no consumer of beta was built: " + third.events());
        assertEquals(List.of(unserved("alpha/a", "/b")), warnings(), "once the restore is done, alpha's call no flow serves: beta is FAILED");

        deploy(restored, flow(pkg("gamma"), "g", "/b"));   // a FAILED package serves nothing
        assertEquals(DeployedPackage.State.RUNNING, packages(restored).get("gamma"));
    }

    // --- off-node validate

    @Test
    void anOffNodeValidateCountsTheFlowsOfTheHeldPackagesInPlaceOfTheNodesVersions() throws Exception {
        deploy(deployments, flow(PackageDraft.of("billing", "0.9.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner")),
                "charge", "/orders"));   // the node's billing serves the address shop serves: the held billing stands in for it
        PackageDraft billing = flow(callee(pkg("billing"), "orders", "/orders"), "charge", "/charge", "orders");
        Path held = tmp.resolve("billing-folder");
        writeTo(held, billing);
        Path dir = tmp.resolve("shop-checkout");
        writeTo(dir, flow(callee(pkg("shop"), "charge", "/charge"), "orders", "/orders", "charge").dependingOn(billing));

        List<String> problems = OnVirtualThread.call(() -> deployments.validate("shop", dir, List.of(held)));

        assertEquals(List.of("calls between flows would form a cycle: flow 'orders' -> rec /charge -> flow 'billing/charge' -> rec /orders -> flow 'orders'"),
                problems);
        assertEquals(List.of(), warnings(), "a validate warns of nothing");
    }
}
