package io.flottila.bench;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node bench's client side against a local Vert.x server (no node, no database): what a load counts as refused (503) and
 * as an error (any other status, a failed request) (FIX-67), a deploy
 * the node refuses, and the exit code a record with failures gives.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodeBenchMainHarnessTest {

    Vertx vertx;
    HttpServer server;
    NodeBenchMain.Harness harness;
    final AtomicInteger mixed = new AtomicInteger();
    final AtomicLong writeFailures = new AtomicLong();
    volatile boolean down;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
        // /ok echoes, /busy is 503 "busy", /mixed answers its n-th request 200, 503, 500 in turn, /fail is 500 "nope", the
        // admin path refuses the package with 422, /health answers the node's shape with {@code writeFailures} (503 DOWN when {@code down})
        server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(bytes -> {
            switch (req.path()) {
                case "/ok" -> req.response().end(bytes);
                case "/busy" -> req.response().setStatusCode(503).end("busy");
                case "/health" -> req.response().setStatusCode(down ? 503 : 200).end(new JsonObject().put("status", down ? "DOWN" : "UP")
                        .put("checks", new JsonObject().put("postgres", down ? "DOWN" : "UP")).put("snapshotWriteFailures", writeFailures.get()).encode());
                case "/mixed" -> {
                    switch (mixed.getAndIncrement() % 3) {
                        case 0 -> req.response().end(bytes);
                        case 1 -> req.response().setStatusCode(503).end("busy");
                        default -> req.response().setStatusCode(500).end("nope");
                    }
                }
                case "/admin/packages/bench" -> req.response().setStatusCode(422).end("{\"error\":\"flow 'bench' is invalid\"}");
                default -> req.response().setStatusCode(500).end("nope");
            }
        })).listen(0, "127.0.0.1").await();
        NodeBenchMain.Options o = NodeBenchMain.options(new String[] {"--scenario", "sync", "--concurrency", "2"});
        harness = new NodeBenchMain.Harness(o, vertx, null, vertx.createHttpClient(new HttpClientOptions(), new PoolOptions().setHttp1MaxSize(2)),
                Buffer.buffer("<Invoice/>"), server.actualPort(), server.actualPort());
    }

    @AfterEach
    void stop() {
        vertx.close().await();
    }

    @Test
    void aLoadCountsEveryOtherStatusAndEveryFailedRequestAsFailedAndKeepsTheFirst() throws IOException {
        NodeBenchMain.Window ok = harness.load(server.actualPort(), "/ok", 5);
        assertEquals(0, ok.failed(), ok.firstFailure());
        assertNull(ok.firstFailure());
        assertEquals(5, ok.latency().count(), "every request's latency is recorded");

        NodeBenchMain.Window status = harness.load(server.actualPort(), "/fail", 3, 200);
        assertEquals(3, status.failed());
        assertEquals("HTTP 500: nope", status.firstFailure());
        assertEquals(3, status.latency().count(), "a failed request's latency is recorded too");
        assertEquals(0, harness.load(server.actualPort(), "/fail", 3, 500).failed(), "the expected status is the load's to say");

        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        NodeBenchMain.Window refused = harness.load(closed, "/ok", 2, 200);
        assertEquals(2, refused.failed());
        assertTrue(refused.firstFailure().toLowerCase().contains("refused"), "the request's failure: " + refused.firstFailure());
        assertEquals(2, refused.errors(), "a request that failed is an error, a refused connection too");
        assertEquals(0, refused.refused(), "only a 503 answer is an admission refusal");
        assertEquals(3, status.errors(), "a 500 is an error");
        assertEquals(0, status.refused());
    }

    @Test
    void aLoadCountsTheFlowsRefusalsApartFromItsErrorsAndRecordsBoth() {
        NodeBenchMain.Window w = harness.load(server.actualPort(), "/mixed", 6, 200);
        assertEquals(6, w.latency().count(), "every answer was counted");
        assertEquals(2, w.refused(), "the two 503s are admission refusals");
        assertEquals(2, w.errors(), "the two 500s are errors");
        assertEquals(4, w.failed(), "failed is what was not answered 200: refusals and errors");
        assertEquals("HTTP 500: nope", w.firstFailure(), "the first failure kept is an error's, not a refusal's");

        JsonObject json = w.toJson(6);
        assertEquals(6, json.getInteger("messages"));
        assertEquals(2, json.getInteger("refused"), json.encode());
        assertEquals(2, json.getInteger("errors"), json.encode());
        assertEquals(4, json.getInteger("failed"), "6 - failed = the 2 answered 200: " + json.encode());

        NodeBenchMain.Window busy = harness.load(server.actualPort(), "/busy", 3, 200);
        assertEquals(3, busy.refused(), "503 when 200 was expected is a refusal");
        assertEquals(0, busy.errors());
        assertEquals(3, busy.failed());
        JsonObject b = busy.toJson(3);
        assertEquals(3, b.getInteger("refused"), b.encode());
        assertEquals(0, b.getInteger("errors"), b.encode());

        NodeBenchMain.Window ok = harness.load(server.actualPort(), "/ok", 2, 200);
        JsonObject o = ok.toJson(2);
        assertEquals(0, o.getInteger("refused"), "a clean window records its zero counts too: " + o.encode());
        assertEquals(0, o.getInteger("errors"), o.encode());
    }

    @Test
    void aWindowsFailedIsItsRefusalsPlusItsErrors() {
        LatencyStats one = new LatencyStats(1);
        one.record(1_000_000);
        NodeBenchMain.Window w = new NodeBenchMain.Window(one, 2, 5, 1_000_000_000L, "HTTP 500: nope");
        assertEquals(7, w.failed());
        JsonObject json = w.toJson(10);
        assertEquals(2, json.getInteger("refused"));
        assertEquals(5, json.getInteger("errors"));
        assertEquals(7, json.getInteger("failed"));
        assertEquals(3.0, json.getDouble("throughputPerSecond"), 1e-9, "the 3 answered of 10 in one second");
    }

    @Test
    void theRunsProbeReadsSnapshotWriteFailuresFromTheNodesHealthUpOrDown() {
        // the stub's /health is the node's shape: 200 UP or 503 DOWN, the counter in the body either way
        java.util.function.LongSupplier counter = harness.probe(server.actualPort()).snapshotWriteFailures();
        writeFailures.set(3);
        assertEquals(3, counter.getAsLong(), "read from /health on the admin port");
        writeFailures.set(17);
        assertEquals(17, counter.getAsLong(), "read again on every call, not once");
        down = true;
        writeFailures.set(4096);
        assertEquals(4096, counter.getAsLong(), "a DOWN node (503) still answers its counter");
    }

    @Test
    void aDeployTheNodeRefusesFailsTheRunWithTheAnswer() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> harness.deploy(server.actualPort(), new byte[] {1}));
        assertTrue(refused.getMessage().contains("422") && refused.getMessage().contains("flow 'bench' is invalid"), refused.getMessage());
    }

    @Test
    void aWorkerWindowsFailuresAreTheRefusedRequestsAndTheItemsParkedInIt() {
        NodeBenchMain.Window queued = new NodeBenchMain.Window(new LatencyStats(1), 1, 0, 1, "HTTP 503");
        assertEquals(4, NodeBenchMain.Harness.failed(queued, new NodeBenchMain.Snapshot(0, 0, 2), new NodeBenchMain.Snapshot(0, 0, 5)),
                "1 refused, 3 parked in the window (2 were parked before it)");
        assertEquals(0, NodeBenchMain.Harness.failed(new NodeBenchMain.Window(new LatencyStats(1), 0, 0, 1, null),
                new NodeBenchMain.Snapshot(0, 0, 2), new NodeBenchMain.Snapshot(0, 0, 2)));
    }

    @Test
    void aRecordWithAFailureExitsThreeAndOneWithoutExitsZero() {
        assertEquals(3, NodeBenchMain.exitCode(new JsonObject().put("messages", 5).put("failed", 1).put("firstFailure", "HTTP 503")));
        assertEquals(0, NodeBenchMain.exitCode(new JsonObject().put("messages", 5).put("failed", 0)));
    }
}
