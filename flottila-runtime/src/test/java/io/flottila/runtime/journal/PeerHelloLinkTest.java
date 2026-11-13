package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerReads;
import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.journal.peer.RawPeer;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static io.flottila.runtime.journal.PeerLinkTest.A;
import static io.flottila.runtime.journal.PeerLinkTest.B;
import static io.flottila.runtime.journal.PeerLinkTest.CONFIG;
import static io.flottila.runtime.journal.PeerLinkTest.ENV;
import static io.flottila.runtime.journal.PeerLinkTest.JOURNAL_A;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5a2 acceptance A1, A2 over a socket (packet P1, P2): node-a's {@code PeerServer} on loopback links with a dialer whose
 * HELLO is of version 2 with bytes after its v1 fields, and node-b's {@code PeerReads} with such a listener; both at version 1
 * — the version node-a and node-b send, the frames after the proofs v1 frames. A HELLO of version 0, one shorter than its v1
 * fields, or on channel repl or bulk is refused by the listener: it closes the link after its own HELLO, no proof sent. The
 * other side is a {@link ScriptedPeer} (keys and proofs from the packet's text; it fails a handshake whose proof is not the one
 * over both whole HELLOs). The fake timer is never advanced: no {@code FLOTTILA_PEER_TIMEOUT} closes a link here, so every close
 * observed is a refusal.
 */
@Timeout(120)
class PeerHelloLinkTest {

    static final long BOUND_SECONDS = 20;
    static final byte[] TRAILING = PeerHandshakeVersionTest.TRAILING;

    @TempDir Path tmp;
    Vertx vertx;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal a;
    Journal b;
    PeerServer server;
    PeerReads reader;
    ServerSocket listening;

    @BeforeEach
    void open() throws Exception {
        vertx = Vertx.vertx();
        a = journal("node-a");
        b = journal("node-b");
        server = PeerServer.listen(vertx, CONFIG, A, a, timer).await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        if (reader != null) reader.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        if (listening != null) listening.close();
        server.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        a.close();
        b.close();
        vertx.close().await();
    }

