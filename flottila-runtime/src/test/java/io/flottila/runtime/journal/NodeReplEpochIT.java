package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.RawPeer;
import io.flottila.runtime.journal.peer.ReplWire;
import io.flottila.runtime.node.Node;
import io.flottila.runtime.node.NodeConfig;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.runtime.node.TestJournalKey;
import io.flottila.testkit.PostgresImage;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.SecretKey;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-5b1 (review F2): a node's peer server bounds a repl link by the epochs its membership's renew answered — Node wires
 * {@code PeerServer.listen}'s {@code epochOf} to the membership once it exists. node-a starts on a database whose {@code nodes}
 * already holds node-b live at epoch 5 (with a peer address: a live peer the renew answers); once node-a renewed twice after
 * its join (the second renew is sent only after the first was answered, so node-b's epoch is node-a's), a repl dial as node-b
 * at epoch 4 — node-a's first link from node-b, so no epoch it accepted before can bound it — is closed unanswered, and one at
 * epoch 5 is served (its OPEN receipted OPENED).
 */
@Timeout(180)
class NodeReplEpochIT {

    static final PostgreSQLContainer pg = new PostgreSQLContainer(PostgresImage.name());
    static final UUID JOURNAL_B = UUID.fromString("0b0b0b0b-0000-4000-8000-0000000005b1");
    static final long EPOCH_B = 5;
    static Vertx vertx;

    @TempDir Path data;
    CaseDatabase db;
    Node node;

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
        db = CaseDatabase.create(pg, vertx, "node_repl_epoch_");
    }

    @AfterEach
    void closeEverything() throws Exception {
        try {
            if (node != null) OnVirtualThread.call(() -> { node.close(); return null; });
        } finally {
            db.close();
        }
    }

    Map<String, String> env(String id) throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("FLOTTILA_ENV", "ci");
        env.put("FLOTTILA_NODE_ID", id);
        env.put("FLOTTILA_ADMIN_PORT", "0");
        env.put("FLOTTILA_DATA_DIR", Files.createDirectories(data.resolve(id)).toString());
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
        TestJournalKey.with(env);   // the environment's master key: node-b's HELLO proves it as node-a's does (D-106)
        return env;
    }

    @Test
    void aJoinedNodeClosesAReplDialBelowTheOwnersLiveEpochAndServesOneAtIt() throws Exception {
        db.migrated();
        db.pool().preparedQuery("insert into nodes(node, epoch, lease_until, peer_address) values ($1, $2, now() + interval '1 hour', $3)")
                .execute(Tuple.of("node-b", EPOCH_B, "127.0.0.1:1")).await();
        NodeConfig config = NodeConfig.from(env("node-a"));
        SecretKey master = config.journalKey().load(config.journal().dir(), JournalFiles.real());
        node = OnVirtualThread.call(() -> Node.start(config, vertx, ctx -> List.of(), List.of(), List.of()));
        renewedTwice();
        int port = port();

        PeerIdentity below = new PeerIdentity("node-b", EPOCH_B - 1, JOURNAL_B, List.of(master));
        closedUnanswered(port, below);

        PeerIdentity at = new PeerIdentity("node-b", EPOCH_B, JOURNAL_B, List.of(master));
        try (RawPeer b = RawPeer.dial(port, at, "node-a", ReplWire.CHANNEL_REPL)) {
            byte[] header = ReplWire.header("node-b", JOURNAL_B, 1, 1_700_000_000_000L);
            b.send(ReplWire.OPEN, ReplWire.open("node-b", JOURNAL_B, 1, header.length + 1024, header));
            ReplWire.Answer a = ReplWire.answer(b.next());
            assertTrue(a.is(ReplWire.RECEIPT, 1, ReplWire.OPENED), "a repl link at node-b's live epoch " + EPOCH_B + " is served: " + a);
            assertEquals(header.length, a.end(), "RECEIPT OPENED's end is the header's length");
        }
    }

    /**
     * Waits (bounded) until node-a's {@code lease_until} changed twice since its join: a renew answered after the join (it saw
     * node-b's row, inserted before the join), and the next one sent only after that answer was taken (one renew in flight).
     */
    void renewedTwice() throws Exception {
        String joined = leaseUntil();
        String seen = joined;
        int changes = 0;
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (changes < 2) {
            if (System.nanoTime() - deadline > 0) fail("node-a's lease_until changed " + changes + " times in 30 s since its join (" + joined + ")");
            String now = leaseUntil();
            if (!now.equals(seen)) {
                changes++;
                seen = now;
            }
            Thread.onSpinWait();
        }
    }

    String leaseUntil() {
        return db.pool().preparedQuery("select lease_until::text from nodes where node = $1").execute(Tuple.of("node-a")).await()
                .iterator().next().getString(0);
    }

    int port() {
        String address = db.pool().preparedQuery("select peer_address from nodes where node = $1").execute(Tuple.of("node-a")).await()
                .iterator().next().getString(0);
        return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
    }

    /**
     * A repl dial as {@code who}, then an OPEN, must be closed by node-a — inside the handshake or at the OPEN — before any
     * answer: an answer fails the case at once (it was served), and so does a link left open past RawPeer's bound.
     */
    static void closedUnanswered(int port, PeerIdentity who) throws IOException {
        RawPeer raw;
        try {
            raw = RawPeer.dial(port, who, "node-a", ReplWire.CHANNEL_REPL);
        } catch (EOFException | SocketException closedInTheHandshake) {
            return;
        }
        try (raw) {
            byte[] header = ReplWire.header(who.node(), who.journal(), 9, 1_700_000_000_000L);
            raw.send(ReplWire.OPEN, ReplWire.open(who.node(), who.journal(), 9, header.length + 1024, header));
            byte[] answer = raw.next();
            fail("a repl link of node-b at epoch " + who.epoch() + " below its live epoch " + EPOCH_B + " was served: " + ReplWire.answer(answer));
        } catch (EOFException | SocketException closed) {
            // closed unanswered
        }
    }
}
