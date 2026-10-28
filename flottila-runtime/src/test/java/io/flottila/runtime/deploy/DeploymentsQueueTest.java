package io.flottila.runtime.deploy;

import io.flottila.api.Ack;
import io.flottila.api.Envelope;
import io.flottila.api.Headers;
import io.flottila.api.Runtime;
import io.flottila.core.dlq.InMemoryDlq;
import io.flottila.core.record.InMemoryMessageStore;
import io.flottila.runtime.deploy.DeployRefusedException.Kind;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeRuntime;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.flottila.runtime.deploy.DeploymentsTest.within;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-01 (D-075): a flow's {@code delivery.queue}, read where the deploy validates every flow. Absent or {@code postgres}: the
 * node's work queue, as before. {@code kafka}: one problem line — the Kafka hand-over is not built yet — and the package is
 * refused (422). Set on a sync or at-most-once flow: a problem line each — only an at-least-once flow has a work queue.
 * Everything in memory, as {@link DeploymentsTest}: the {@link Doors} component, the in-memory store, DLQ and a
 * {@link CountingQueue}, the fake clock driving every timer.
 */
class DeploymentsQueueTest {

    @TempDir Path tmp;
    Path archives;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    final InMemoryMessageStore messages = new InMemoryMessageStore();
    final InMemoryDlq dlq = new InMemoryDlq();
    TracingTimer timer;
    CountingQueue queue;
    Deployments deployments;

    @BeforeEach
    void wire() {
        archives = tmp.resolve("archives");
        timer = new TracingTimer(rt.timer());
        queue = new CountingQueue(rt.clock());
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "dev");
        env.put("FLOTTILA_NODE_ID", DeploymentsTest.NODE);
        env.put("FLOTTILA_DATA_DIR", tmp.resolve("data").toString());
        env.put("FLOTTILA_STOP_DEADLINE", DeploymentsTest.STOP_DEADLINE.toString());
        env.put("FLOTTILA_BLOB_DURABLE_MAX", Long.toString(DeploymentsTest.DURABLE_MAX));
        env.put("FLOTTILA_MAX_PACKAGE_BYTES", Long.toString(DeploymentsTest.MAX_PACKAGE));
        Runtime runtime = new Runtime(rt.clock(), timer, rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), timer, DeploymentsTest.STEPS, List.of(),
                messages, dlq, queue, () -> 0L);
    }

    @AfterEach
    void release() {
        doors.releaseAll();
    }

    /** {@code shop} 1.0.0 with these flows (id → delivery mode), each from its own in endpoint to {@code partner}; {@code queues} sets delivery.queue. */
    static PackageDraft shop(Map<String, String> modes, Map<String, String> queues) {
        PackageDraft p = DeploymentsTest.flows("shop", modes);
        queues.forEach((flow, queue) -> p.flowDoc(flow).getJsonObject("delivery").put("queue", queue));
        return p;
    }

    DeployRefusedException refused(PackageDraft p) {
        return assertThrows(DeployRefusedException.class, () -> OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), p.zipTo(archives))),
                "the deploy is refused");
    }

    DeployedPackage deployed(PackageDraft p) throws Exception {
        return OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), p.zipTo(archives))).pkg();
    }

    void nothingStarted() {
        assertEquals(List.of(), doors.events().stream().filter(e -> e.startsWith("start ")).toList(), "no consumer started: " + doors.events());
        assertEquals(List.of(), deployments.flows(), "nothing is listed");
        rt.clock().advance(Duration.ofHours(1));
        assertEquals(List.of(), queue.claims(), "no worker polls");
    }

    @Test
    void kafkaRefusedWithOneLine() {
        DeployRefusedException refused = refused(shop(Map.of("audit", "at-least-once"), Map.of("audit", "kafka")));

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals(List.of("flow 'audit' sets delivery.queue kafka: the Kafka hand-over is not built yet (Phase 2, later); omit it or use postgres"),
                refused.problems(), "one line, naming the flow and what to do instead");
        nothingStarted();
    }

    /** A sync and an at-most-once flow that set {@code delivery.queue}: one line each — only an at-least-once flow has a work queue. */
    @Test
    void queueOnSyncFlowRefused() {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("orders", "sync");
        modes.put("ping", "at-most-once");
        modes.put("audit", "at-least-once");
        DeployRefusedException refused = refused(shop(modes, Map.of("orders", "postgres", "ping", "postgres", "audit", "postgres")));

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertEquals(2, refused.problems().size(), "one line per flow that is not at-least-once; the at-least-once one is fine: " + refused.problems());
        for (String flow : List.of("orders", "ping")) {
            assertTrue(refused.problems().stream().anyMatch(l -> l.contains("flow '" + flow + "'") && l.contains("delivery.queue")
                            && l.contains("only an at-least-once flow has a work queue")),
                    "a line names " + flow + ", delivery.queue and why: " + refused.problems());
        }
        nothingStarted();
    }

    /** {@code postgres} or no {@code queue}: the at-least-once flow deploys on the node's work queue and accepts into it. */
    @Test
    void postgresOrAbsentDeploys() throws Exception {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("audit", "at-least-once");
        modes.put("ledger", "at-least-once");
        DeployedPackage p = deployed(shop(modes, Map.of("audit", "postgres")));

        assertEquals(List.of("shop/audit", "shop/ledger"), p.flows().stream().sorted().toList(), "both deploy");
        for (String flow : List.of("shop/audit", "shop/ledger")) {
            Envelope e = rt.ingress(flow, Headers.empty(), Buffer.buffer("<order/>"));
            assertEquals(new Ack.Accepted(e.id()), within(doors.door(flow).deliver(e), flow + "'s answer"), flow + " accepts");
        }
        assertEquals(List.of("shop/audit", "shop/ledger"), queue.enqueued().stream().sorted().toList(), "each into the node's work queue");
    }
}
