package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.api.Stage;
import io.flottila.api.StepConfig;
import io.flottila.api.StepFactory;
import io.flottila.api.StepResources;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09d1b, implementer: what the deploy cases leave open — an empty {@code dependencies} list, a stop that fails while a
 * refused deploy is undone, an option a loose component's schema lets be null, and a step failure that names a package file
 * by its absolute path.
 */
class DeploymentsEdgeTest {

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final List<Deployments> made = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        doors.releaseAll();
        for (Deployments d : made) OnVirtualThread.call(() -> { d.close(); return null; });
    }

    Deployments deployments(Map<String, Component> components, List<StepFactory> steps) {
        Map<String, String> env = Map.of("FLOTTILA_ENV", "dev", "FLOTTILA_NODE_ID", "node-1", "FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        Deployments d = new Deployments(NodeConfig.from(env), runtime, components, rt.clock(), rt.timer(), steps, List.of(), new InMemoryMessageStore(), new InMemoryDlq(),
                new InMemoryWorkQueue(rt.clock()), () -> 0L);
        made.add(d);
        return d;
    }

    Deployments deployments() {
        return deployments(Map.of(Doors.SCHEME, doors), List.of());
    }

    /** {@code shop} 1.0.0: a sync flow per id, each from its own in endpoint, each to {@code partner}. */
    static PackageDraft shop(String... flows) {
        PackageDraft p = PackageDraft.of("shop", "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : flows) p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 1024, PackageDraft.toPartner());
        return p;
    }

    DeployedPackage deploy(Deployments d, PackageDraft p) throws Exception {
        Path archive = p.zipTo(tmp.resolve("archives"));
        return OnVirtualThread.call(() -> d.deploy("shop", Optional.empty(), archive)).pkg();
    }

    DeployRefusedException refused(Deployments d, PackageDraft p) {
        return refused(d, p.zipTo(tmp.resolve("archives")));
    }

    static DeployRefusedException refused(Deployments d, Path archive) {
        return assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> d.deploy("shop", Optional.empty(), archive)));
    }

    @Test
    void anEmptyDependenciesListDeploys() throws Exception {
        PackageDraft p = shop("orders");
        p.manifest().put("dependencies", new JsonArray());

        DeployedPackage deployed = deploy(deployments(), p);

        assertEquals(List.of("shop/orders"), deployed.flows(), "no dependency is no dependency, listed or not");
    }

    @Test
    void aStopThatFailsWhileARefusedDeployIsUndoneKeepsTheRefusal() throws Exception {
        Deployments d = deployments();
        doors.failStop("shop/a", "socket stuck");
        doors.failStart(2, "address in use");

        Path archive = shop("a", "b").zipTo(tmp.resolve("archives"));

        DeployRefusedException refused = refused(d, archive);

        assertEquals(Kind.FAILED, refused.kind(), refused.getMessage());
        assertEquals("flow 'shop/b' did not start: address in use", refused.error(), "the answer is the start that failed, not the stop");
        assertTrue(doors.door("shop/a").stopped(), "the flow that started was stopped (its stop failed)");
        assertEquals(List.of(), d.flows());
        assertFalse(Files.exists(tmp.resolve("data").resolve("packages").resolve("shop").resolve(PackageDraft.sha256(archive))), "no package directory is left");
        assertEquals(List.of("shop/a", "shop/b"), deploy(deployments(Map.of(Doors.SCHEME, new Doors()), List.of()), shop("a", "b")).flows(),
                "fixture: the same package deploys where its consumers start and stop");
    }

    /** A flow its schema refuses is listed by the schema's lines only: no definition is read from it, so no second line is added for it. */
    @Test
    void aFlowItsSchemaRefusesIsNotReadAsADefinition() throws Exception {
        PackageDraft p = shop("orders", "audit");
        p.flowDoc("orders").remove("from");

        DeployRefusedException refused = refused(deployments(), p);

        assertEquals(Kind.INVALID, refused.kind(), refused.getMessage());
        assertFalse(refused.problems().isEmpty());
        assertTrue(refused.problems().stream().allMatch(l -> l.startsWith("flows/orders.yaml at ")), "the flow schema's lines, nothing else: " + refused.problems());
        assertEquals(List.of(), doors.events(), "nothing was built");
    }

    /** An endpoint its schema refuses is listed by the schema's lines only: nothing is read from it, its config least of all. */
    @Test
    void anEndpointItsSchemaRefusesIsNotReadAsAConfig() throws Exception {
        PackageDraft p = shop("orders");
        p.endpointDoc("orders-in").put("config", "path=/orders");

        DeployRefusedException refused = refused(deployments(), p);

        assertEquals(Kind.INVALID, refused.kind(), refused.getMessage());
        assertFalse(refused.problems().isEmpty());
        assertTrue(refused.problems().stream().allMatch(l -> l.startsWith("endpoints/orders-in.yaml at ")), "the endpoint schema's lines, nothing else: " + refused.problems());
        assertEquals(List.of(), doors.events(), "nothing was built");
    }

    /** A refused deploy deletes packages/<name>/ only when it is empty: what an earlier node left there for another archive stays. */
    @Test
    void aRefusedDeployKeepsWhatAnEarlierNodeLeftBesideIt() throws Exception {
        Path leftover = tmp.resolve("data").resolve("packages").resolve("shop").resolve("0".repeat(64)).resolve("package.yaml");
        Files.createDirectories(leftover.getParent());
        Files.writeString(leftover, "left by an earlier node");
        PackageDraft p = shop("orders");
        p.manifest().put("dependencies", new JsonArray().add(new JsonObject().put("name", "common").put("version", "1.0.0")));

        DeployRefusedException refused = refused(deployments(), p);

        assertEquals(Kind.INVALID, refused.kind(), refused.getMessage());
        assertEquals("left by an earlier node", Files.readString(leftover), "the other archive's directory is untouched");
        try (var left = Files.list(leftover.getParent().getParent())) {
            assertEquals(List.of(leftover.getParent()), left.toList(), "only the leftover is in packages/shop/: the refused archive's directory is gone");
        }
    }

    /** A component whose schema takes any option, null included; its consumers and producers are the {@link Doors}' own. */
    record Loose(Doors doors) implements Component {
        @Override public String scheme() { return "loose"; }
        @Override public String configSchema() { return "{\"type\":\"object\"}"; }
        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return doors.consumer(cfg, handler); }
        @Override public Producer producer(EndpointConfig cfg) { return doors.producer(cfg); }
        @Override public Future<Health> health() { return Future.succeededFuture(Health.up()); }
    }

    @Test
    void anOptionSetToNullIsAnAbsentOne() throws Exception {
        Deployments d = deployments(Map.of(Doors.SCHEME, doors, "loose", new Loose(doors)), List.of());
        PackageDraft p = PackageDraft.of("shop", "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .endpoint("orders-in", "loose", "in", new JsonObject().put("path", "/orders").putNull("proxy"))
                .flow("orders", "1.0.0", "sync", "orders-in", 1024, PackageDraft.toPartner());

        deploy(d, p);

        Map<String, Object> options = doors.door("shop/orders").cfg().options();
        assertEquals("/orders", options.get("path"));
        assertFalse(options.containsKey("proxy"), "a null option is not handed to the component: " + options);
    }

    /** A step type whose build fails naming the file it reads by its absolute path, as a step that reads a stylesheet may. */
    record Reads() implements StepFactory {
        @Override public String type() { return "reads"; }
        @Override public String configSchema() { return "{\"type\":\"object\"}"; }
        @Override public Stage create(StepConfig step, StepResources resources) {
            throw new IllegalArgumentException(step + ": cannot read " + resources.file(step.requireString("file")));
        }
    }

    @Test
    void aStepFailureNamingAPackageFileNamesItFromThePackageRoot() throws Exception {
        Deployments d = deployments(Map.of(Doors.SCHEME, doors), List.of(new Reads()));
        PackageDraft p = shop("orders");
        p.flowDoc("orders").put("steps", new JsonArray().add(new JsonObject().put("type", "reads").put("name", "r").put("file", "maps/order.xsl")));

        DeployRefusedException refused = refused(d, p);

        assertEquals(Kind.INVALID, refused.kind(), refused.getMessage());
        assertEquals(List.of("flow 'orders' does not build: step 'r' (steps[0], reads): cannot read flows/maps/order.xsl"), refused.problems());
    }
}
