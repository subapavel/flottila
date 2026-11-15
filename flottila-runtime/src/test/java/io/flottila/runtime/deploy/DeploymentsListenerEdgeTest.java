package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Component;
import io.flottila.api.Consumer;
import io.flottila.api.EndpointConfig;
import io.flottila.api.Envelope;
import io.flottila.api.Health;
import io.flottila.api.Producer;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.schemas.Package;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static io.flottila.runtime.deploy.DeploymentsListenerTest.consumersOf;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.flowOf;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.line;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.listening;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.on;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.pkg;
import static io.flottila.runtime.deploy.DeploymentsListenerTest.port;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P1-09g (D-072), the edges of the refusal line beyond {@link DeploymentsListenerTest}: a pair of one package is reported on the
 * later flow id whatever order the flow files are read in; a host configured in brackets is shown in brackets once; a component
 * that answers {@code listensOn} with null is one problem line naming the endpoint and its scheme, as a throw is.
 */
class DeploymentsListenerEdgeTest {

    @TempDir Path tmp;
    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final NullAnswer nul = new NullAnswer();
    Deployments deployments;

    /**
     * A component of the scheme {@value #SCHEME} whose {@code listensOn} answers null and records the endpoint asked; everything else
     * is a {@link Doors} of that scheme, so a deploy that let the null through would build its consumer and succeed.
     */
    static final class NullAnswer implements Component {
        static final String SCHEME = "nul";
        final Doors inner = new Doors(SCHEME);
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override public String scheme() { return inner.scheme(); }

        @Override public String configSchema() { return inner.configSchema(); }

        @Override
        public Consumer consumer(EndpointConfig cfg, Function<Envelope, Future<Ack>> handler) { return inner.consumer(cfg, handler); }

        @Override public Producer producer(EndpointConfig cfg) { return inner.producer(cfg); }

        @Override public Future<Health> health() { return inner.health(); }

        @Override
        public Optional<InetSocketAddress> listensOn(EndpointConfig cfg) {
            asked.add(cfg.name());
            return null;
        }
    }

    @BeforeEach
    void wire() {
        Map<String, String> env = Map.of("FLOTTILA_ENV", "dev", "FLOTTILA_NODE_ID", "node-1", "FLOTTILA_DATA_DIR", tmp.resolve("node-1").toString(),
                "FLOTTILA_BLOB_DURABLE_MAX", "4096", "FLOTTILA_ADMIN_PORT", Integer.toString(DeploymentsListenerTest.ADMIN_PORT));
        Map<String, Component> byScheme = Map.of(Doors.SCHEME, doors, NullAnswer.SCHEME, nul);
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), byScheme);
        deployments = new Deployments(NodeConfig.from(env), runtime, byScheme, rt.clock(), rt.timer(), List.of(), List.of(), new InMemoryMessageStore(),
                new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L, new InMemoryDeploymentStore(),
                new DirArchiveStore(tmp.resolve("node-1").resolve("archives")));
    }

    @AfterEach
    void close() throws Exception {
        doors.releaseAll();
        nul.inner.releaseAll();
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    void deploy(PackageDraft p) throws Exception {
        Path archive = p.zipTo(tmp.resolve("uploads"));
        String name = p.manifest().getString("name");
        OnVirtualThread.call(() -> deployments.deploy(name, Optional.empty(), archive));
    }

    /** The problem lines of the deploy of {@code p}, refused as a 422. */
    List<String> refused(PackageDraft p) {
        Path archive = p.zipTo(tmp.resolve("uploads"));
        String name = p.manifest().getString("name");
        DeployRefusedException r = assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> deployments.deploy(name, Optional.empty(), archive)),
                "the deploy of " + name + " is refused");
        assertEquals(Kind.INVALID, r.kind(), "a 422: " + r.error() + " " + r.problems());
        return r.problems();
    }

    /** The flow ids of {@code p} in the order the package reader reads its flow files. */
    List<String> fileOrder(PackageDraft p) throws Exception {
        Path dir = tmp.resolve("read");
        for (Map.Entry<String, byte[]> f : p.files().entrySet()) {
            Path file = dir.resolve(f.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, f.getValue());
        }
        return Package.load(dir).flowIds();
    }

    @Test
    void aPairsLineGoesOnTheLaterFlowIdNotTheLaterFile() throws Exception {
        PackageDraft shop = listening(listening(pkg("shop"), "a", port(7070)), "a-b", port(7070));
        assertEquals(List.of("a-b", "a"), fileOrder(shop), "witness: flows/a-b.yaml is read before flows/a.yaml, the reverse of id order");

        assertEquals(List.of(line("a-b", "a-b-in", "127.0.0.1:7070", "127.0.0.1:7070", flowOf("a", "a-in"))), refused(shop),
                "one line, on the later flow id (a-b), naming the earlier (a), whatever order the files are read in");
        assertEquals(List.of(), deployments.packages(), "nothing of it is listed");
        assertEquals(List.of(), doors.events(), "no consumer was built");
    }

    @Test
    void aHostConfiguredInBracketsIsShownInBracketsOnce() throws Exception {
        deploy(listening(pkg("billing"), "invoices", on("[::1]", 8080)));

        String expected = line("orders", "orders-in", "[::1]:8080", "[::1]:8080", flowOf("billing/invoices", "invoices-in"));
        assertEquals(List.of(expected), refused(listening(pkg("shop"), "orders", on("::1", 8080))),
                "the running flow's host configured as [::1] is shown [::1], the deploying one's ::1 bracketed once");
        assertEquals(List.of(expected), refused(listening(pkg("shop"), "orders", on("[::1]", 8080))),
                "the deploying flow's host configured as [::1] is shown [::1], not bracketed again");
        assertEquals(List.of(), consumersOf(doors, "shop"), "no consumer of shop was built: " + doors.events());
    }

    @Test
    void aListensOnThatAnswersNullIsAProblemLine() {
        PackageDraft shop = listening(listening(pkg("shop"), "orders", NullAnswer.SCHEME, port(7070)), "audit", port(7071));

        assertEquals(List.of("endpoint 'orders-in' (scheme 'nul'): its component cannot say where its consumer listens: listensOn answered null"),
                refused(shop), "one line naming the endpoint and its scheme, as a throw is; audit's answer adds none");
        assertEquals(List.of("orders-in"), nul.asked, "the null came from asking orders-in's component");
        assertEquals(List.of(), nul.inner.events(), "no consumer of the null-answering scheme was built");
        assertEquals(List.of(), doors.events(), "no consumer was built");
        assertEquals(List.of(), deployments.packages(), "nothing of it is listed");
    }

    @Test
    void anInEndpointOfAnUnservedSchemeIsNotAskedWhereItListens() {
        PackageDraft shop = listening(listening(pkg("shop"), "orders", "sftp", port(7070)), "audit", port(7070));

        assertEquals(List.of("endpoint 'orders-in': no component on this node serves the scheme 'sftp'"), refused(shop),
                "only the unserved scheme's line: no listener line for orders-in (no component to ask), no overlap with audit's 7070");
        assertEquals(List.of("audit-in"), doors.listensOnAsked(), "witness: the served in endpoint of the same package was asked where it listens");
        assertEquals(List.of(), nul.asked, "no other component was asked for orders-in");
        assertEquals(List.of(), doors.events(), "no consumer was built");
        assertEquals(List.of(), deployments.packages(), "nothing of it is listed");
    }
}
