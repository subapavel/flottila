package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Health;
import io.flottila.api.Key;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.flow.Intake;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.core.record.Snapshot;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.lease.InMemoryPollerLeases;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-02D (D-084): the deploy asks each in endpoint's component {@code dedupeBy} with the endpoint as its consumer gets it (the
 * flow's qualified id and maxBodyBytes in its options) and builds the flow's delivery with the answer, so the prop it names
 * reaches the node's intake as the save's dedupe key; a component that names none (the default) saves without a key. A
 * {@code dedupeBy} that throws is one problem line of the deploy, nothing started.
 *
 * <p>The shape assumed: {@code Component.dedupeBy(EndpointConfig)} answering {@code Optional<Key<String>>}; core {@code Intake.save(arrived,
 * ingress, flow, partitionKey, dedupeKey, abandoned)} answering {@code Future<String>}, the id the Accepted names.
 */
@Timeout(60)
class DeploymentsDedupeTest {

    static final Key<String> FILE_KEY = new Key<>("dd.fileKey");

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final Doors dedupedDoors = new Doors("dd");
    final Deduping deduping = new Deduping(dedupedDoors);
    final KeyedIntake intake = new KeyedIntake();
    Deployments deployments;

    @BeforeEach
    void wire() {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", "node-1");
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", "4096");
        NodeConfig config = NodeConfig.from(env);
        Map<String, Component> components = Map.of(Doors.SCHEME, doors, "dd", deduping);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), components);
        InMemoryMessageStore messages = new InMemoryMessageStore();
        deployments = new Deployments(config, runtime, components, rt.clock(), rt.timer(), List.of(), List.of(), messages, new InMemoryDlq(),
                new CountingQueue(rt.clock()), () -> 0L, new InMemoryDeploymentStore(), new DirArchiveStore(config.archives().dir()), intake,
                new InMemoryPollerLeases(rt.clock()));
    }

    @AfterEach
    void closeTheNode() throws Exception {
        doors.releaseAll();
        dedupedDoors.releaseAll();
        OnVirtualThread.call(() -> {
            deployments.close();
            return null;
        });
    }

    // --- fixture

    /** The {@code dd} scheme: a {@link Doors} that never polls and answers {@code dedupeBy} by {@link #answer}, every ask recorded. */
    static final class Deduping implements Component {
        final Doors inner;
        final List<EndpointConfig> asked = new CopyOnWriteArrayList<>();
        volatile Function<EndpointConfig, Optional<Key<String>>> answer = cfg -> Optional.of(FILE_KEY);

        Deduping(Doors inner) { this.inner = inner; }

        @Override public String scheme() { return inner.scheme(); }

        @Override public String configSchema() { return inner.configSchema(); }

        @Override public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return inner.consumer(cfg, handler); }

        @Override public Producer producer(EndpointConfig cfg) { return inner.producer(cfg); }

        @Override public Future<Health> health() { return inner.health(); }

        @Override public Optional<InetSocketAddress> listensOn(EndpointConfig cfg) { return inner.listensOn(cfg); }

        @Override
        public Optional<Key<String>> dedupeBy(EndpointConfig cfg) {
            asked.add(cfg);
            return answer.apply(cfg);
        }
    }

    /** The node's intake at the store boundary: records each save and answers {@link #FIRST}, as for a duplicate. */
    static final class KeyedIntake implements Intake {
        static final String FIRST = "m-first-saved";

        record Save(String id, String flow, String dedupeKey) {}

        final List<Save> saves = new CopyOnWriteArrayList<>();

        @Override
        public Future<String> save(Envelope arrived, Snapshot ingress, String flow, String partitionKey, String dedupeKey, BooleanSupplier abandoned) {
            saves.add(new Save(arrived.id(), flow, dedupeKey));
            return Future.succeededFuture(FIRST);
        }
    }

    /** {@code shop}: at-least-once flow {@code feed} from the dd endpoint {@code drop}, at-least-once flow {@code orders} from a rec one. */
    static PackageDraft shop() {
        return PackageDraft.of("shop", "1.0.0")
                .endpoint("drop", "dd", "in", new JsonObject().put("path", "/drop"))
                .endpoint("orders-in", "in", new JsonObject().put("path", "/orders"))
                .endpoint("partner", "out", new JsonObject().put("url", "rec://partner"))
                .flow("feed", "1.0.0", "at-least-once", "drop", 4096, PackageDraft.toPartner())
                .flow("orders", "1.0.0", "at-least-once", "orders-in", 4096, PackageDraft.toPartner());
    }

    void deploy(PackageDraft p) throws Exception {
        OnVirtualThread.call(() -> deployments.deploy(p.manifest().getString("name"), Optional.empty(), p.zipTo(tmp.resolve("uploads"))));
    }

    Doors.Door only(Doors on, String flow) {
        List<Doors.Door> built = on.doors().stream().filter(d -> d.flow().equals(flow)).toList();
        assertEquals(1, built.size(), "fixture: one consumer of " + flow + ": " + built);
        return built.get(0);
    }

    Ack deliver(Doors.Door door, String fileKey) throws Exception {
        Envelope in = rt.ingress(door.flow(), Headers.empty().with(Envelope.H_CONTENT_TYPE, "application/xml"), Buffer.buffer("<order/>"))
                .with(FILE_KEY, fileKey);
        return door.handler().apply(in).await(10, TimeUnit.SECONDS);
    }

    // --- cases

    @Test
    void theFlowsDedupeKeyReachesTheIntake() throws Exception {
        deploy(shop());

        assertTrue(!deduping.asked.isEmpty(), "the deploy asked the dd component about its in endpoint");
        for (EndpointConfig asked : deduping.asked) {
            assertEquals("drop", asked.name());
            assertEquals("shop/feed", asked.options().get("flow"), "asked with the endpoint as its consumer gets it: the flow's qualified id");
            assertEquals(4096, ((Number) asked.options().get("maxBodyBytes")).intValue(), "and its maxBodyBytes");
        }

        Ack ack = deliver(only(dedupedDoors, "shop/feed"), "/drop/a.xml@1730196000000#ab12");
        assertEquals(new Ack.Accepted(KeyedIntake.FIRST), ack, "the consumer is answered with the id the intake named");
        assertEquals(List.of(new KeyedIntake.Save(intake.saves.get(0).id(), "shop/feed", "/drop/a.xml@1730196000000#ab12")), intake.saves,
                "the named prop reached the intake as the save's dedupe key, scoped by the flow's qualified id");

        deliver(only(doors, "shop/orders"), "/orders/b.xml@1730196000000#cd34");
        assertEquals(2, intake.saves.size(), "fixture: the rec flow saved too");
        assertEquals("shop/orders", intake.saves.get(1).flow());
        assertEquals(null, intake.saves.get(1).dedupeKey(), "a component that names no dedupe prop saves without a key, whatever props arrive");
    }

    /** A {@code dedupeBy} that throws is one problem line of the deploy, worded like a flow that does not build. */
    @Test
    void aThrowingDedupeByIsAProblemLine() {
        deduping.answer = cfg -> {
            throw new IllegalStateException("cannot tell from these options");
        };

        DeployRefusedException refused = assertThrows(DeployRefusedException.class, () -> deploy(shop()), "the deploy is refused");

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals(1, refused.problems().size(), "one problem line: " + refused.problems());
        String line = refused.problems().get(0);
        assertTrue(line.startsWith("flow 'feed' does not build: ") && line.contains("cannot tell from these options"),
                "names the flow and the component's reason: " + line);
        assertTrue(dedupedDoors.doors().stream().noneMatch(Doors.Door::started) && doors.doors().stream().noneMatch(Doors.Door::started),
                "nothing started");
        assertEquals(List.of(), intake.saves);
    }
}
