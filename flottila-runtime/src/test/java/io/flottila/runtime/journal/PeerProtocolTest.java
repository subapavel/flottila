package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.api.Hasher;
import io.flottila.api.Timer;
import io.flottila.runtime.blob.RemoteBodyException;
import io.flottila.runtime.journal.peer.FrameRelay;
import io.flottila.runtime.journal.peer.PeerConfig;
import io.flottila.runtime.journal.peer.PeerHandshake;
import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.PeerReads;
import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.journal.peer.RawPeer;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The peer link's refusals and limits beyond {@link PeerLinkTest} (CORE-5a, implementer): what a peer that holds the
 * environment's key but breaks the protocol gets — an authenticated frame of a type or a size the other side does not take, a
 * replayed frame, an answer to no READ, more READs open than any reader may have — and the link's watchdog
 * ({@code FLOTTILA_PEER_TIMEOUT} on the injected timer): a link not established, and a read without an answer, close; a link
 * its own reader paused does not. node-a's journal holds a small record and a large one (spilled, 32 MiB + 3: above the read
 * buffer's pause mark and the loopback socket buffers); node-a's {@code PeerServer} listens on loopback; the misbehaving side is a {@link RawPeer} running the
 * real handshake over a plain socket.
 */
@Timeout(120)
class PeerProtocolTest {

    static final UUID JOURNAL_A = PeerLinkTest.JOURNAL_A;
    static final UUID JOURNAL_B = PeerLinkTest.JOURNAL_B;
    static final PeerConfig CONFIG = PeerLinkTest.CONFIG;
    static final BlobKey KEY = BlobKey.body("m-1", 0);
    static final BlobKey LARGE = BlobKey.body("m-large", 0);
    static final long LARGE_SIZE = (32L << 20) + 3;
    static final long BOUND_SECONDS = 20;

    @TempDir Path tmp;
    Vertx vertx;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal a;
    Journal b;
    byte[] body;
    JournalRef ref;
    JournalRef large;
    PeerServer server;
    final List<AutoCloseable> closing = new ArrayList<>();
    final List<PeerReads> readers = new ArrayList<>();

