package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.BlobStore;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.TestJournalKey;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.flottila.runtime.journal.JournalWorld.bytes;
import static io.flottila.runtime.journal.JournalWorld.causes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-5a r2 (review r1): the peer link as two real nodes wire it — {@link Node#start} twice, in this JVM, on one Postgres,
 * each its own data directory and {@code FLOTTILA_PEER_PORT} 0. A body node-a's store made durable (its journal, its
 * {@code body_refs} row) reads on node-b's store byte for byte, which it can only do through node-a's link: node-b never held
 * it. That pins what no unit case does: the journal id node-a's listener answers under is the one {@code journal_nodes} holds
 * for it ({@code Journal.id()}), the address node-a's row publishes carries the port its listener bound (not the configured
 * 0), and node-b dials the address its renew answered ({@code members::peerAddress}). node-b's reads wait for its first renew
 * after node-a joined — until then node-a is "not live" to it, a retryable failure the case retries; any other failure is
 * the case's red at once.
 */
@Timeout(180)
class NodePeerReadIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static Vertx vertx;

    @TempDir Path data;
    CaseDatabase db;
    final List<Node> nodes = new ArrayList<>();

    @BeforeAll
    static void start() {
        pg.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() {
        vertx.close().await();
        pg.stop();
    }

    @BeforeEach
    void caseDatabase() throws Exception {
        db = CaseDatabase.create(pg, vertx, "node_peer_");
    }

    @AfterEach
    void closeEverything() throws Exception {
        try {
            for (Node n : nodes.reversed()) OnVirtualThread.call(() -> { n.close(); return null; });
        } finally {
            db.close();
        }
    }

    /** Node {@code node} on the case's database, its peer link on 127.0.0.1, any free port; a short node lease, renewed often. */
    Map<String, String> env(String node) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", node);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve(node)).toString());
        env.put("FLOTTILA_PG_HOST", pg.getHost());
        env.put("FLOTTILA_PG_PORT", String.valueOf(pg.getMappedPort(5432)));
        env.put("FLOTTILA_PG_DATABASE", db.name());
        env.put("FLOTTILA_PG_USER", pg.getUsername());
        env.put("FLOTTILA_PG_PASSWORD", pg.getPassword());
        env.put("FLOTTILA_PG_POOL_SIZE", "4");
        env.put("FLOTTILA_PEER_BIND", "127.0.0.1");
        env.put("FLOTTILA_PEER_HOST", "127.0.0.1");
        env.put("FLOTTILA_PEER_PORT", "0");
        env.put("FLOTTILA_NODE_LEASE", "PT2S");
        env.put("FLOTTILA_NODE_LEASE_RENEW", "PT0.5S");
        TestJournalKey.with(env);   // one master key for both: the environment's (D-106)
        return env;
    }

    /** Starts node {@code node}; answers its store (the one its components are given). */
    BlobStore start(String node) throws Exception {
        NodeConfig config = NodeConfig.from(env(node));
        AtomicReference<BlobStore> store = new AtomicReference<>();
        Node n = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> {
            store.set(ctx.blobs());
            return List.of();
        }, List.of(), List.of()));
        nodes.add(n);
        return store.get();
    }

    @Test
    void aBodyMadeDurableOnOneNodeReadsOnAnotherThroughThePeerLinkAtThePortItBound() throws Exception {
        BlobStore onA = start("node-a");
        BlobStore onB = start("node-b");
        BlobKey key = BlobKey.body("m-peer", 0);
        Buffer body = bytes(3_000, 41);
        OnVirtualThread.call(() -> {
            onA.write(key, body, 1L << 20).await(20, TimeUnit.SECONDS);
            onA.persist(key).await(20, TimeUnit.SECONDS);
            return null;
        });
        Row ref = db.pool().preparedQuery("select node from body_refs where message_id = $1 and step = 0").execute(Tuple.of("m-peer")).await().iterator().next();
        assertEquals("node-a", ref.getString(0), "fixture: the body lies in node-a's journal");

        String address = db.pool().preparedQuery("select peer_address from nodes where node = $1").execute(Tuple.of("node-a")).await()
                .iterator().next().getString(0);
        assertTrue(address != null && address.startsWith("127.0.0.1:"), "node-a's row publishes FLOTTILA_PEER_HOST:port: " + address);
        int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        assertNotEquals(0, port, "the port node-a's listener bound, not FLOTTILA_PEER_PORT 0: " + address);
        assertTrue(listening(port), "node-a's link listens at the port its row publishes: " + address);

        assertEquals(body, readOnB(onB, key), "node-b reads node-a's body byte for byte, through node-a's link");
    }

    /** Whether something on 127.0.0.1 accepts a connection at {@code port}. */
    static boolean listening(int port) {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            return s.isConnected();
        } catch (java.io.IOException refused) {
            return false;
        }
    }

    /**
     * {@code key}'s bytes read through {@code onB}; a failure naming node-a "not live" (node-b's renew has not answered node-a's
     * address yet) is retried, bounded; any other failure fails the case.
     */
    static Buffer readOnB(BlobStore onB, BlobKey key) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            Future<Buffer> read = JournalWorld.opened(onB, key);
            if (read.succeeded()) return read.result();
            List<String> chain = causes(read.cause());
            boolean notLiveYet = chain.stream().anyMatch(c -> c.contains("not live"));
            if (!notLiveYet) fail("node-b's read of node-a's body failed: " + chain);
            if (System.nanoTime() - deadline > 0) fail("node-b never learned node-a's peer address within 20 s (its renews): " + chain);
            Thread.onSpinWait();
        }
    }
}
