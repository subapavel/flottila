package io.flottila.runtime.deploy;

import io.flottila.api.Health;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.queue.InMemoryWorkQueue;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c3b (D-070), beside {@link DeploymentsRestoreTest}: a restore runs once, at the node's start — on a node that lists a
 * package already, or once {@code close} began, it is refused and installs nothing; the {@code deployments} health check is DOWN
 * while a package or a flow is listed FAILED, its detail naming which; a missing archive is named as such.
 */
class DeploymentsRestoreGuardTest {

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
    final List<Deployments> made = new ArrayList<>();

    @AfterEach
    void closeEach() throws Exception {
        for (Deployments d : made) OnVirtualThread.call(() -> { d.close(); return null; });
    }

    /** A node {@code id} of the pool: its own data directory and {@code doors}, the pool's rows, archives in {@code <tmp>/bucket}. */
    Deployments node(String id, Doors doors) {
        Map<String, String> e = new HashMap<>();
        e.put("FLOTTILA_ENV", "dev");
        e.put("FLOTTILA_NODE_ID", id);
        e.put("FLOTTILA_DATA_DIR", tmp.resolve(id).toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        Deployments d = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                new InMemoryWorkQueue(rt.clock()), () -> 0L, rows, new DirArchiveStore(tmp.resolve("bucket")));
        made.add(d);
        return d;
    }

    static byte[] pkg(String name, String... flows) {
        PackageDraft p = PackageDraft.of(name, "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : flows) {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 1024, PackageDraft.toPartner());
        }
        return p.zip();
    }

    void deploy(Deployments d, String name, byte[] zip) throws Exception {
        Path archive = PackageDraft.write(tmp.resolve("uploads"), zip);
        OnVirtualThread.call(() -> d.deploy(name, Optional.empty(), archive));
    }

    static void restore(Deployments d) throws Exception {
        OnVirtualThread.call(() -> { d.restore(); return null; });
    }

    /**
     * Node 1 deployed shop: a restore on it is refused (it lists a package), and so is a second restore on node 2, which restored
     * shop; node 3, closed before its restore, refuses it and lists nothing. No refused restore builds or starts a flow.
     */
    @Test
    void aRestoreRunsOnceBeforeAnyDeployAndBeforeClose() throws Exception {
        Doors one = new Doors();
        Deployments first = node("node-1", one);
        deploy(first, "shop", pkg("shop", "a"));
        int doorsOfOne = one.doors().size();
        IllegalStateException afterDeploy = assertThrows(IllegalStateException.class, () -> restore(first), "a restore after a deploy");
        assertTrue(afterDeploy.getMessage().contains("at the node's start"), afterDeploy.getMessage());
        assertEquals(doorsOfOne, one.doors().size(), "the refused restore built nothing");

        Doors two = new Doors();
        Deployments second = node("node-2", two);
        restore(second);
        assertEquals(List.of("shop"), second.packages().stream().map(DeployedPackage::name).toList(), "fixture: node 2 restored shop");
        assertThrows(IllegalStateException.class, () -> restore(second), "a second restore");
        assertEquals(1, two.doors().size(), "the refused restore built nothing: " + two.events());

        Doors three = new Doors();
        Deployments third = node("node-3", three);
        OnVirtualThread.call(() -> { third.close(); return null; });
        assertThrows(IllegalStateException.class, () -> restore(third), "a restore once close began");
        assertEquals(List.of(), third.packages(), "nothing is listed");
        assertEquals(List.of(), three.doors(), "nothing is built");
    }

    /**
     * health() is UP on a node with nothing deployed and on one where everything runs; DOWN while a restored flow did not start —
     * its detail naming that flow, not the package that runs — and while a package could not be restored (its archive is not in
     * the archive store, as its problem line says) — naming that package.
     */
    @Test
    void healthIsDownWhileAPackageOrAFlowIsListedFailed() throws Exception {
        Doors one = new Doors();
        Deployments first = node("node-1", one);
        assertEquals(Health.up(), first.health(), "nothing deployed");
        byte[] store = pkg("store", "c");
        deploy(first, "shop", pkg("shop", "a", "b"));
        deploy(first, "store", store);
        assertEquals(Health.up(), first.health(), "everything runs");

        Doors two = new Doors();
        two.failStartOf("shop/b", "address in use");
        Deployments second = node("node-2", two);
        restore(second);
        Health flowDown = second.health();
        assertEquals(Health.Status.DOWN, flowDown.status(), flowDown.toString());
        assertTrue(flowDown.detail().contains("flow shop/b"), "the detail names the FAILED flow: " + flowDown);
        assertFalse(flowDown.detail().contains("store") || flowDown.detail().contains("shop/a"), "and nothing that runs: " + flowDown);

        Files.delete(tmp.resolve("bucket").resolve(PackageDraft.sha256(store) + ".zip"));
        Deployments third = node("node-3", new Doors());
        restore(third);
        Health packageDown = third.health();
        assertEquals(Health.Status.DOWN, packageDown.status(), packageDown.toString());
        assertTrue(packageDown.detail().contains("package store"), "the detail names the FAILED package: " + packageDown);
        assertFalse(packageDown.detail().contains("shop"), "and nothing that runs: " + packageDown);
        DeployedPackage failed = third.packages().get(1);
        assertEquals("store", failed.name());
        assertTrue(failed.problems().getFirst().contains("is not in the archive store"), "a missing object is named as such: " + failed.problems());
    }

    /**
     * A flow that does not start on restore is stopped and closed there and then: its consumer is stopped, and once the node
     * closes — which stops only the flows that run — no producer of either flow is left open.
     */
    @Test
    void aFlowThatDoesNotStartOnRestoreIsStoppedAndClosed() throws Exception {
        deploy(node("node-1", new Doors()), "shop", pkg("shop", "a", "b"));
        Doors two = new Doors();
        two.failStartOf("shop/b", "address in use");
        Deployments second = node("node-2", two);
        restore(second);
        assertEquals(List.of("RUNNING", "FAILED"), second.flows().stream().map(f -> f.state().name()).toList(), "fixture: b did not start");
        assertTrue(two.door("shop/b").stopped(), "b's consumer was stopped as the restore found its start failed: " + two.door("shop/b").history());

        OnVirtualThread.call(() -> { second.close(); return null; });

        DeploymentsTest.until(() -> two.openProducers().isEmpty(), "every producer node 2 opened is closed, b's included: " + two.producers());
    }
}