    @BeforeEach
    void open() throws Exception {
        vertx = Vertx.vertx();
        a = journal("node-a");
        b = journal("node-b");
        body = JournalFixture.body("m-1", 3_000);
        ref = OnVirtualThread.call(() -> {
            Journal.Appended appended = a.append(new JournalRecord("m-1", 0, BlobKey.BODY, JournalFixture.KEY, body), Promise.promise().future());
            appended.forced().await(BOUND_SECONDS, TimeUnit.SECONDS);
            return appended.ref();
        });
        large = OnVirtualThread.call(() -> a.spill(LARGE, JournalFixture.KEY, new GeneratedInput(61, LARGE_SIZE), LARGE_SIZE, Hasher.sha256()).persist());
        server = PeerServer.listen(vertx, CONFIG, PeerLinkTest.identity("node-a", PeerLinkTest.ENV), a, timer).await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        for (PeerReads r : readers) r.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        for (AutoCloseable c : closing) c.close();
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

    static PeerIdentity b() {
        return PeerLinkTest.identity("node-b", PeerLinkTest.ENV);
    }

    /** node-b's reader of node-a at the address {@code live} names for it (absent: not live), its links on {@code on}. */
    PeerReads reader(Map<String, String> live, FakeTimer on) {
        PeerReads r = new PeerReads(vertx, CONFIG, b(), this.b, on, owner -> Optional.ofNullable(live.get(owner)));
        readers.add(r);
        return r;
    }

    PeerReads reader(String address) {
        return reader(new ConcurrentHashMap<>(Map.of("node-a", address)), timer);
    }

    static OnVirtualThread.Call<byte[]> read(PeerReads r, BlobKey key, JournalRef at) {
        return OnVirtualThread.start(() -> {
            try (InputStream in = r.open(key, "node-a", JOURNAL_A, at)) {
                return in.readAllBytes();
            }
        });
    }

    FrameRelay relay() throws Exception {
        FrameRelay r = new FrameRelay("127.0.0.1", server.port());
        closing.add(r);
        return r;
    }

    ServerSocket fakeListener() throws Exception {
        ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        closing.add(s);
        return s;
    }

    static RemoteBodyException failedRetryable(OnVirtualThread.Call<byte[]> call, String what) throws Exception {
        try {
            byte[] answered = call.outcome().get(BOUND_SECONDS, TimeUnit.SECONDS);
            fail(what + ": answered " + answered.length + " bytes");
            return null;
        } catch (java.util.concurrent.TimeoutException pending) {
            throw new AssertionError(what + ": the read neither answered nor failed within " + BOUND_SECONDS + " s (its thread: "
                    + java.util.Arrays.toString(call.thread().getStackTrace()) + ")");
        } catch (ExecutionException failed) {
            RemoteBodyException r = assertInstanceOf(RemoteBodyException.class, failed.getCause(), what + ": " + JournalWorld.causes(failed.getCause()));
            assertTrue(String.valueOf(r.getMessage()).contains("node-a"), what + ": naming the owner: " + r.getMessage());
            return r;
        }
    }

    static byte[] bytesOf(OnVirtualThread.Call<byte[]> call, String what) throws Exception {
        try {
            return call.outcome().get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError(what + ": the read failed", failed.getCause());
        }
    }

    // ---------------------------------------------------------------------------------------------------- the listener's refusals

    /**
     * An authenticated frame the listener does not take closes the link: a BYTES frame (a listener is never answered), READs
     * whose payload does not add up (too short for its owner's length, an owner length that overruns it), and a READ sent
     * twice (a replay: its sequence number is not the next one). Witness: on a link of its own, the same well-formed READ is
     * answered with the record's bytes.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BYTES to a listener", "a READ of 5 bytes", "a READ whose owner length overruns it", "a replayed READ"})
    void anAuthenticatedFrameTheListenerDoesNotTakeClosesTheLink(String what) throws Exception {
        try (RawPeer honest = RawPeer.dial(server.port(), b(), "node-a")) {
            honest.send(RawPeer.READ, RawPeer.read(1, "node-a", JOURNAL_A, ref.segment(), ref.position(), ref.length()));
            byte[] answer = honest.next();
            assertEquals(RawPeer.BYTES, RawPeer.type(answer), "witness: a well-formed READ is answered with BYTES");
            assertEquals(8 + ref.length(), RawPeer.payload(answer).remaining(), "witness: the record's bytes in one frame");
        }
        try (RawPeer peer = RawPeer.dial(server.port(), b(), "node-a")) {
            byte[] good = RawPeer.read(1, "node-a", JOURNAL_A, ref.segment(), ref.position(), ref.length());
            switch (what) {
                case "BYTES to a listener" -> peer.send(RawPeer.BYTES, new byte[16]);
                case "a READ of 5 bytes" -> peer.send(RawPeer.READ, new byte[5]);
                case "a READ whose owner length overruns it" -> {
                    ByteBuffer.wrap(good).putShort(8, (short) 200);
                    peer.send(RawPeer.READ, good);
                }
                default -> {
                    peer.send(RawPeer.READ, good);
                    assertEquals(RawPeer.BYTES, RawPeer.type(peer.next()), "fixture: the first READ is answered");
                    peer.replay();
                }
            }
            assertTrue(peer.closedByPeer(), what + ": node-a closed the link");
        }
    }

    /** A frame length below the shortest frame (type, sequence, tag) closes the link from the length alone, both directions. */
    @ParameterizedTest(name = "a length of 24 toward the {0}")
    @org.junit.jupiter.params.provider.EnumSource(value = FrameRelay.Side.class, names = {"LISTENER", "DIALER"})
    void aFrameLengthBelowTheShortestFrameClosesTheLink(FrameRelay.Side receiver) throws Exception {
        FrameRelay wire = relay();
        PeerReads r = reader(wire.address());
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "witness: the link before"));
        (receiver == FrameRelay.Side.LISTENER ? wire.toListener() : wire.toDialer()).inject(new byte[] {0, 0, 0, 24});
        assertEquals(receiver, wire.awaitClosedBy(0, "the short frame's receiver closes"), "closed by its receiver: " + wire.describe());
    }

    /** A READ naming another owner — with node-a's own journal id — answers MISS on node-a, and the link stays. */
    @Test
    void aReadNamingAnotherOwnerAnswersMiss() throws Exception {
        try (RawPeer peer = RawPeer.dial(server.port(), b(), "node-a")) {
            peer.send(RawPeer.READ, RawPeer.read(7, "node-c", JOURNAL_A, ref.segment(), ref.position(), ref.length()));
            byte[] answer = peer.next();
            assertEquals(RawPeer.MISS, RawPeer.type(answer), "MISS for another owner's record");
            assertEquals(7, RawPeer.payload(answer).getLong(0), "for that READ");
            peer.send(RawPeer.READ, RawPeer.read(8, "node-a", JOURNAL_A, ref.segment(), ref.position(), ref.length()));
            assertEquals(RawPeer.BYTES, RawPeer.type(peer.next()), "the link stays: the next READ is answered");
        }
    }

    /**
     * A dialer with more than 256 READs open breaks the protocol (FLOTTILA_PEER_READS is at most 256): node-a closes the link.
     * The dialer never reads, so node-a's answers to the large record fill the socket and its READs stay open.
     */
    @Test
    void aDialerWithMoreThan256ReadsOpenIsClosed() throws Exception {
        try (RawPeer peer = RawPeer.dial(server.port(), b(), "node-a")) {
            for (int id = 1; id <= 257; id++) peer.send(RawPeer.READ, RawPeer.read(id, "node-a", JOURNAL_A, large.segment(), large.position(), large.length()));
            assertTrue(peer.closedByPeer(), "node-a closed the link of the 257th READ");
        }
    }

    /**
     * node-a serves at most FLOTTILA_PEER_READS of one link's READs at once, whatever the dialer sends: with a bound of 1, the
     * small record's READ sent right after the large one's is answered only after every byte of the large record.
     */
    @Test
    void theOwnerServesAtMostPeerReadsOfALinkAtOnce() throws Exception {
        PeerConfig one = new PeerConfig(CONFIG.bind(), 0, CONFIG.host(), CONFIG.timeout(), 1);
        PeerServer single = PeerServer.listen(vertx, one, PeerLinkTest.identity("node-a", PeerLinkTest.ENV), a, timer).await(BOUND_SECONDS, TimeUnit.SECONDS);
        closing.add(() -> single.close().await(BOUND_SECONDS, TimeUnit.SECONDS));
        try (RawPeer peer = RawPeer.dial(single.port(), b(), "node-a")) {
            peer.send(RawPeer.READ, RawPeer.read(1, "node-a", JOURNAL_A, large.segment(), large.position(), large.length()));
            peer.send(RawPeer.READ, RawPeer.read(2, "node-a", JOURNAL_A, ref.segment(), ref.position(), ref.length()));
            long largeBefore = 0;
            while (true) {
                byte[] f = peer.next();
                assertEquals(RawPeer.BYTES, RawPeer.type(f), "node-a answers both READs with BYTES");
                ByteBuffer p = RawPeer.payload(f);
                if (p.getLong(0) == 2) break;
                largeBefore += p.remaining() - 8;
            }
            assertEquals(large.length(), largeBefore, "the second READ waited for the first to be served whole");
        }
    }

    /** An established link no READ waits on outlives node-a's timeouts: the READ after them is answered on it. */
    @Test
    void anIdleLinkOutlivesTheListenersTimeouts() throws Exception {
        try (RawPeer peer = RawPeer.dial(server.port(), b(), "node-a")) {
            peer.send(RawPeer.READ, RawPeer.read(1, "node-a", JOURNAL_A, ref.segment(), ref.position(), ref.length()));
            assertEquals(RawPeer.BYTES, RawPeer.type(peer.next()), "fixture: the link is established and serves");
            for (int i = 0; i < 3; i++) clock.advance(CONFIG.timeout());
            peer.send(RawPeer.READ, RawPeer.read(2, "node-a", JOURNAL_A, ref.segment(), ref.position(), ref.length()));
            assertEquals(RawPeer.BYTES, RawPeer.type(peer.next()), "the READ after the timeouts is answered on the same link");
        }
    }

    /** A segment file that ends inside the record (cut on disk) answers MISS, never fewer bytes than asked; the link stays. */
    @Test
    void aSegmentCutInsideTheRecordAnswersMiss() throws Exception {
        try (java.nio.channels.FileChannel seg = java.nio.channels.FileChannel.open(segmentOf("node-a", ref.segment()),
                java.nio.file.StandardOpenOption.WRITE)) {
            seg.truncate(ref.position() + ref.length() - 1);
        }
        try (RawPeer peer = RawPeer.dial(server.port(), b(), "node-a")) {
            peer.send(RawPeer.READ, RawPeer.read(1, "node-a", JOURNAL_A, ref.segment(), ref.position(), ref.length()));
            byte[] answer = peer.next();
            assertEquals(RawPeer.MISS, RawPeer.type(answer), "a record the segment cannot hold whole");
            peer.send(RawPeer.READ, RawPeer.read(2, "node-a", JOURNAL_A, ref.segment() + 999, ref.position(), ref.length()));
            assertEquals(RawPeer.MISS, RawPeer.type(peer.next()), "the link stays");
        }
    }

    /** The segment file of {@code node}'s journal {@code segment} (the fixture's layout: {@code <n>.seg} below its directory). */
    Path segmentOf(String node, long segment) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(tmp.resolve(node))) {
            return files.filter(f -> f.getFileName().toString().equals(segment + ".seg")).findFirst().orElseThrow();
        }
    }

    /** A connection that never says HELLO is closed by node-a once FLOTTILA_PEER_TIMEOUT passed on its timer, and not before. */
    @Test
    void aConnectionWithoutAHelloIsClosedByTheListenerAfterTheTimeout() throws Exception {
        try (Socket silent = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            skipFrame(silent);   // node-a's HELLO: the link is open on node-a
            clock.advance(CONFIG.timeout().minusMillis(1));
            silent.setSoTimeout(200);
            assertEquals(-2, readOrTimeout(silent), "still open a millisecond before the timeout");
            clock.advance(Duration.ofMillis(1));
            assertTrue(RawPeer.closedByPeer(silent), "closed by node-a at the timeout");
        }
    }

    /** Reads one whole frame of {@code s} and drops it. */
    static void skipFrame(Socket s) throws Exception {
        java.io.DataInputStream in = new java.io.DataInputStream(s.getInputStream());
        in.readFully(new byte[in.readInt()]);
    }

    /** One byte of {@code s} (-1: closed), or -2 when none came within its timeout: the open link's witness. */
    static int readOrTimeout(Socket s) throws Exception {
        try {
            return s.getInputStream().read() < 0 ? -1 : 0;
        } catch (java.net.SocketTimeoutException open) {
            return -2;
        }
    }

    // ---------------------------------------------------------------------------------------------------- r2 R1: before the proof

    /** Above any HELLO (a u16 node id: 65 535 + 93 bytes at most); a BYTES frame's size on a proven link (the large reads here). */
    static final long ABOVE_A_HELLO = 1L << 20;
    /** Above a PROOF (57 bytes after the u32); below a HELLO naming a long node id. */
    static final long ABOVE_A_PROOF = 4096;

    static byte[] u32(long n) {
        return new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
    }

    static java.util.function.Supplier<byte[]> zeros() {
        return () -> new byte[32];
    }

    /**
     * r2 R1: until a link is proven, a frame whose length announces more than a HELLO — then, after the HELLO, more than a
     * PROOF — is refused at its length, both sides: only the u32 is sent, so a side that waited for the bytes it announces would
     * stay open (the timers stand still: a silent connection is closed only by its timeout,
     * {@link #aConnectionWithoutAHelloIsClosedByTheListenerAfterTheTimeout}). The dialer's read fails retryable at once.
     */
    @ParameterizedTest(name = "toward the {0}, in place of its {1}")
    @CsvSource({"LISTENER, HELLO", "LISTENER, PROOF", "DIALER, HELLO", "DIALER, PROOF"})
    void aFrameLongerThanTheHandshakeAllowsIsRefusedAtItsLength(FrameRelay.Side receiver, String stage) throws Exception {
        byte[] header = u32(stage.equals("HELLO") ? ABOVE_A_HELLO : ABOVE_A_PROOF);
        if (receiver == FrameRelay.Side.LISTENER) {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                OutputStream out = s.getOutputStream();
                if (stage.equals("PROOF")) out.write(PeerHandshake.dialer(b(), "node-a", zeros()).hello().getBytes());
                out.write(header);
                out.flush();
                assertTrue(RawPeer.closedByPeer(s), "node-a closed the link at the length announced in place of its " + stage);
            }
        } else {
            ServerSocket listening = fakeListener();
            OnVirtualThread.Call<byte[]> call = read(reader("127.0.0.1:" + listening.getLocalPort()), KEY, ref);
            try (Socket s = listening.accept()) {
                skipFrame(s);   // node-b's HELLO
                OutputStream out = s.getOutputStream();
                if (stage.equals("PROOF")) out.write(PeerHandshake.listener(PeerLinkTest.identity("node-a", PeerLinkTest.ENV), zeros()).hello().getBytes());
                out.write(header);
                out.flush();
                failedRetryable(call, "a read whose link announced too long a frame in place of its " + stage);
                assertTrue(RawPeer.closedByPeer(s), "node-b closed the link at the length announced in place of its " + stage);
            }
        }
    }

    /** A READ of m-1's record sent on {@code p} as READ {@code id} is answered with BYTES. */
    void served(RawPeer p, long id, String what) throws Exception {
        p.send(RawPeer.READ, RawPeer.read(id, "node-a", JOURNAL_A, ref.segment(), ref.position(), ref.length()));
        assertEquals(RawPeer.BYTES, RawPeer.type(p.next()), what);
    }

    /**
     * r2 R1: node-a holds at most 64 links in their handshake ({@code PeerConfig.HANDSHAKES_MAX}). 64 proven links are open
     * and served (proven ones do not count); then 64 connections that say nothing, each given node-a's HELLO (open on node-a, in
     * its handshake; the timers stand still, so none times out); a 65th is closed at once. The 64th silent one was held, not
     * closed: its handshake then completes and it is served, and with it proven a new connection is served too.
     */
    @Test
    void theListenerHoldsAtMost64LinksInTheirHandshakeAndClosesAFurtherOneAtOnce() throws Exception {
        assertEquals(64, PeerConfig.HANDSHAKES_MAX, "the packet's bound");
        List<RawPeer> open = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                RawPeer p = RawPeer.dial(server.port(), b(), "node-a");
                open.add(p);
                served(p, 1, "fixture: proven link " + (i + 1) + " is served");
            }
            RawPeer last = null;
            for (int i = 0; i < 64; i++) {
                last = RawPeer.connect(server.port());   // it waits for node-a's HELLO: node-a opened the link
                open.add(last);
            }
            try (Socket further = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
                assertTrue(RawPeer.closedByPeer(further), "a 65th connection in its handshake is closed at once");
            }
            last.handshakeAsDialer(b(), "node-a");
            served(last, 1, "the 64th held connection was open: its handshake completes and it is served");
            try (RawPeer next = RawPeer.dial(server.port(), b(), "node-a")) {
                served(next, 1, "with the 64th proven, a new connection is held and served");
            }
        } finally {
            for (RawPeer p : open) p.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------- the reader's refusals

    /**
     * An authenticated answer the reader did not ask for closes the link at the reader and fails the read retryable, never
     * with bytes: a READ to a reader, BYTES for no READ in flight, a MISS of nine bytes, BYTES beyond the length asked, BYTES
     * too short for their read id, BYTES carrying the read id and no record byte (r2 R5: a protocol refusal, never a read()
     * answering 0). The fake node-a runs the real handshake.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"a READ to a reader", "BYTES for no READ in flight", "a MISS of 9 bytes", "BYTES beyond the length asked",
            "BYTES without their id", "BYTES with no record byte"})
    void anAnswerTheReaderDidNotAskForClosesTheLinkAndFailsTheRead(String what) throws Exception {
        ServerSocket listening = fakeListener();
        PeerReads r = reader("127.0.0.1:" + listening.getLocalPort());
        OnVirtualThread.Call<byte[]> call = read(r, KEY, ref);
        try (RawPeer fake = RawPeer.accept(listening, PeerLinkTest.identity("node-a", PeerLinkTest.ENV))) {
            byte[] readFrame = fake.next();
            assertEquals(RawPeer.READ, RawPeer.type(readFrame), "fixture: node-b asks");
            long id = RawPeer.payload(readFrame).getLong(0);
            switch (what) {
                case "a READ to a reader" -> fake.send(RawPeer.READ, RawPeer.read(id, "node-a", JOURNAL_A, 1, 2, 3));
                case "BYTES for no READ in flight" -> fake.send(RawPeer.BYTES, ByteBuffer.allocate(16).putLong(id + 1).array());
                case "a MISS of 9 bytes" -> fake.send(RawPeer.MISS, ByteBuffer.allocate(9).putLong(id).array());
                case "BYTES beyond the length asked" -> fake.send(RawPeer.BYTES, ByteBuffer.allocate(8 + (int) ref.length() + 1).putLong(id).array());
                case "BYTES with no record byte" -> fake.send(RawPeer.BYTES, ByteBuffer.allocate(8).putLong(id).array());   // R5: never a read() of 0
                default -> fake.send(RawPeer.BYTES, new byte[4]);
            }
            failedRetryable(call, what);
            assertTrue(fake.closedByPeer(), what + ": node-b closed the link");
        }
    }

    /** A READ of a segment node-a does not hold answers MISS: retryable, the link stays, and the next read on it answers. */
    @Test
    void aReadOfASegmentTheOwnerDoesNotHoldAnswersMissAndKeepsTheLink() throws Exception {
        FrameRelay wire = relay();
        PeerReads r = reader(wire.address());
        RemoteBodyException miss = failedRetryable(read(r, KEY, new JournalRef(ref.segment() + 999, ref.position(), ref.length())), "a segment not held");
        assertTrue(miss.getMessage().contains("MISS"), "the owner answered MISS: " + miss.getMessage());
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "the next read on the link"));
        assertEquals(1, wire.connections(), "on the same link: " + wire.describe());
        assertFalse(wire.closed(0), "MISS keeps the link");
    }

    /** An owner whose address listens nowhere fails at once, retryable, naming the dial; nothing waits for a timeout. */
    @Test
    void anOwnerListeningNowhereFailsTheReadRetryable() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        RemoteBodyException refused = failedRetryable(read(reader("127.0.0.1:" + closed), KEY, ref), "a refused dial");
        assertTrue(refused.getMessage().contains("no peer link"), "names the dial: " + refused.getMessage());
    }

    /**
     * A read whose dial failed gives its place under FLOTTILA_PEER_READS back: with a bound of 1, the next read of the same owner,
     * now at a listening address, is answered.
     */
    @Test
    void aReadWhoseDialFailedGivesItsPlaceBack() throws Exception {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        Map<String, String> live = new ConcurrentHashMap<>(Map.of("node-a", "127.0.0.1:" + closed));
        PeerReads r = new PeerReads(vertx, new PeerConfig(CONFIG.bind(), 0, CONFIG.host(), CONFIG.timeout(), 1), b(), this.b, timer,
                owner -> Optional.ofNullable(live.get(owner)));
        readers.add(r);
        failedRetryable(read(r, KEY, ref), "a refused dial");
        live.put("node-a", "127.0.0.1:" + server.port());
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "the next read, its owner listening"));
    }

    /** {@code inner}, except that its first {@code refusals} schedules throw: a throw inside a link's open (its watchdog). */
    static Timer refusingFirst(Timer inner, int refusals) {
        AtomicInteger left = new AtomicInteger(refusals);
        return (delay, task) -> {
            if (left.getAndDecrement() > 0) throw new IllegalStateException("fixture: this timer refuses the schedule");
            return inner.schedule(delay, task);
        };
    }

    PeerReads reader(PeerConfig config, Timer on, java.util.function.Function<String, Optional<String>> addressOf) {
        PeerReads r = new PeerReads(vertx, config, b(), this.b, on, addressOf);
        readers.add(r);
        return r;
    }

    static PeerConfig oneRead() {
        return new PeerConfig(CONFIG.bind(), 0, CONFIG.host(), CONFIG.timeout(), 1);
    }

    /**
     * r2 R2: a dial whose connect throws at once — a peer address naming port 70000, no TCP port — fails the read retryable,
     * never leaves it waiting, and gives its place under FLOTTILA_PEER_READS back: with a bound of 1, the next read, its owner
     * now behind a relay, dials again and is answered.
     */
    @Test
    void aDialWhoseConnectThrowsFailsTheReadAndTheNextReadDialsAgain() throws Exception {
        Map<String, String> live = new ConcurrentHashMap<>(Map.of("node-a", "127.0.0.1:70000"));
        PeerReads r = reader(oneRead(), timer, owner -> Optional.ofNullable(live.get(owner)));
        failedRetryable(read(r, KEY, ref), "a dial to port 70000");
        FrameRelay wire = relay();
        live.put("node-a", wire.address());
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "the next read, its owner at a listening address"));
        assertEquals(1, wire.connections(), "dialled again: " + wire.describe());
    }

    /**
     * r2 R2: a dial whose link cannot open — node-b's timer refuses the link's watchdog, a throw inside PeerLink.open — fails
     * the read retryable, closes the connected socket (the relay sees node-b's end first) and gives the read's place back: with
     * a bound of 1, the next read dials again, on a second connection, and is answered.
     */
    @Test
    void aDialWhoseLinkCannotOpenFailsTheReadClosesItsSocketAndTheNextReadDialsAgain() throws Exception {
        FrameRelay wire = relay();
        PeerReads r = reader(oneRead(), refusingFirst(timer, 1), owner -> Optional.of(wire.address()));
        failedRetryable(read(r, KEY, ref), "a read whose link could not open");
        assertEquals(FrameRelay.Side.DIALER, wire.awaitClosedBy(0, "node-b closes the socket its link could not open on"), wire.describe());
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "the next read"));
        assertEquals(2, wire.connections(), "on a connection of its own: " + wire.describe());
    }

    /**
     * r2 R2: a connection node-a cannot open a link on — its timer refuses the watchdog, a throw inside PeerLink.open — is
     * closed, never left open without a handler; the next connection is served.
     */
    @Test
    void aConnectionTheListenerCannotOpenALinkOnIsClosed() throws Exception {
        PeerServer refusing = PeerServer.listen(vertx, CONFIG, PeerLinkTest.identity("node-a", PeerLinkTest.ENV), a, refusingFirst(timer, 1))
                .await(BOUND_SECONDS, TimeUnit.SECONDS);
        closing.add(() -> refusing.close().await(BOUND_SECONDS, TimeUnit.SECONDS));
        try (Socket first = new Socket(InetAddress.getLoopbackAddress(), refusing.port())) {
            assertTrue(RawPeer.closedByPeer(first), "node-a closed the connection its link could not open on");
        }
        try (RawPeer next = RawPeer.dial(refusing.port(), b(), "node-a")) {
            served(next, 1, "the next connection is served");
        }
    }

    /**
     * A consumer that closes its read while the link is held for it (more than the pause mark buffered): the link resumes, the
     * rest of the record's bytes are dropped as they come, and the read's place under FLOTTILA_PEER_READS comes back once they
     * passed — with a bound of 1, the next read is answered on the same link.
     */
    @Test
    void aReadItsConsumerClosesWhileHeldGivesItsPlaceBack() throws Exception {
        FrameRelay wire = relay();
        PeerReads r = new PeerReads(vertx, new PeerConfig(CONFIG.bind(), 0, CONFIG.host(), CONFIG.timeout(), 1), b(), this.b, timer,
                owner -> Optional.of(wire.address()));
        readers.add(r);
        OnVirtualThread.call(() -> {
            try (InputStream in = r.open(LARGE, "node-a", JOURNAL_A, large)) {
                assertEquals(1024, in.readNBytes(1024).length, "fixture: the read started");
                PeerReadHeapTest.until(wire.toDialer()::parkedWriting, () -> "node-b's reader paused its link: " + wire.describe());
            }
            return null;
        });
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "the next read, the closed one's bytes passed"));
        assertEquals(1, wire.connections(), "on the same link: " + wire.describe());
    }

    /** An owner that moved (a new incarnation elsewhere, a new address in the renew) is dialled at its new address. */
    @Test
    void anOwnerAtANewAddressIsDialledThere() throws Exception {
        FrameRelay before = relay();
        FrameRelay after = relay();
        Map<String, String> live = new ConcurrentHashMap<>(Map.of("node-a", before.address()));
        PeerReads r = reader(live, timer);
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "at the first address"));
        live.put("node-a", after.address());
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "at the new address"));
        assertEquals(1, before.connections(), "the first address was dialled once: " + before.describe());
        assertEquals(1, after.connections(), "the new address dialled: " + after.describe());
        assertEquals(FrameRelay.Side.DIALER, before.awaitClosedBy(0, "the link to the old address"), "node-b closed the link to the old address");
    }

    /** Closing the reads fails a read in flight retryable (its answer held at the relay), never leaves it waiting. */
    @Test
    void closingTheReadsFailsAReadInFlight() throws Exception {
        FrameRelay wire = relay();
        PeerReads r = reader(wire.address());
        assertArrayEquals(body, bytesOf(read(r, KEY, ref), "witness: the link reads"));
        int mark = wire.toListener().frames();
        wire.toDialer().hold();
        OnVirtualThread.Call<byte[]> pending = read(r, KEY, ref);
        PeerReadIT.until(() -> wire.toListener().frames() > mark, () -> "the READ went out: " + wire.describe());
        r.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        failedRetryable(pending, "a read in flight when the reads close");
    }

    // ---------------------------------------------------------------------------------------------------- the reader's watchdog

    /**
     * A listener that accepts the connection and never says HELLO: the read fails retryable once FLOTTILA_PEER_TIMEOUT passed on
     * node-b's timer ("not established"), and not before.
     */
    @Test
    void aLinkNotEstablishedWithinTheTimeoutFailsTheRead() throws Exception {
        FakeClock bClock = FakeClock.at(JournalFixture.START);
        FakeTimer bTimer = new FakeTimer(bClock);
        ServerSocket listening = fakeListener();
        PeerReads r = reader(new ConcurrentHashMap<>(Map.of("node-a", "127.0.0.1:" + listening.getLocalPort())), bTimer);
        OnVirtualThread.Call<byte[]> call = read(r, KEY, ref);
        try (Socket mute = listening.accept()) {
            skipFrame(mute);   // node-b's HELLO: its link is open
            bClock.advance(CONFIG.timeout().minusMillis(1));
            assertFalse(call.isDone(), "still waiting a millisecond before the timeout");
            bClock.advance(Duration.ofMillis(1));
            RemoteBodyException late = failedRetryable(call, "a link never established");
            assertTrue(late.getMessage().contains("not established"), late.getMessage());
        }
    }

    /**
     * A listener that completes the handshake and never answers the READ: one timeout with progress (the READ itself) keeps
     * the link; the next one without any fails the read retryable ("gave no answer").
     */
    @Test
    void aReadWithoutAnAnswerForATimeoutFailsRetryable() throws Exception {
        FakeClock bClock = FakeClock.at(JournalFixture.START);
        FakeTimer bTimer = new FakeTimer(bClock);
        ServerSocket listening = fakeListener();
        PeerReads r = reader(new ConcurrentHashMap<>(Map.of("node-a", "127.0.0.1:" + listening.getLocalPort())), bTimer);
        OnVirtualThread.Call<byte[]> call = read(r, KEY, ref);
        try (RawPeer fake = RawPeer.accept(listening, PeerLinkTest.identity("node-a", PeerLinkTest.ENV))) {
            assertEquals(RawPeer.READ, RawPeer.type(fake.next()), "fixture: node-b asks");
            bClock.advance(CONFIG.timeout());
            assertFalse(call.isDone(), "a timeout in which the READ went out keeps the link");
            bClock.advance(CONFIG.timeout());
            RemoteBodyException silent = failedRetryable(call, "a READ never answered");
            assertTrue(silent.getMessage().contains("gave no answer"), silent.getMessage());
            assertTrue(fake.closedByPeer(), "node-b closed the link");
        }
    }

    /**
     * A READ that went out within a timeout is progress: the watchdog's look at its end keeps the link, and the owner's answer
     * after it is the read's — the record's bytes as node-a's journal holds them, checked by node-b's.
     */
    @Test
    void aReadAnsweredAfterATimeoutInWhichItWentOutCompletes() throws Exception {
        FakeClock bClock = FakeClock.at(JournalFixture.START);
        FakeTimer bTimer = new FakeTimer(bClock);
        ServerSocket listening = fakeListener();
        PeerReads r = reader(new ConcurrentHashMap<>(Map.of("node-a", "127.0.0.1:" + listening.getLocalPort())), bTimer);
        OnVirtualThread.Call<byte[]> call = read(r, KEY, ref);
        try (RawPeer fake = RawPeer.accept(listening, PeerLinkTest.identity("node-a", PeerLinkTest.ENV))) {
            byte[] readFrame = fake.next();
            assertEquals(RawPeer.READ, RawPeer.type(readFrame), "fixture: node-b asks");
            long id = RawPeer.payload(readFrame).getLong(0);
            bClock.advance(CONFIG.timeout());   // the watchdog looks once: the READ went out since the link opened
            byte[] stored;
            try (InputStream raw = a.raw(ref)) {
                stored = raw.readNBytes((int) ref.length());
            }
            fake.send(RawPeer.BYTES, ByteBuffer.allocate(8 + stored.length).putLong(id).put(stored).array());
            assertArrayEquals(body, bytesOf(call, "answered after the timeout"));
            bClock.advance(CONFIG.timeout());
            bClock.advance(CONFIG.timeout());   // an idle link: no read waits on it, so no timeout closes it
            OnVirtualThread.Call<byte[]> again = read(r, KEY, ref);
            byte[] second = fake.next();
            assertEquals(RawPeer.READ, RawPeer.type(second), "the next READ comes on the same link");
            fake.send(RawPeer.BYTES, ByteBuffer.allocate(8 + stored.length).putLong(RawPeer.payload(second).getLong(0)).put(stored).array());
            assertArrayEquals(body, bytesOf(again, "the second read on the idle link"));
        }
    }

    /**
     * A link paused by a reader still draining is not a stalled one (r2 R3; the r1 case let the consumer take nothing for three
     * timeouts, which R3 now closes): its consumer stopped with more than the pause mark buffered, and after each timeout on
     * node-b's timer it takes one more 1 MiB chunk — progress while the link stays paused (the second look still finds more
     * than half the mark buffered) — so the watchdog keeps the link; the read then goes on to the record's last byte.
     */
    @Test
    void aLinkPausedByAReaderStillDrainingOutlivesTheTimeouts() throws Exception {
        FakeClock bClock = FakeClock.at(JournalFixture.START);
        FakeTimer bTimer = new FakeTimer(bClock);
        FrameRelay wire = relay();
        PeerReads r = reader(new ConcurrentHashMap<>(Map.of("node-a", wire.address())), bTimer);
        MessageDigest md = GeneratedInput.digest();
        long got = OnVirtualThread.call(() -> {
            try (InputStream in = r.open(LARGE, "node-a", JOURNAL_A, large)) {
                byte[] first = in.readNBytes(1024);
                md.update(first);
                long n = first.length;
                PeerReadHeapTest.until(wire.toDialer()::parkedWriting, () -> "node-b's reader paused its link: " + wire.describe());
                for (int i = 0; i < 3; i++) {
                    bClock.advance(CONFIG.timeout());
                    byte[] chunk = in.readNBytes(1 << 20);   // crosses one chunk boundary: one more chunk taken from the link
                    md.update(chunk);
                    n += chunk.length;
                }
                byte[] rest = in.readAllBytes();
                md.update(rest);
                return n + rest.length;
            }
        });
        assertEquals(GeneratedInput.expected(61, LARGE_SIZE), new GeneratedInput.Digest(got, GeneratedInput.hashOf(md.digest())),
                "the record read to its last byte after the timeouts passed");
        assertFalse(wire.closed(0), "the paused link stayed: " + wire.describe());
    }

    /**
     * r2 R3: a link paused by a consumer that stopped for good (it neither reads nor closes) is closed by node-b once a whole
     * FLOTTILA_PEER_TIMEOUT passed on node-b's timer with its consumer taking nothing — the first look sees the progress before
     * the pause, the second none — and every read on it fails retryable: a read waiting behind the paused one (head-of-line,
     * FIX-177), and the abandoned one at its next read once its buffered bytes are taken.
     */
    @Test
    void aLinkPausedByAnAbandonedConsumerIsClosedAfterATimeoutAndItsReadsFail() throws Exception {
        FakeClock bClock = FakeClock.at(JournalFixture.START);
        FakeTimer bTimer = new FakeTimer(bClock);
        FrameRelay wire = relay();
        PeerReads r = reader(new ConcurrentHashMap<>(Map.of("node-a", wire.address())), bTimer);
        java.util.concurrent.CountDownLatch paused = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch goOn = new java.util.concurrent.CountDownLatch(1);
        OnVirtualThread.Call<byte[]> abandoned = OnVirtualThread.start(() -> {
            try (InputStream in = r.open(LARGE, "node-a", JOURNAL_A, large)) {
                in.readNBytes(1024);
                PeerReadHeapTest.until(wire.toDialer()::parkedWriting, () -> "fixture: node-b's reader paused its link: " + wire.describe());
                paused.countDown();
                goOn.await();
                return in.readAllBytes();
            }
        });
        assertTrue(paused.await(BOUND_SECONDS, TimeUnit.SECONDS), "fixture: the link paused");
        int mark = wire.toListener().frames();
        OnVirtualThread.Call<byte[]> behind = read(r, KEY, ref);
        PeerReadIT.until(() -> wire.toListener().frames() > mark, () -> "fixture: the second READ went out: " + wire.describe());

        bClock.advance(CONFIG.timeout());   // the first look: progress since the link opened (bytes, both READs, the consumer's first chunk)
        bClock.advance(CONFIG.timeout());   // the second: a whole timeout paused, the consumer taking nothing

        failedRetryable(behind, "a read waiting behind the abandoned one");
        assertEquals(FrameRelay.Side.DIALER, wire.awaitClosedBy(0, "node-b closes the paused link"), "closed by node-b: " + wire.describe());
        goOn.countDown();
        failedRetryable(abandoned, "the abandoned read, at its next read past its buffered bytes");
    }
}