    Journal journal(String node) throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve(node))).segmentAge(Duration.ofHours(1));
        s.node = node;
        JournalConfig config = s.config();
        return OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
    }

    /** A READ of node-a's journal at a segment it does not hold, sent on {@code p}, is answered MISS: the link is proven and served. */
    static void answered(ScriptedPeer p, long id, String what) throws Exception {
        p.send(ScriptedPeer.READ, RawPeer.read(id, "node-a", JOURNAL_A, 1, 2, 3));
        byte[] f = p.next();
        assertEquals(ScriptedPeer.MISS, ScriptedPeer.type(f), what);
        assertEquals(id, ScriptedPeer.payload(f).getLong(), what + ": the MISS of that READ");
    }

    /** A1, toward the listener: node-a links with a dialer speaking version 2 with trailing bytes, at version 1, and serves it. */
    @Test
    void aDialerSpeakingVersion2WithTrailingBytesLinksWithTheListenerAtVersion1() throws Exception {
        try (ScriptedPeer p = ScriptedPeer.dial(null, server.port(), Hello.dialer(PeerProtocolTest.b(), PeerHandshakeVersionTest.nonce(2))
                .version(2).trailing(TRAILING), ENV)) {
            assertEquals(1, p.theirHello()[0], "node-a speaks version 1");
            answered(p, 7, "a READ on the link is answered");
        }
    }

    /**
     * A1, toward the dialer: node-b's reader links with a listener speaking version 2 with trailing bytes, at version 1 — its
     * READ comes as a v1 frame under the session; the listener answers MISS and the read fails retryable, naming node-a.
     */
    @Test
    void aListenerSpeakingVersion2WithTrailingBytesLinksWithTheDialerAtVersion1() throws Exception {
        listening = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        reader = new PeerReads(vertx, CONFIG, B, b, timer, owner -> Optional.of("127.0.0.1:" + listening.getLocalPort()));
        OnVirtualThread.Call<byte[]> call = PeerProtocolTest.read(reader, PeerProtocolTest.KEY, new JournalRef(1, 2, 3));
        try (ScriptedPeer p = ScriptedPeer.accept(listening, Hello.listener(A, PeerHandshakeVersionTest.nonce(1)).version(2).trailing(TRAILING), ENV)) {
            assertEquals(1, p.theirHello()[0], "node-b speaks version 1");
            byte[] read = p.next();
            assertEquals(ScriptedPeer.READ, ScriptedPeer.type(read), "node-b's READ on the established link");
            p.send(ScriptedPeer.MISS, Arrays.copyOfRange(read, 13, 21));   // its read id, after u32 len · type · seq
            PeerProtocolTest.failedRetryable(call, "a read its owner answered MISS");
        }
    }

    /**
     * P1: the HELLO cap counts the channel byte: a dialer's v1 HELLO naming a node id of 65 535 bytes (the longest a u16 names)
     * links and is served.
     */
    @Test
    void aDialersHelloNamingTheLongestNodeIdLinks() throws Exception {
        try (ScriptedPeer p = ScriptedPeer.dial(null, server.port(), Hello.dialer(PeerProtocolTest.b(), PeerHandshakeVersionTest.nonce(2))
                .node("n".repeat(0xFFFF)), ENV)) {
            answered(p, 8, "a READ on the link of the longest HELLO is answered");
        }
    }

    /**
     * A1: node-a refuses a dialer's HELLO of version 0, one byte shorter than its v1 fields, or of the 5a layout without the
     * channel byte: after its own HELLO it closes the link, no proof sent. Witness: {@link #aDialerOnTheReadChannelLinksAsToday}.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"version 0", "one byte short", "no channel byte"})
    void aHelloOfVersion0OrShorterThanItsV1FieldsIsRefusedByTheListener(String what) throws Exception {
        Function<Hello, Hello> broken = switch (what) {
            case "version 0" -> h -> h.version(0);
            case "one byte short" -> h -> h.cut(1);
            default -> Hello::withoutChannel;
        };
        Hello hello = broken.apply(Hello.dialer(PeerProtocolTest.b(), PeerHandshakeVersionTest.nonce(2)));
        assertEquals(List.of(ScriptedPeer.HELLO), ScriptedPeer.refusedAfter(server.port(), hello), "node-a closed after its HELLO: " + what);
    }

    /** A2: node-a refuses a dialer on channel repl or bulk: it closes the link after its own HELLO, no proof sent. */
    @ParameterizedTest(name = "channel {0}")
    @ValueSource(ints = {ScriptedPeer.CHANNEL_REPL, ScriptedPeer.CHANNEL_BULK})
    void aDialerOnTheReplOrBulkChannelIsRefusedByTheListener(int channel) throws Exception {
        Hello hello = Hello.dialer(PeerProtocolTest.b(), PeerHandshakeVersionTest.nonce(2)).channel(channel);
        assertEquals(List.of(ScriptedPeer.HELLO), ScriptedPeer.refusedAfter(server.port(), hello), "node-a closed after its HELLO: channel " + channel);
    }

    /**
     * A2: channel read links as today — the same scripted dialer the refusals above vary, and node-b's own handshake
     * ({@link RawPeer}) — and both are served by node-a.
     */
    @Test
    void aDialerOnTheReadChannelLinksAsToday() throws Exception {
        try (ScriptedPeer p = ScriptedPeer.dial(null, server.port(), Hello.dialer(PeerProtocolTest.b(), PeerHandshakeVersionTest.nonce(2))
                .channel(ScriptedPeer.CHANNEL_READ), ENV)) {
            assertEquals(ScriptedPeer.CHANNEL_READ, p.theirHello()[2], "node-a's HELLO names channel read");
            answered(p, 9, "the scripted dialer on channel read is served");
        }
        try (RawPeer raw = RawPeer.dial(server.port(), PeerProtocolTest.b(), "node-a")) {
            raw.send(RawPeer.READ, RawPeer.read(10, "node-a", JOURNAL_A, 1, 2, 3));
            assertTrue(RawPeer.type(raw.next()) == RawPeer.MISS, "node-b's own handshake is served");
        }
    }
}
