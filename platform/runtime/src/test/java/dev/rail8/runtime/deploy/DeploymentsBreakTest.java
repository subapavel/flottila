package dev.rail8.runtime.deploy;

import dev.rail8.api.Ack;
import dev.rail8.api.Headers;
import dev.rail8.api.Health;
import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.queue.InMemoryWorkQueue;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.rail8.runtime.deploy.DeploymentsTest.until;
import static dev.rail8.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A consumer that breaks while its flow runs — its connection dropped, its listener closed — is stopped and, after a delay that
 * doubles with each failure in a row, replaced by a fresh consumer of the same endpoint, with no operator: the flow is listed
 * RESTARTING and the health DEGRADED meanwhile, and the other flows serve all along. The node runs on a fake clock.
 */
class DeploymentsBreakTest {

    @TempDir Path tmp;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    Deployments deployments;

    @BeforeEach
    void node() {
        Map<String, String> e = Map.of("RAIL8_ENV", "dev", "RAIL8_NODE_ID", "node-1", "RAIL8_DATA_DIR", tmp.resolve("node-1").toString());
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(e), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(),
                new InMemoryMessageStore(), new InMemoryDlq(), new InMemoryWorkQueue(rt.clock()), () -> 0L, new InMemoryDeploymentStore());
    }

    @AfterEach
    void close() throws Exception {
        OnVirtualThread.call(() -> { deployments.close(); return null; });
    }

    List<Doors.Door> live(String flow) {
        return doors.doors().stream().filter(d -> d.flow().equals(flow) && d.started() && !d.stopped()).toList();
    }

    Ack send(String flow) throws Exception {
        List<Doors.Door> running = live(flow);
        assertEquals(1, running.size(), "one consumer of " + flow + " runs: " + doors.events());
        return within(running.getFirst().deliver(rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"))), "the answer of " + flow);
    }

    DeployedFlow.State state(String flow) {
        return deployments.flows().stream().filter(f -> f.id().equals(flow)).findFirst().orElseThrow().state();
    }

    Health restarting(String flow, int failures, Instant next) {
        return new Health(Health.Status.DEGRADED, "RESTARTING: flow " + flow + " (failure " + failures + ", next attempt at " + next + ")");
    }

    @Test
    void aConsumerThatBreaksIsReplacedAfterAGrowingDelayAndTheOtherFlowsServeAllAlong() throws Exception {
        Path folder = DeploymentsRestoreGuardTest.pkg("shop", "a", "b").folderIn(tmp.resolve("folders"));
        OnVirtualThread.call(() -> deployments.deploy("shop", folder));
        Doors.Door b = doors.door("shop/b");
        assertEquals(Health.up(), deployments.health(), "fixture: everything runs");

        doors.door("shop/a").breakOff("connection reset");
        Instant broke = rt.clock().instant();
        until(() -> doors.door("shop/a").stopped() && state("shop/a") == DeployedFlow.State.RESTARTING, "a's broken consumer is stopped: " + doors.events());
        assertEquals(restarting("shop/a", 1, broke.plusSeconds(1)), deployments.health(), "the health names the flow, its failures and the next attempt");
        assertEquals(new Deployments.Restarting(1, broke.plusSeconds(1), "connection reset"), deployments.restarting().get("shop/a"), "the cause is kept for the admin API");
        assertEquals(List.of(), live("shop/a"), "nothing of a serves before its next attempt");
        assertInstanceOf(Ack.Reply.class, send("shop/b"), "b serves while a waits");

        rt.clock().advance(Duration.ofSeconds(1));
        until(() -> live("shop/a").size() == 1 && deployments.restarting().isEmpty(), "a fresh consumer of a runs: " + doors.events());
        assertEquals(DeployedFlow.State.RUNNING, state("shop/a"));
        assertEquals(Health.up(), deployments.health());
        assertInstanceOf(Ack.Reply.class, send("shop/a"), "a serves again");

        doors.door("shop/a").breakOff("connection reset");   // again, a second after its last failure: the delay doubles
        Instant again = rt.clock().instant();
        until(() -> state("shop/a") == DeployedFlow.State.RESTARTING, "a's second consumer is stopped: " + doors.events());
        assertEquals(new Deployments.Restarting(2, again.plusSeconds(2), "connection reset"), deployments.restarting().get("shop/a"));
        rt.clock().advance(Duration.ofSeconds(1));
        assertEquals(List.of(), live("shop/a"), "a second is not yet two");
        rt.clock().advance(Duration.ofSeconds(1));
        until(() -> live("shop/a").size() == 1 && deployments.restarting().isEmpty(), "a third consumer of a runs: " + doors.events());
        assertInstanceOf(Ack.Reply.class, send("shop/a"), "a serves again");

        assertEquals(List.of(b), doors.doors().stream().filter(d -> d.flow().equals("shop/b")).toList(), "b's consumer was never stopped or built again");
        assertFalse(b.stopped());
        assertEquals(3, doors.doors().stream().filter(d -> d.flow().equals("shop/a")).count(), "a ran three consumers: " + doors.events());
    }
}
