package dev.rail8.runtime.deploy;

import dev.rail8.api.Health;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Beside {@link DeploymentsRestoreTest}: a restore runs once, at the node's start — on a node that lists a package already, or once
 * {@code close} began, it is refused and installs nothing; the {@code deployments} health check is DEGRADED while a package is
 * listed FAILED, its detail naming which, and while a flow's consumer is tried again; a repository gone is named as such.
 */
class DeploymentsRestoreGuardTest {

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    final InMemoryDeploymentStore rows = new InMemoryDeploymentStore();
    final List<Deployments> made = new ArrayList<>();
    GitRepos repos;

    @BeforeEach
    void repositories() {
        repos = new GitRepos(tmp.resolve("repos"));
    }

    @AfterEach
    void closeEach() throws Exception {
        for (Deployments d : made) OnVirtualThread.call(() -> { d.close(); return null; });
    }

    /** A node {@code id} of the pool: its own data directory and {@code doors}, the pool's rows and repositories. */
    Deployments node(String id, Doors doors) {
        Map<String, String> e = new HashMap<>();
        e.put("RAIL8_ENV", "dev");
        e.put("RAIL8_NODE_ID", id);
        e.put("RAIL8_DATA_DIR", tmp.resolve(id).toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        Deployments d = new Deployments(NodeConfig.from(repos.env(e)), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(), messages, dlq,
                new InMemoryWorkQueue(rt.clock()), () -> 0L, rows);
        made.add(d);
        return d;
    }

    static PackageDraft pkg(String name, String... flows) {
        PackageDraft p = PackageDraft.of(name, "1.0.0").endpoint("partner", "out", new JsonObject().put("url", "rec://partner"));
        for (String id : flows) {
            p.endpoint(id + "-in", "in", new JsonObject().put("path", "/" + id)).flow(id, "1.0.0", "sync", id + "-in", 1024, PackageDraft.toPartner());
        }
        return p;
    }

    void deploy(Deployments d, PackageDraft p) throws Exception {
        String commit = repos.commit(p);
        OnVirtualThread.call(() -> d.deploy(p.name(), commit, "test"));
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
        deploy(first, pkg("shop", "a"));
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
     * health() is UP on a node with nothing deployed and on one where everything runs; DEGRADED while the consumer of a restored
     * flow that did not start waits for its next attempt — its detail naming that flow, its failures in a row and when the next
     * attempt is due, never the cause nor what runs — and UP once it started; DEGRADED, not DOWN, while a package could not be
     * restored (its repository is gone, as its problem line says) — naming that package.
     */
    @Test
    void healthIsDegradedWhileAFlowRestartsOrAPackageIsListedFailed() throws Exception {
        Doors one = new Doors();
        Deployments first = node("node-1", one);
        assertEquals(Health.up(), first.health(), "nothing deployed");
        deploy(first, pkg("shop", "a", "b"));
        deploy(first, pkg("store", "c"));
        assertEquals(Health.up(), first.health(), "everything runs");

        Doors two = new Doors();
        two.failStartOf("shop/b", "address in use");
        Deployments second = node("node-2", two);
        restore(second);
        assertEquals(new Health(Health.Status.DEGRADED, "RESTARTING: flow shop/b (failure 1, next attempt at " + rt.clock().instant().plusSeconds(1) + ")"),
                second.health(), "the detail names the flow whose consumer is tried again, not the cause nor what runs");
        rt.clock().advance(Duration.ofSeconds(1));
        DeploymentsTest.until(() -> Health.up().equals(second.health()), "UP once b's fresh consumer started: " + two.events());

        repos.delete("store");   // the third node's cache is its own: it holds no commit yet
        Deployments third = node("node-3", new Doors());
        restore(third);
        Health packageFailed = third.health();
        assertEquals(Health.degraded("FAILED: package store"), packageFailed, "the detail names the FAILED package and nothing that runs");
        DeployedPackage failed = third.packages().get(1);
        assertEquals("store", failed.name());
        assertTrue(failed.problems().getFirst().contains("store at " + failed.commit().orElseThrow() + ": not found"),
                "a repository gone is named as such: " + failed.problems());
    }

    /**
     * A flow whose consumer does not start on restore runs on: that consumer is stopped, and the next attempt is due a second
     * later. Once the node closes — before that attempt — no attempt follows, and no producer of either flow is left open.
     */
    @Test
    void aConsumerThatDoesNotStartOnRestoreIsStoppedAndTheCloseEndsItsAttempts() throws Exception {
        deploy(node("node-1", new Doors()), pkg("shop", "a", "b"));
        Doors two = new Doors();
        two.failStartOf("shop/b", "address in use");
        Deployments second = node("node-2", two);
        restore(second);
        assertEquals(List.of("RUNNING", "RESTARTING"), second.flows().stream().map(f -> f.state().name()).toList(), "fixture: b did not start");
        assertTrue(two.door("shop/b").stopped(), "b's consumer was stopped as its start failed: " + two.door("shop/b").history());
        int built = two.doors().size();

        OnVirtualThread.call(() -> { second.close(); return null; });

        DeploymentsTest.until(() -> two.openProducers().isEmpty(), "every producer node 2 opened is closed, b's included: " + two.producers());
        rt.clock().advance(Duration.ofMinutes(1));
        assertEquals(built, two.doors().size(), "no consumer is built after the close: " + two.events());
    }
}
