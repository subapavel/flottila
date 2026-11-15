package dev.rail8.runtime.deploy;

import dev.rail8.api.Runtime;
import dev.rail8.core.dlq.InMemoryDlq;
import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.runtime.deploy.DeployRefusedException.Kind;
import dev.rail8.runtime.node.NodeConfig;
import dev.rail8.runtime.node.OnVirtualThread;
import dev.rail8.testkit.FakeRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.rail8.runtime.deploy.DeploymentsTest.flows;
import static dev.rail8.runtime.deploy.DeploymentsTest.shop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX-30, what {@link DeploymentsProducersTest} leaves open: an at-least-once flow whose consumer is refused after its worker
 * was built closes that worker's producers; a close whose consumer stop fails still closes every producer, and still rethrows
 * the stop failure.
 */
class DeploymentsProducersEdgeTest {

    @TempDir Path tmp;
    Path archives;

    final FakeRuntime rt = FakeRuntime.create();
    final Doors doors = new Doors();
    Deployments deployments;

    @BeforeEach
    void wire() {
        archives = tmp.resolve("archives");
        Map<String, String> env = new HashMap<>();
        env.put("RAIL8_ENV", "dev");
        env.put("RAIL8_NODE_ID", "node-1");
        env.put("RAIL8_DATA_DIR", tmp.resolve("data").toString());
        env.put("RAIL8_BLOB_DURABLE_MAX", "4096");
        Runtime runtime = new Runtime(rt.clock(), rt.timer(), rt.ids(), rt.blobs(), rt.hasher(), Map.of(Doors.SCHEME, doors));
        deployments = new Deployments(NodeConfig.from(env), runtime, Map.of(Doors.SCHEME, doors), rt.clock(), rt.timer(), List.of(), List.of(),
                new InMemoryMessageStore(), new InMemoryDlq(), new CountingQueue(rt.clock()), () -> 0L);
    }

    @AfterEach
    void release() {
        doors.releaseAll();
    }

    @Test
    void anAtLeastOnceFlowWhoseConsumerIsRefusedClosesItsWorkersProducer() {
        PackageDraft p = shop("shop");
        p.endpointDoc("audit-in").getJsonObject("config").put("refuse", "no door today");

        DeployRefusedException refused = assertThrows(DeployRefusedException.class,
                () -> OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), p.zipTo(archives))));

        assertEquals(Kind.INVALID, refused.kind(), refused.error() + " " + refused.problems());
        assertTrue(refused.problems().stream().anyMatch(s -> s.contains("no door today")), "fixture: audit's consumer was refused: " + refused.problems());
        assertEquals(6, doors.producers().size(), "fixture: orders', audit's worker's and ping's producer, and each flow's replay's (D-067): " + doors.producers());
        assertEquals(List.of(), doors.openProducers(), "the refused flow's worker producer is closed too: " + doors.producers());
        assertEquals(List.of(), doors.producersClosedTwice(), "each once: " + doors.producers());
    }

    @Test
    void aCloseWhoseConsumerStopFailsStillClosesEveryProducerAndRethrows() throws Exception {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("audit", "at-least-once");
        modes.put("orders", "sync");
        OnVirtualThread.call(() -> deployments.deploy("shop", Optional.empty(), flows("shop", modes).zipTo(archives)));
        assertEquals(4, doors.producers().size(), "fixture: four producers, each flow's and its replay's (D-067): " + doors.producers());
        doors.failStop("shop/orders", "socket stuck");

        OnVirtualThread.Call<Void> closing = OnVirtualThread.start(deployments::close);
        Exception thrown = assertThrows(Exception.class, () -> OnVirtualThread.join(closing), "close rethrows the stop failure");

        assertTrue(String.valueOf(thrown).contains("socket stuck") || String.valueOf(thrown.getCause()).contains("socket stuck"), "the stop's failure: " + thrown);
        assertEquals(List.of(), doors.openProducers(), "a failed stop closes the producers all the same: " + doors.producers());
        assertEquals(List.of(), doors.producersClosedTwice(), "each once: " + doors.producers());
    }
}
