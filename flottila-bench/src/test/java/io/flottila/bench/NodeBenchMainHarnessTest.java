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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node bench's client side against a local Vert.x server (no node, no database): what a load counts as failed, a deploy
 * the node refuses, and the exit code a record with failures gives.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodeBenchMainHarnessTest {

    Vertx vertx;
    HttpServer server;
    NodeBenchMain.Harness harness;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
        // /ok echoes, /fail is 500 "nope", the admin path refuses the package with 422
        server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(bytes -> {
            switch (req.path()) {
                case "/ok" -> req.response().end(bytes);
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
    }

    @Test
    void aDeployTheNodeRefusesFailsTheRunWithTheAnswer() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> harness.deploy(server.actualPort(), new byte[] {1}));
        assertTrue(refused.getMessage().contains("422") && refused.getMessage().contains("flow 'bench' is invalid"), refused.getMessage());
    }

    @Test
    void aWorkerWindowsFailuresAreTheRefusedRequestsAndTheItemsParkedInIt() {
        NodeBenchMain.Window queued = new NodeBenchMain.Window(new LatencyStats(1), 1, 1, "HTTP 503");
        assertEquals(4, NodeBenchMain.Harness.failed(queued, new NodeBenchMain.Snapshot(0, 0, 2), new NodeBenchMain.Snapshot(0, 0, 5)),
                "1 refused, 3 parked in the window (2 were parked before it)");
        assertEquals(0, NodeBenchMain.Harness.failed(new NodeBenchMain.Window(new LatencyStats(1), 0, 1, null),
                new NodeBenchMain.Snapshot(0, 0, 2), new NodeBenchMain.Snapshot(0, 0, 2)));
    }

    @Test
    void aRecordWithAFailureExitsThreeAndOneWithoutExitsZero() {
        assertEquals(3, NodeBenchMain.exitCode(new JsonObject().put("messages", 5).put("failed", 1).put("firstFailure", "HTTP 503")));
        assertEquals(0, NodeBenchMain.exitCode(new JsonObject().put("messages", 5).put("failed", 0)));
    }
}
