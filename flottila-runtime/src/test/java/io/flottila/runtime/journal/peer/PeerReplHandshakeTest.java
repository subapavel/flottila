package io.flottila.runtime.journal.peer;

import io.flottila.runtime.journal.Journal;
import io.flottila.runtime.journal.JournalConfig;
import io.flottila.runtime.journal.JournalFiles;
import io.flottila.runtime.journal.peer.ScriptedPeer.Hello;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5b1 T2 (packet 5b1 types as amended by C1, C8): the channel of a link is the dialer's HELLO byte 2; the listener's HELLO
 * goes at once with channel 1 on every link (its bytes the same on a read and a repl dial), takes 1 and 2 and refuses 3 naming
 * "bulk"; a repl dialer, like a read one, refuses a listener whose HELLO names a channel other than read. Both proofs cover
 * both whole HELLOs, so a relay rewriting the dialer's channel byte never gets a link established. A frame of the other
 * channel closes the link: READ on a repl link, OPEN on a read link. A repl HELLO whose node is not a DNS label is closed,
 * and nothing lands outside {@code replicas/}. The fake timer is never advanced: no FLOTTILA_PEER_TIMEOUT closes a link here.
 *
 * <p>The API assumed (CORE-5b1, test-author): {@code PeerHandshake.dialer(PeerIdentity self, String peer, Supplier<byte[]>
 * nonces, byte channel)} (1 read, 2 repl, 3 bulk); {@code PeerLink.channel()} once established, on both sides (an integral
 * type); {@code PeerServer.listen(Vertx, PeerConfig, Supplier<PeerIdentity>, Journal, Timer, ToLongFunction<String> epochOf)};
 * {@code RawPeer.dial(port, self, peer, channel)} and {@code RawPeer#framesUntilClosed} (fixtures); {@link ReplWire} for the
 * repl payloads.
 */
@Timeout(120)
class PeerReplHandshakeTest {

    static final long BOUND_SECONDS = 20;
    static final SecretKey ENV = new SecretKeySpec(bytes(0x5B, 32), "AES");
    static final UUID JOURNAL_A = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");
    static final UUID JOURNAL_B = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");
    static final PeerIdentity A = new PeerIdentity("node-a", 1, JOURNAL_A, List.of(ENV));
    static final PeerIdentity B = new PeerIdentity("node-b", 1, JOURNAL_B, List.of(ENV));
    static final PeerConfig CONFIG = new PeerConfig("127.0.0.1", 0, "127.0.0.1", Duration.ofSeconds(2), 16);
    static final Instant START = Instant.parse("2026-01-01T00:00:00.850Z");
    /** The HELLO payload's channel byte in its frame: len u32 · type u8 · seq u64, then version, role, channel. */
    static final int CHANNEL_AT = 4 + 1 + 8 + 2;

    @TempDir Path tmp;
    Vertx vertx;
    final FakeClock clock = FakeClock.at(START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal holder;
    Path holderDir;
    PeerServer server;
    final List<AutoCloseable> closing = new ArrayList<>();

    static byte[] bytes(int seed, int n) {
        byte[] b = new byte[n];
        new java.util.SplittableRandom(seed).nextBytes(b);
        return b;
    }

    static java.util.function.Supplier<byte[]> nonces(int seed) {
        return () -> bytes(seed, PeerHandshake.NONCE);
    }

    @BeforeEach
    void open() throws Exception {
        vertx = Vertx.vertx();
        holderDir = Files.createDirectories(tmp.resolve("node-a"));
        JournalConfig config = new JournalConfig(holderDir, 64L << 20, 65_536, Duration.ofSeconds(1), 16L << 20, Duration.ofSeconds(5),
                256L << 10, "node-a");
        holder = onVirtual(() -> Journal.open(config, clock, timer, id -> null, JournalFiles.real()));
        server = PeerServer.listen(vertx, CONFIG, () -> A, holder, timer, owner -> 0L).await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closing) c.close();
        server.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        holder.close();
        vertx.close().await();
    }

    static <T> T onVirtual(java.util.concurrent.Callable<T> call) throws Exception {
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                f.complete(call.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        }
    }

    /** An OPEN of node-b's segment {@code n} (its header, 1 KiB of room after it): a repl link answers it RECEIPT OPENED. */
    static byte[] openOf(String owner, UUID journal, long n) {
        byte[] header = ReplWire.header(owner, journal, n, START.toEpochMilli());
        return ReplWire.open(owner, journal, n, header.length + 1024L, header);
    }

    /** A link of PeerLinks on both sides — the test's NetServer as listener — dialled on {@code channel}: [dialer, listener]. */
    PeerLink[] linkPair(byte channel) throws Exception {
        Promise<PeerLink> listening = Promise.promise();
        NetServer net = vertx.createNetServer().connectHandler(socket ->
                PeerLink.open(socket, PeerHandshake.listener(A, nonces(1)), timer, CONFIG.timeout(), new Quiet())
                        .established().onComplete(listening::tryComplete, listening::tryFail));
        int port = net.listen(0, "127.0.0.1").await(BOUND_SECONDS, TimeUnit.SECONDS).actualPort();
        closing.add(() -> net.close().await(BOUND_SECONDS, TimeUnit.SECONDS));
        NetClient client = vertx.createNetClient();
        closing.add(() -> client.close().await(BOUND_SECONDS, TimeUnit.SECONDS));
        Promise<PeerLink> dialling = Promise.promise();
        vertx.runOnContext(v -> client.connect(port, "127.0.0.1").onComplete(socket ->
                PeerLink.open(socket, PeerHandshake.dialer(B, "node-a", nonces(2), channel), timer, CONFIG.timeout(), new Quiet())
                        .established().onComplete(dialling::tryComplete, dialling::tryFail), dialling::tryFail));
        PeerLink dialer = dialling.future().await(BOUND_SECONDS, TimeUnit.SECONDS);
        PeerLink listener = listening.future().await(BOUND_SECONDS, TimeUnit.SECONDS);
        closing.add(() -> dialer.close(new IOException("the case ends")));
        return new PeerLink[] {dialer, listener};
    }

    /** A receiver that takes nothing and waits for nothing. */
    static final class Quiet implements PeerLink.Receiver {
        @Override public void received(PeerLink link, int type, byte[] frame) { }
        @Override public void closed(PeerLink link, IOException why) { }
        @Override public boolean waiting() { return false; }
    }

    @Test
    void aReplDialLinksWithChannelTwoOnBothSidesAndAReadDialWithChannelOne() throws Exception {
        PeerLink[] repl = linkPair(ReplWire.CHANNEL_REPL);
        assertEquals(2, repl[0].channel(), "the dialer's link: the channel it dialled");
        assertEquals(2, repl[1].channel(), "the listener's link: the dialer's HELLO byte 2");
        PeerLink[] read = linkPair(ReplWire.CHANNEL_READ);
        assertEquals(1, read[0].channel(), "a read dial: the dialer's link");
        assertEquals(1, read[1].channel(), "a read dial: the listener's link");
    }

    @Test
    void theDialersHelloNamesItsChannelAndTheListenersHelloNamesReadOnEveryLink() throws Exception {
        assertEquals(2, PeerHandshake.dialer(B, "node-a", nonces(2), ReplWire.CHANNEL_REPL).hello().getByte(CHANNEL_AT), "a repl dialer's HELLO, byte 2");
        assertEquals(1, PeerHandshake.dialer(B, "node-a", nonces(2), ReplWire.CHANNEL_READ).hello().getByte(CHANNEL_AT), "a read dialer's HELLO, byte 2");

        byte[] onRepl;
        byte[] onRead;
        try (ScriptedPeer p = ScriptedPeer.dial(null, server.port(), Hello.dialer(B, bytes(3, 32)).channel(ScriptedPeer.CHANNEL_REPL), ENV)) {
            onRepl = p.theirHello();
        }
        try (ScriptedPeer p = ScriptedPeer.dial(null, server.port(), Hello.dialer(B, bytes(4, 32)).channel(ScriptedPeer.CHANNEL_READ), ENV)) {
            onRead = p.theirHello();
        }
        assertEquals(1, onRepl[2], "the listener's HELLO on a repl dial names channel read (C1: unchanged)");
        int nonceAt = 3 + 2 + "node-a".length() + 8 + 16;
        assertArrayEquals(withoutNonce(onRead, nonceAt), withoutNonce(onRepl, nonceAt),
                "the listener's HELLO bytes are the same on a read and a repl dial (but its fresh nonce)");
    }

    static byte[] withoutNonce(byte[] hello, int at) {
        byte[] c = Arrays.copyOf(hello, hello.length);
        Arrays.fill(c, at, at + 32, (byte) 0);
        return c;
    }

    @Test
    void theListenerTakesAReplHelloAndRefusesABulkOneNamingBulk() throws Exception {
        assertNotNull(PeerHandshake.listener(A, nonces(1)).proof(Hello.dialer(B, bytes(2, 32)).channel(ScriptedPeer.CHANNEL_REPL).frame()),
                "a repl dialer's HELLO is proven");
        PeerRefusedException e = assertThrows(PeerRefusedException.class,
                () -> PeerHandshake.listener(A, nonces(1)).proof(Hello.dialer(B, bytes(2, 32)).channel(ScriptedPeer.CHANNEL_BULK).frame()));
        assertTrue(String.valueOf(e.getMessage()).contains("bulk"), "the refusal names bulk: " + e.getMessage());
        assertEquals(List.of(ScriptedPeer.HELLO), ScriptedPeer.refusedAfter(server.port(),
                Hello.dialer(B, bytes(2, 32)).channel(ScriptedPeer.CHANNEL_BULK)), "the server closes a bulk dial after its HELLO, no proof sent");
    }

    @Test
    void aReplDialerRefusesAListenerWhoseHelloNamesAChannelOtherThanRead() throws Exception {
        PeerRefusedException e = assertThrows(PeerRefusedException.class, () -> PeerHandshake.dialer(B, "node-a", nonces(2), ReplWire.CHANNEL_REPL)
                .proof(Hello.listener(A, bytes(1, 32)).channel(ScriptedPeer.CHANNEL_REPL).frame()));
        assertTrue(String.valueOf(e.getMessage()).contains("channel repl"), "names the channel: " + e.getMessage());
        assertNotNull(PeerHandshake.dialer(B, "node-a", nonces(2), ReplWire.CHANNEL_REPL).proof(Hello.listener(A, bytes(1, 32)).frame()),
                "witness: the same listener HELLO on channel read is taken by a repl dialer");
    }

    @ParameterizedTest(name = "dialled {0}, rewritten to {1}")
    @CsvSource({"2, 1", "1, 2"})
    void aRelayRewritingTheDialersChannelByteNeverEstablishes(byte dialled, byte rewritten) throws Exception {
        FrameRelay relay = new FrameRelay("127.0.0.1", server.port());
        closing.add(relay);
        relay.toListener().setByteOfNext(CHANNEL_AT, rewritten);
        RawPeer dialer = RawPeer.connect(relay.port());
        closing.add(dialer);
        assertThrows(PeerRefusedException.class, () -> dialer.handshakeAsDialer(B, "node-a", dialled),
                "the listener's proof covers the HELLO it saw, not the one the dialer sent");
        assertEquals(FrameRelay.Side.LISTENER, relay.awaitClosedBy(0, "the rewritten dial"),
                "the listener refuses the dialer's proof over the HELLO the dialer sent and closes first");

        RawPeer witness = RawPeer.connect(relay.port());   // the same relay, nothing rewritten
        closing.add(witness);
        witness.handshakeAsDialer(B, "node-a", dialled);
        if (dialled == ReplWire.CHANNEL_REPL) {
            witness.send(ReplWire.OPEN, openOf("node-b", JOURNAL_B, 1));
            assertTrue(ReplWire.answer(witness.next()).is(ReplWire.RECEIPT, 1, ReplWire.OPENED), "witness: the same repl dial unchanged is served");
        } else {
            witness.send(RawPeer.READ, RawPeer.read(9, "node-a", JOURNAL_A, 1, 2, 3));
            assertEquals(RawPeer.MISS, RawPeer.type(witness.next()), "witness: the same read dial unchanged is served");
        }
    }

    @Test
    void aReadFrameOnAReplLinkClosesIt() throws Exception {
        try (RawPeer owner = RawPeer.dial(server.port(), B, "node-a", ReplWire.CHANNEL_REPL)) {
            owner.send(ReplWire.OPEN, openOf("node-b", JOURNAL_B, 1));
            assertTrue(ReplWire.answer(owner.next()).is(ReplWire.RECEIPT, 1, ReplWire.OPENED), "witness: the repl link serves an OPEN");
            owner.send(RawPeer.READ, RawPeer.read(9, "node-a", JOURNAL_A, 1, 2, 3));
            assertEquals(List.of(), owner.framesUntilClosed(), "a READ on a repl link closes it, unanswered");
        }
    }

    @Test
    void anOpenFrameOnAReadLinkClosesIt() throws Exception {
        try (RawPeer reader = RawPeer.dial(server.port(), B, "node-a", ReplWire.CHANNEL_READ)) {
            reader.send(RawPeer.READ, RawPeer.read(9, "node-a", JOURNAL_A, 1, 2, 3));
            assertEquals(RawPeer.MISS, RawPeer.type(reader.next()), "witness: the read link serves a READ");
            reader.send(ReplWire.OPEN, openOf("node-b", JOURNAL_B, 1));
            assertEquals(List.of(), reader.framesUntilClosed(), "an OPEN on a read link closes it, unanswered");
        }
        assertFalse(Files.exists(holderDir.resolve("replicas").resolve("node-b").resolve(JOURNAL_B.toString()).resolve("1.part")),
                "no replica was opened");
    }

    @ParameterizedTest(name = "node {0}")
    @ValueSource(strings = {"..", "a/b"})
    void aReplHelloWhoseNodeIsNotADnsLabelIsClosed(String node) throws Exception {
        PeerIdentity who = new PeerIdentity(node, 1, JOURNAL_B, List.of(ENV));
        assertTrue(closesTheLink(Hello.dialer(who, bytes(5, 32)).channel(ScriptedPeer.CHANNEL_REPL), openOf(node, JOURNAL_B, 1)),
                "a repl HELLO naming node " + node + " is closed");
        assertFalse(Files.exists(holderDir.resolve(JOURNAL_B.toString())), "nothing created beside replicas/ (node ..)");
        assertFalse(Files.exists(holderDir.resolve("replicas").resolve("a")), "nothing created under replicas/a (node a/b)");
        assertFalse(closesTheLink(Hello.dialer(B, bytes(6, 32)).channel(ScriptedPeer.CHANNEL_REPL), openOf("node-b", JOURNAL_B, 1)),
                "witness: the same dial naming node-b is served");
    }

    /**
     * Whether a repl dial with {@code hello} is closed — in its handshake, or at the OPEN it sends next — rather than answered:
     * true on the peer's close, false on an answer. A link left open and silent is an AssertionError (a read past the bound),
     * never a close.
     */
    boolean closesTheLink(Hello hello, byte[] open) throws IOException {
        ScriptedPeer p;
        try {
            p = ScriptedPeer.dial(null, server.port(), hello, ENV);
        } catch (EOFException | SocketException closed) {
            return true;
        }
        try (p) {
            p.send(ReplWire.OPEN, open);
            ReplWire.answer(p.next());
            return false;
        } catch (EOFException | SocketException closed) {
            return true;
        } catch (java.net.SocketTimeoutException open2) {
            throw new AssertionError("the link stayed open and silent within " + BOUND_SECONDS + " s", open2);
        }
    }
}
