package io.flottila.bench;

import io.flottila.components.http.HttpComponent;
import io.flottila.runtime.node.NodeConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kill -9 ledger's parts that need no database and no node (P1-10b): the nodes' environment for each archive store, the
 * partner that holds calls until node 1 is dead, what a send counts as an ACK, the bounded wait, the refusal of a node 1 that
 * exited or did not serve, and a run that fails before it connects.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LedgerMainPartsTest {

    Vertx vertx;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stop() {
        vertx.close().await();
    }

    @Test
    void bothNodesShareTheArchiveDirectoryWithoutS3OptionsAndUseThePathStyleS3StoreWithThem() {
        LedgerMain.Options dir = LedgerMain.options(new String[] {"--db", "x_bench", "--host", "db.local", "--port", "6543", "--user", "u", "--password", "p"});
        assertEquals(Map.of(), dir.s3(), "no --s3-* option: the dir store");
        Map<String, String> env = LedgerMain.nodeEnv(dir, "ledger-node-2", Path.of("/data/n2"), Path.of("/data/archives"), 0);
        NodeConfig node = NodeConfig.from(env);
        assertEquals(NodeConfig.Archives.Store.DIR, node.archives().store());
        assertEquals(Path.of("/data/archives"), node.archives().dir(), "the archive directory both nodes are given, not one under a data directory");
        assertEquals(Path.of("/data/n2"), node.dataDir());
        assertEquals("ledger-node-2", node.nodeId());
        assertEquals(0, node.admin().port());
        assertFalse(node.deployed(), "env dev: the run's nodes are not deployed ones");
        assertEquals(new NodeConfig.Pg("db.local", 6543, "x_bench", "u", "p", 16), node.pg());

        LedgerMain.Options s3 = LedgerMain.options(new String[] {"--db", "x_bench", "--s3-endpoint", "http://127.0.0.1:4566", "--s3-region", "eu-west-1",
                "--s3-bucket", "b", "--s3-prefix", "pre", "--s3-access-key", "ak", "--s3-secret-key", "sk"});
        NodeConfig.S3 store = NodeConfig.from(LedgerMain.nodeEnv(s3, "ledger-node-1", Path.of("/data/n1"), Path.of("/data/archives"), 7001)).s3().orElseThrow();
        assertEquals(new NodeConfig.S3("b", "eu-west-1", Optional.of(URI.create("http://127.0.0.1:4566")), true, "pre",
                Optional.of("ak"), Optional.of("sk")), store, "every --s3-* option reaches the node, path-style");
        assertEquals("s3", LedgerMain.nodeEnv(s3, "n", Path.of("/d"), Path.of("/a"), 0).get("FLOTTILA_ARCHIVE_STORE"));
    }

    @Test
    void aNodeConfigurationTheNodeRefusesFailsTheRunBeforeItConnects() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> LedgerMain.run(new String[] {"--host", "127.0.0.1",
                "--port", "1", "--db", "x_bench", "--messages", "4", "--s3-region", "eu-west-1"}));
        assertTrue(refused.getMessage().contains("FLOTTILA_S3_BUCKET"), "the node's own refusal, unwrapped: " + refused.getMessage());
    }

    @Test
    void aRunThatCannotReachItsDatabaseThrowsTheFailureItself() {
        Throwable failed = assertThrows(Throwable.class, () -> LedgerMain.run(new String[] {"--host", "127.0.0.1", "--port", "1", "--db", "x_bench",
                "--messages", "4"}));
        assertInstanceOf(RuntimeException.class, failed, "Flyway's connect failure, not the virtual thread's wrapper: " + failed);
    }

    @Test
    void thePartnerEchoesTheFirstCallsHoldsTheLaterOnesAndEchoesEveryCallOnceReleased() {
        LedgerMain.Partner partner = new LedgerMain.Partner(2);
        int port = vertx.createHttpServer().requestHandler(partner).listen(0, "127.0.0.1").await().actualPort();
        HttpClientAgent client = LedgerMain.client(vertx);
        NodeBenchMain.Harness h = harness(client);
        for (int i = 0; i < 2; i++) {
            NodeBenchMain.Answer a = h.exchange(HttpMethod.POST, port, "/echo", "text/plain", Buffer.buffer("call " + i));
            assertEquals(200, a.status());
            assertEquals("call " + i, a.body().toString(), "the first calls are echoed");
        }
        CompletableFuture<NodeBenchMain.Answer> held = CompletableFuture.supplyAsync(
                () -> h.exchange(HttpMethod.POST, port, "/echo", "text/plain", Buffer.buffer("held")),
                task -> Thread.ofVirtual().start(task));
        assertTrue(LedgerMain.until(vertx, Duration.ofSeconds(30), () -> partner.calls() == 3), "the third call reached the partner");
        partner.release();
        NodeBenchMain.Answer late = h.exchange(HttpMethod.POST, port, "/echo", "text/plain", Buffer.buffer("late"));
        assertEquals("late", late.body().toString(), "after the release every call is echoed");
        assertFalse(held.isDone(), "the call that came while the partner held was never answered, not even after the release");
        assertEquals(4, partner.calls(), "every call read is counted");
        assertTrue(partner.heldToKillMs() >= 0, "a call was held before now");
        assertEquals(-1, new LedgerMain.Partner(5).heldToKillMs(), "no call held: -1");
    }

    @Test
    void anAckIsTheMessageIdOfA202AndNothingElse() throws Exception {
        AtomicInteger status = new AtomicInteger(202);
        HttpServer in = vertx.createHttpServer().requestHandler((HttpServerRequest req) -> req.body().onSuccess(bytes ->
                req.response().setStatusCode(status.get()).putHeader(HttpComponent.H_MESSAGE_ID, "m-" + bytes).end())).listen(0, "127.0.0.1").await();
        HttpClientAgent client = LedgerMain.client(vertx);
        assertEquals("m-1", LedgerMain.ack(client, in.actualPort(), Buffer.buffer("1"), 30_000));
        status.set(503);
        assertNull(LedgerMain.ack(client, in.actualPort(), Buffer.buffer("2"), 30_000), "a refusal is no ACK, whatever header it carries");
        assertNull(LedgerMain.ack(client, NodeBenchMain.freePort(), Buffer.buffer("3"), 30_000), "nothing listening: no ACK");

        LedgerMain.Partner silent = new LedgerMain.Partner(0);   // holds every call: no answer ever
        int held = vertx.createHttpServer().requestHandler(silent).listen(0, "127.0.0.1").await().actualPort();
        assertNull(LedgerMain.ack(client, held, Buffer.buffer("4"), 200), "no answer within the timeout: no ACK, the call returns");
        assertEquals(1, silent.calls());
    }

    @Test
    void aMessageWithoutA202IsResentUntilItIsAckedAndALoadEndsAtItsBound() {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer in = vertx.createHttpServer().requestHandler((HttpServerRequest req) -> req.body().onSuccess(bytes -> {
            int n = attempts.incrementAndGet();   // the first two attempts are refused, every later one ACKed
            req.response().setStatusCode(n <= 2 ? 503 : 202).putHeader(HttpComponent.H_MESSAGE_ID, "m-" + n).end();
        })).listen(0, "127.0.0.1").await();
        LedgerMain.Sending sending = new LedgerMain.Sending(vertx, 3, Buffer.buffer("x"), in.actualPort(), 30_000);
        sending.load(LedgerMain.client(vertx), Integer.MAX_VALUE, new CompletableFuture<>(), Duration.ofSeconds(30));
        assertEquals(3, sending.sent.get(), "three messages, each sent once at first");
        assertEquals(2, sending.resent.get(), "the two refused attempts re-sent");
        assertEquals(3, sending.acked.size(), "every message ACKed, once: " + sending.acked);
        assertEquals(5, attempts.get());
        assertEquals(0, sending.owed.get(), "nothing owed");

        LedgerMain.Sending late = new LedgerMain.Sending(vertx, 3, Buffer.buffer("x"), in.actualPort(), 30_000);
        late.load(LedgerMain.client(vertx), Integer.MAX_VALUE, new CompletableFuture<>(), Duration.ZERO);
        assertEquals(0, late.sent.get(), "a load past its bound sends nothing");
        assertEquals(5, attempts.get());

        LedgerMain.Sending stopped = new LedgerMain.Sending(vertx, 3, Buffer.buffer("x"), in.actualPort(), 30_000);
        stopped.load(LedgerMain.client(vertx), Integer.MAX_VALUE, CompletableFuture.completedFuture(null), Duration.ofSeconds(30));
        assertEquals(0, stopped.sent.get(), "a load whose stop is done sends nothing (node 1 killed: the rest goes to node 2)");
        assertEquals(5, attempts.get());
    }

    @Test
    void untilSaysWhetherTheConditionHeldWithinTheBound() {
        AtomicInteger looks = new AtomicInteger();
        assertTrue(LedgerMain.until(vertx, Duration.ofSeconds(30), () -> looks.incrementAndGet() == 3), "true once it holds");
        assertEquals(3, looks.get(), "looked at again until it held");
        assertFalse(LedgerMain.until(vertx, Duration.ZERO, () -> false), "false once the bound passed");
    }

    @Test
    void aNode1ThatExitedOrDoesNotServeWithinTheBoundIsRefusedNamingItsOutput() throws Exception {
        NodeBenchMain.Harness h = harness(LedgerMain.client(vertx));
        Path log = Path.of("node-1.log");
        Process exited = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version").start();
        exited.waitFor();
        IllegalStateException early = assertThrows(IllegalStateException.class,
                () -> LedgerMain.awaitServing(vertx, h, exited, NodeBenchMain.freePort(), Duration.ofSeconds(30), log));
        assertTrue(early.getMessage().contains("exited with 0") && early.getMessage().contains("node-1.log"), early.getMessage());

        Process alive = new ProcessBuilder("cat").start();   // lives until its stdin closes
        try {
            int closed = NodeBenchMain.freePort();
            IllegalStateException late = assertThrows(IllegalStateException.class, () -> LedgerMain.awaitServing(vertx, h, alive, closed, Duration.ZERO, log));
            assertTrue(late.getMessage().contains("did not serve") && late.getMessage().contains(String.valueOf(closed))
                    && late.getMessage().contains("node-1.log"), late.getMessage());

            int health = vertx.createHttpServer().requestHandler(req -> req.response().setStatusCode(req.path().equals("/health") ? 200 : 404).end())
                    .listen(0, "127.0.0.1").await().actualPort();
            LedgerMain.awaitServing(vertx, h, alive, health, Duration.ofSeconds(30), log);   // returns: alive and serving
            assertEquals(200, LedgerMain.health(h, health));
            assertEquals(-1, LedgerMain.health(h, closed), "nothing listening: no status");
        } finally {
            alive.destroyForcibly();
            alive.waitFor();
        }
    }

    NodeBenchMain.Harness harness(HttpClientAgent client) {
        return new NodeBenchMain.Harness(LedgerMain.bench(LedgerMain.options(new String[] {})), vertx, null, client, Buffer.buffer("<Invoice/>"), 0, 0);
    }
}
