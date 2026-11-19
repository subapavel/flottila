package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Component;
import dev.rail8.api.Consumer;
import dev.rail8.api.EndpointConfig;
import dev.rail8.api.Envelope;
import dev.rail8.api.Health;
import dev.rail8.api.Producer;
import dev.rail8.api.Runtime;
import dev.rail8.api.plugin.HttpIngress;
import dev.rail8.api.settings.Settings;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

import static dev.rail8.runtime.deploy.DeploymentsListenerTest.listening;
import static dev.rail8.runtime.deploy.DeploymentsListenerTest.pkg;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One flow per path on the node's HTTP port. The deploy computes, for every in endpoint that feeds a flow and whose component
 * declares a prefix, the path its consumer would mount: the prefix, then the endpoint's {@code address}. It refuses, one line each
 * and before anything is built or stopped, a path equal to that of another flow of the same new version (once per pair, on the
 * later flow id) or of a running flow of another package. The version a deploy replaces holds no path: a redeploy keeps its own
 * paths and frees the ones it leaves. {@link Deployments#serves} answers whether a running flow takes requests at a path.
 *
 * <p>Every in endpoint here is of {@link Posts}, the scheme {@value Posts#SCHEME} with the prefix {@value Posts#PREFIX}; every flow
 * sends to the out endpoint {@code partner} of {@link Doors}. The node has one secret, {@code secret://orders-path}, set to
 * {@code /orders}.
 */
class DeploymentsHttpPathTest {

    static final DeployedFlow.State FLOW_RUNNING = DeployedFlow.State.RUNNING;

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final Posts posts = new Posts();
    Deployments deployments;

    @BeforeEach
    void wire() {
        Map<String, Component> byScheme = Map.of(Doors.SCHEME, doors, Posts.SCHEME, posts);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), byScheme);
        Map<String, String> env = Map.of("RAIL8_ENV", "dev", "RAIL8_NODE_ID", "node-1", "RAIL8_DATA_DIR", tmp.resolve("node-1").toString(),
                "RAIL8_BLOB_DURABLE_MAX", "4096", "RAIL8_SECRET_ORDERS_PATH", "/orders");
        deployments = new Deployments(NodeConfig.from(env), runtime, byScheme, rt.clock(), rt.timer(), List.of(), List.of(),
                new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L, new InMemoryDeploymentStore(),
                new DirArchiveStore(tmp.resolve("archives")));
    }

    @AfterEach
    void close() throws Exception {
        doors.releaseAll();
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    /** Adds the flow {@code flow} to {@code p}, from its own in endpoint {@code <flow>-in} of {@value Posts#SCHEME} at {@code address}. */
    static PackageDraft at(PackageDraft p, String flow, String address) {
        return listening(p, flow, Posts.SCHEME, new JsonObject().put("address", address));
    }

    /** The refusal line: {@code flow} of {@code endpoint} at {@code path}, which {@code holder} takes requests at already. */
    static String line(String flow, String endpoint, String path, String holder) {
        return "flow '" + flow + "' (endpoint '" + endpoint + "') takes requests at " + path + " on the node's HTTP port, where " + holder
                + " takes them: one flow per path";
    }

    static String flowOf(String flow, String endpoint) {
        return "flow '" + flow + "' (endpoint '" + endpoint + "')";
    }

    DeployResult deploy(PackageDraft p) throws Exception {
        Path archive = p.zipTo(tmp.resolve("uploads"));
        return OnVirtualThread.call(() -> deployments.deploy(p.manifest().getString("name"), Optional.empty(), archive));
    }

    List<String> refused(PackageDraft p) {
        Path archive = p.zipTo(tmp.resolve("uploads"));
        String name = p.manifest().getString("name");
        DeployRefusedException r = assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> deployments.deploy(name, Optional.empty(), archive)), "the deploy of " + name + " is refused");
        assertEquals(Kind.INVALID, r.kind(), "a 422: " + r.error() + " " + r.problems());
        return r.problems().stream().sorted().toList();
    }

    Map<String, DeployedFlow.State> flows() {
        Map<String, DeployedFlow.State> m = new TreeMap<>();
        deployments.flows().forEach(f -> m.put(f.id(), f.state()));
        return m;
    }

    @Test
    void twoFlowsOfOnePackageAtOnePathAre422() throws Exception {
        PackageDraft shop = pkg("shop");
        at(shop, "audit", "/orders");
        at(shop, "orders", "/orders");
        at(shop, "refunds", "/orders");
        at(shop, "ping", "/Orders");

        assertEquals(List.of(line("orders", "orders-in", "/post/orders", flowOf("audit", "audit-in")),
                        line("refunds", "refunds-in", "/post/orders", flowOf("audit", "audit-in"))),
                refused(shop), "a line per later flow, naming the first flow at the path by its local id; a path is case-sensitive");
        assertEquals(List.of(), deployments.packages(), "nothing of it is listed");
        assertEquals(0, posts.consumers, "no consumer was built");
    }

    @Test
    void anAddressWrittenAsASecretReferenceIs422() throws Exception {
        assertEquals(List.of("endpoint 'orders-in' (scheme 'post'): its address is written as a secret reference; an address is the path "
                        + "senders call, written as it is"),
                refused(at(pkg("shop"), "orders", "secret://orders-path")), "a path is no secret: validate and the deploy see the same one");
        assertEquals(0, posts.consumers, "no consumer was built");
    }

    @Test
    void aSecondPackageAtTheFirstsPathIs422() throws Exception {
        deploy(at(pkg("billing"), "invoices", "/orders"));

        assertEquals(List.of(line("orders", "orders-in", "/post/orders", flowOf("billing/invoices", "invoices-in"))),
                refused(at(at(pkg("shop"), "orders", "/orders"), "audit", "/audit")),
                "the running flow of another package is named qualified; the other path is free");
        assertEquals(Map.of("billing/invoices", FLOW_RUNNING), flows(), "the first package runs on, alone");
        assertEquals(1, posts.consumers, "only billing's consumer was built");
    }

    @Test
    void aRedeployKeepsItsOwnPathsAndFreesThoseItLeaves() throws Exception {
        PackageDraft billing = at(at(pkg("billing"), "invoices", "/orders"), "credits", "/credits");
        deploy(billing);
        assertTrue(deployments.serves("/post/orders") && deployments.serves("/post/credits"), "both paths are served");

        billing.flowDoc("invoices").getJsonObject("limits").put("maxBodyBytes", 2048);   // changed, at its path
        billing.endpointDoc("credits-in").put("config", new JsonObject().put("address", "/refunds"));   // moved
        deploy(billing);

        assertTrue(deployments.serves("/post/orders"), "the changed flow keeps its path");
        assertTrue(deployments.serves("/post/refunds"), "the moved flow is served at its new path");
        assertFalse(deployments.serves("/post/credits"), "the path it left is no flow's");
        assertFalse(deployments.serves("/orders"), "a path is the prefix and the address");
        deploy(at(pkg("shop"), "credits", "/credits"));
        assertEquals(Map.of("billing/credits", FLOW_RUNNING, "billing/invoices", FLOW_RUNNING, "shop/credits", FLOW_RUNNING), flows(),
                "another package takes the path billing left");
    }

    @Test
    void anUndeployFreesItsPaths() throws Exception {
        deploy(at(pkg("billing"), "invoices", "/orders"));

        OnVirtualThread.call(() -> deployments.undeploy("billing"));

        assertFalse(deployments.serves("/post/orders"), "an undeployed flow serves nothing");
        deploy(at(pkg("shop"), "orders", "/orders"));
        assertTrue(deployments.serves("/post/orders"), "the path is free for another package");
    }

    /**
     * The scheme {@value #SCHEME}: the prefix {@value #PREFIX}, the in field {@code address} and nothing else. Its consumers start
     * and stop at once and take no requests: nothing is mounted. It counts the consumers built.
     */
    static final class Posts implements Component {

        static final String SCHEME = "post";
        static final String PREFIX = "/post";

        volatile int consumers;

        @Override public String scheme() { return SCHEME; }

        @Override public Settings settings() { return Settings.of(HttpIngress.ADDRESS); }

        @Override public Optional<String> httpPrefix() { return Optional.of(PREFIX); }

        @Override
        public synchronized Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) {
            consumers++;
            return new Consumer() {
                @Override public Future<Void> start() { return Future.succeededFuture(); }
                @Override public Future<Void> stop() { return Future.succeededFuture(); }
            };
        }

        @Override public Producer producer(EndpointConfig cfg) { throw new UnsupportedOperationException("an in-only scheme"); }

        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }
}
