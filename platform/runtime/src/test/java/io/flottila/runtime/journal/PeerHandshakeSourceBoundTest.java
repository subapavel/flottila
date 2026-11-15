package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerConfig;
import io.flottila.runtime.journal.peer.PeerHandshake;
import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.journal.peer.PeerServerProbe;
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

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5a2 acceptance A4 (packet P4): node-a holds at most {@code PeerConfig.HANDSHAKES_PER_SOURCE} = 8 links in their handshake
 * per source address, beside the 64 in all; a 9th from one address is closed at once — no HELLO of node-a's comes — while the
 * first 8 wait, and its place is given back when a link of that address is refused, established or closed. On loopback every
 * connection comes from one address unless bound: the connections here bind 127.0.0.2 ({@link #X}) and 127.0.0.3 ({@link #Y}).
 * The fake timer is never advanced: no {@code FLOTTILA_PEER_TIMEOUT} closes a link here, so every close observed is a bound's or
 * a refusal's. "Held" is a connection given node-a's HELLO ({@link RawPeer#connect(InetAddress, int)}): its link is open on
 * node-a, in its handshake.
 *
 * <p>The API assumed (CORE-5a2, test-author): {@code public static final int PeerConfig.HANDSHAKES_PER_SOURCE} (8); the source
 * address is the remote host of the connection. CORE-5a2 r1 (M14): {@code PeerServer.sourcesHeld()} (package-private, read through
 * {@link PeerServerProbe}) counts the addresses with a place taken; once every link of the case has ended — established, refused
 * or closed by the dialer — it is 0, so the server keeps no entry per address ever seen. A held connection's place is taken
 * before node-a's HELLO goes out, so "held" reads at once; a place given back is seen within the bound ({@link #awaitNoSourceHeld}).
 */
@Timeout(120)
class PeerHandshakeSourceBoundTest {

    static final long BOUND_SECONDS = 20;
    static final InetAddress X = loopback(2);
    static final InetAddress Y = loopback(3);

    @TempDir Path tmp;
    Vertx vertx;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal a;
    PeerServer server;
    final List<AutoCloseable> closing = new ArrayList<>();

    static InetAddress loopback(int x) {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) x});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void open() throws Exception {
        vertx = Vertx.vertx();
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve("node-a"))).segmentAge(Duration.ofHours(1));
        s.node = "node-a";
        JournalConfig config = s.config();
        a = OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
        server = PeerServer.listen(vertx, PeerLinkTest.CONFIG, PeerLinkTest.A, a, timer).await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closing) c.close();
        server.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        a.close();
        vertx.close().await();
    }

    /** A connection from {@code from} that node-a holds in its handshake (it sent its HELLO). */
    RawPeer held(InetAddress from) throws IOException {
        RawPeer p = RawPeer.connect(from, server.port());
        closing.add(p);
        return p;
    }

    List<RawPeer> eightHeld(InetAddress from) throws IOException {
        assertEquals(8, PeerConfig.HANDSHAKES_PER_SOURCE, "the packet's bound");
        List<RawPeer> held = new ArrayList<>();
        for (int i = 0; i < 8; i++) held.add(held(from));
        return held;
    }

    /** Whether node-a closed a new connection from {@code from} at once: its first read is the end, no byte of a HELLO before. */
    boolean closedAtOnce(InetAddress from) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port(), from, 0)) {
            s.setSoTimeout((int) TimeUnit.SECONDS.toMillis(BOUND_SECONDS));
            try {
                return s.getInputStream().read() < 0;
            } catch (SocketTimeoutException open) {
                return false;
            } catch (SocketException reset) {
                return true;
            }
        }
    }

    /** Waits, within the bound, until node-a holds a place for no source address: every link of the case has ended its handshake. */
    void awaitNoSourceHeld(String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
        int held;
        while ((held = PeerServerProbe.sourcesHeld(server)) != 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + ": node-a still holds places for " + held + " source address(es) after " + BOUND_SECONDS + " s");
            }
            Thread.onSpinWait();
        }
    }

    /** The handshake of a held connection completes and a READ on it is answered: it was open, waiting. */
    static void completesAndIsServed(RawPeer p, long id, String what) throws Exception {
        p.handshakeAsDialer(PeerProtocolTest.b(), "node-a");
        p.send(RawPeer.READ, RawPeer.read(id, "node-a", PeerLinkTest.JOURNAL_A, 1, 2, 3));
        assertEquals(RawPeer.MISS, RawPeer.type(p.next()), what);
    }

    /**
     * Eight connections from X are held; a 9th from X — and 57 more after it — are closed at once, each giving back the place it
     * took among the 64 (8 + 57 = 65 would fill them); a connection from Y is held; then each of the eight from X was waiting:
     * its handshake completes and it is served.
     */
    @Test
    void aNinthHandshakeFromOneSourceAddressIsClosedAtOnceWhileTheFirstEightWait() throws Exception {
        List<RawPeer> eight = eightHeld(X);
        assertEquals(1, PeerServerProbe.sourcesHeld(server), "eight held from 127.0.0.2: one address holds places");
        for (int i = 0; i < 57; i++) assertTrue(closedAtOnce(X), "connection " + (9 + i) + " from 127.0.0.2 is closed at once");
        RawPeer y = held(Y);
        assertEquals(2, PeerServerProbe.sourcesHeld(server), "held from 127.0.0.2 and 127.0.0.3: two addresses hold places");
        completesAndIsServed(y, 100, "127.0.0.3 has its own eight; the closed ones from 127.0.0.2 kept no place of the 64");
        for (int i = 0; i < 8; i++) completesAndIsServed(eight.get(i), i + 1, "connection " + (i + 1) + " from 127.0.0.2 was waiting");
        awaitNoSourceHeld("all nine links are established");
    }

    /**
     * A refusal gives its place back: of eight held from X (a 9th closed at once) one sends a HELLO under a key the environment
     * does not hold and is refused; then a connection from X is held again and served. The place is back before node-a's close
     * of the refused link reaches it.
     */
    @Test
    void aSourceAddressGetsItsPlaceBackWhenAHandshakeIsRefused() throws Exception {
        List<RawPeer> eight = eightHeld(X);
        assertTrue(closedAtOnce(X), "fixture: a 9th from 127.0.0.2 is closed at once");
        byte[] stranger = PeerHandshake.dialer(PeerLinkTest.identity("node-b", PeerLinkTest.UNKNOWN), "node-a", PeerLinkTest.nonces(3)).hello().getBytes();
        eight.getFirst().write(stranger, 0, stranger.length);
        assertTrue(eight.getFirst().closedByPeer(), "fixture: node-a refused the HELLO under an unknown key");
        completesAndIsServed(held(X), 9, "a connection from 127.0.0.2 after the refusal is held and served");
        assertEquals(1, PeerServerProbe.sourcesHeld(server), "seven still held from 127.0.0.2: its address holds places");
        for (int i = 1; i < 8; i++) completesAndIsServed(eight.get(i), i, "connection " + (i + 1) + " from 127.0.0.2 was waiting");
        awaitNoSourceHeld("one link refused, the other eight established");
    }

    /** An established link gives its place back: one of eight held from X completes and is served; then a 9th from X is held. */
    @Test
    void aSourceAddressGetsItsPlaceBackWhenAHandshakeCompletes() throws Exception {
        List<RawPeer> eight = eightHeld(X);
        assertTrue(closedAtOnce(X), "fixture: a 9th from 127.0.0.2 is closed at once");
        completesAndIsServed(eight.getFirst(), 1, "fixture: the first from 127.0.0.2 is proven");
        completesAndIsServed(held(X), 2, "with one proven, a connection from 127.0.0.2 is held and served");
        assertEquals(1, PeerServerProbe.sourcesHeld(server), "seven still held from 127.0.0.2: its address holds places");
        for (int i = 1; i < 8; i++) completesAndIsServed(eight.get(i), i + 2, "connection " + (i + 1) + " from 127.0.0.2 was waiting");
        awaitNoSourceHeld("all nine links are established");
    }

    /**
     * A link the dialer closes in its handshake gives its place back: one of eight held from X closes; a connection from X is
     * then held — node-a sees the close at a moment the dialer cannot observe, so new connections are tried until one is held
     * (each before it closed at once), within the bound.
     */
    @Test
    void aSourceAddressGetsItsPlaceBackWhenTheDialerClosesAHeldConnection() throws Exception {
        List<RawPeer> eight = eightHeld(X);
        assertTrue(closedAtOnce(X), "fixture: a 9th from 127.0.0.2 is closed at once");
        eight.getFirst().close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
        RawPeer again = null;
        int tries = 0;
        while (again == null) {
            tries++;
            try {
                again = held(X);
            } catch (EOFException | SocketException closedAtOnce) {
                if (System.nanoTime() > deadline) throw new AssertionError("no connection from 127.0.0.2 held within " + BOUND_SECONDS + " s, " + tries + " tries");
            }
        }
        completesAndIsServed(again, 3, "a connection from 127.0.0.2 after the close is held and served");
        assertEquals(1, PeerServerProbe.sourcesHeld(server), "seven still held from 127.0.0.2: its address holds places");
        for (int i = 1; i < 8; i++) eight.get(i).close();
        awaitNoSourceHeld("the dialer closed the other seven held links, the one held again is established");
    }
}
