package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.journal.peer.ScriptedPeer;
import io.flottila.runtime.journal.peer.ScriptedPeer.Hello;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.flottila.runtime.journal.PeerLinkTest.A;
import static io.flottila.runtime.journal.PeerLinkTest.CONFIG;
import static io.flottila.runtime.journal.PeerLinkTest.ENV;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CORE-5a2 O1 (implementer, beside {@link PeerHelloLinkTest}): the bytes after a HELLO's v1 fields count against the HELLO cap
 * the longest node id fills. A dialer's HELLO whose node id is 9 bytes shorter than the longest and which carries 9 bytes after
 * its v1 fields — exactly the cap — links and is served; the longest node id with one byte after it is past the cap: node-a
 * closes the link after its own HELLO, no proof sent. The fake timer is never advanced: every close observed is a refusal.
 */
@Timeout(120)
class PeerHelloCapTest {

    static final long BOUND_SECONDS = 20;

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
        server = PeerServer.listen(vertx, CONFIG, A, a, timer).await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        server.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        a.close();
        vertx.close().await();
    }

    static Hello dialer(int nodeLength, int trailing) {
        return Hello.dialer(PeerProtocolTest.b(), PeerHandshakeVersionTest.nonce(2)).node("n".repeat(nodeLength)).trailing(new byte[trailing]);
    }

    @Test
    void aHelloFillingTheCapWithBytesAfterItsV1FieldsLinks() throws Exception {
        try (ScriptedPeer p = ScriptedPeer.dial(null, server.port(), dialer(0xFFFF - 9, 9), ENV)) {
            PeerHelloLinkTest.answered(p, 11, "a READ on the link of a HELLO exactly at the cap is answered");
        }
    }

    @Test
    void aHelloOneBytePastTheCapIsRefused() throws Exception {
        assertEquals(List.of(ScriptedPeer.HELLO), ScriptedPeer.refusedAfter(server.port(), dialer(0xFFFF, 1)),
                "node-a closed after its HELLO: the longest node id and a byte after it");
    }
}
