package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerConfig;
import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.journal.peer.RawPeer;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * r2 R1 with R2 (CORE-5a, implementer): a connection the listener could not open a link on gives its place among the
 * {@value PeerConfig#HANDSHAKES_MAX} held in their handshake back. node-a's timer refuses the watchdog of the first 64 links,
 * so each of 64 connections is closed at once; the 65th — had each kept its place, the bound would close it too — completes
 * its handshake and its READ is answered.
 */
@Timeout(120)
class PeerServerHandshakeReleaseTest {

    @TempDir Path tmp;
    Vertx vertx;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal a;
    PeerServer server;

    @BeforeEach
    void open() throws Exception {
        vertx = Vertx.vertx();
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-a"))).segmentAge(Duration.ofHours(1));
        s.node = "node-a";
        JournalConfig config = s.config();
        a = OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
        server = PeerServer.listen(vertx, PeerLinkTest.CONFIG, PeerLinkTest.identity("node-a", PeerLinkTest.ENV), a,
                PeerProtocolTest.refusingFirst(timer, PeerConfig.HANDSHAKES_MAX)).await(20, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        server.close().await(20, TimeUnit.SECONDS);
        a.close();
        vertx.close().await();
    }

    @Test
    void connectionsWhoseLinkCouldNotOpenLeaveNoPlaceTakenInTheHandshakeBound() throws Exception {
        for (int i = 0; i < PeerConfig.HANDSHAKES_MAX; i++) {
            try (Socket refused = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                assertTrue(RawPeer.closedByPeer(refused), "fixture: connection " + (i + 1) + " is closed, its link could not open");
            }
        }
        try (RawPeer next = RawPeer.dial(server.port(), PeerProtocolTest.b(), "node-a")) {
            next.send(RawPeer.READ, RawPeer.read(1, "node-a", PeerLinkTest.JOURNAL_A, 1, 2, 3));
            assertEquals(RawPeer.MISS, RawPeer.type(next.next()), "the 65th connection is held, proven and answered");
        }
    }
}
