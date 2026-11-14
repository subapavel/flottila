package io.flottila.runtime.journal;

import io.flottila.api.BlobKey;
import io.flottila.runtime.blob.RemoteBodyException;
import io.flottila.runtime.journal.peer.FrameRelay;
import io.flottila.runtime.journal.peer.PeerConfig;
import io.flottila.runtime.journal.peer.PeerHandshake;
import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.PeerReads;
import io.flottila.runtime.journal.peer.PeerRefusedException;
import io.flottila.runtime.journal.peer.PeerServer;
import io.flottila.runtime.node.OnVirtualThread;
import io.flottila.testkit.FakeClock;
import io.flottila.testkit.FakeTimer;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.crypto.SecretKey;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CORE-5a acceptance A1 and the advisor's W3, W7 (packet agent/packets/CORE-5.md S1, S2, "Architecture advisor … disposition"):
 * the peer link refuses what the environment's key does not vouch for and closes on a frame it cannot trust. Two journals
 * without Postgres — node-a's holds one record, node-b's only reads with the same tenant keys — node-a's {@code PeerServer} on
 * loopback, node-b's {@code PeerReads} reaching it through a {@link FrameRelay} that counts the frames, holds, flips or injects
 * them, and records which side closed. A fake timer that is never advanced drives both ends: no {@code FLOTTILA_PEER_TIMEOUT}
 * closes a link here, so every close observed is the guard's.
 *
 * <p>The API assumed (CORE-5a, test-author; package {@code io.flottila.runtime.journal.peer}):
 * <ul>
 * <li>{@code record PeerConfig(String bind, int port, String host, Duration timeout, int reads)} — the FLOTTILA_PEER_* keys;
 * <li>{@code record PeerIdentity(String node, long epoch, UUID journal, List<SecretKey> masters)} — what HELLO says of a node
 *     (its id, its incarnation, its journal's id = {@code journal_nodes.journal_id}) and the environment's master keys, the
 *     current first, then the previous one (D-106 rolling rotation, W7);
 * <li>{@code static Future<PeerServer> PeerServer.listen(Vertx, PeerConfig, PeerIdentity self, Journal journal, Timer)},
 *     {@code int port()}, {@code Future<Void> close()} — serves READ(owner, journal id, n, pos, len) of its own journal's records
 *     as BYTES (≤ 1 MiB each), MISS for an (owner, journal id, n) it does not hold;
 * <li>{@code PeerReads(Vertx, PeerConfig, PeerIdentity self, Journal local, Timer, Function<String, Optional<String>> addressOf)}
 *     — one {@code read} link per peer, opened on the first read of it; {@code InputStream open(BlobKey key, String owner, UUID
 *     journal, JournalRef ref) throws IOException}, blocking, on a virtual thread: the record's plaintext, checked by CRC32C and
 *     AEAD and against {@code key} by the reader (with {@code local}'s tenant keys and record checks); a read whose link is
 *     refused, closed or lost, or whose owner answers MISS, fails with a retryable {@link RemoteBodyException} — at once, the
 *     caller retries; {@code Future<Void> close()};
 * <li>{@code PeerHandshake} — the HELLO exchange without a socket: {@code static dialer(PeerIdentity self, String peer,
 *     Supplier<byte[]> nonces)} and {@code static listener(PeerIdentity self, Supplier<byte[]> nonces)} (32-byte nonces);
 *     {@code Buffer hello()} — this side's HELLO frame (version, node, epoch, journal id, nonce, the fingerprint of the master key
 *     it proves under), sent by both sides at once on connect; {@code Buffer proof(Buffer peerHello)} — this side's proof frame
 *     over both nonces, both node ids, the epochs and the direction, under the key the dialer's fingerprint names; {@code
 *     accept(Buffer peerProof)} — the established session, non-null; each refusal a {@link PeerRefusedException} (an
 *     IOException). A HELLO naming a key fingerprint this side does not hold is refused at once.
 * </ul>
 * Frames are {@code u32 len · u8 type · u64 seq · payload · tag[16]}, {@code len} counting every byte after itself.
 */
@Timeout(120)
class PeerLinkTest {

    static final SecretKey ENV = JournalFixture.key(7_001);
    static final SecretKey PREVIOUS = JournalFixture.key(7_002);
    static final SecretKey UNKNOWN = JournalFixture.key(7_003);
    static final UUID JOURNAL_A = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");
    static final UUID JOURNAL_B = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");
    static final PeerConfig CONFIG = new PeerConfig("127.0.0.1", 0, "127.0.0.1", Duration.ofSeconds(2), 16);
    static final BlobKey KEY = BlobKey.body("m-1", 0);
    static final long BOUND_SECONDS = 20;

    @TempDir Path tmp;
    Vertx vertx;
    final FakeClock clock = FakeClock.at(JournalFixture.START);
    final FakeTimer timer = new FakeTimer(clock);
    Journal a;
    Journal b;
    byte[] body;
    JournalRef ref;
    PeerServer server;
    final List<FrameRelay> relays = new ArrayList<>();
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
        server = PeerServer.listen(vertx, CONFIG, identity("node-a", ENV, PREVIOUS), a, timer).await(BOUND_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        for (PeerReads r : readers) r.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        for (FrameRelay r : relays) r.close();
        server.close().await(BOUND_SECONDS, TimeUnit.SECONDS);
        a.close();
        b.close();
        vertx.close().await();
    }

    Journal journal(String node) throws Exception {
        JournalFixture.Settings s = JournalFixture.settings(Files.createDirectories(tmp.resolve(node)));
        s.node = node;
        JournalConfig config = s.config();
        return OnVirtualThread.call(() -> Journal.open(config, clock, timer, JournalFixture.keys(JournalFixture.KEY), JournalFiles.real()));
    }

    static PeerIdentity identity(String node, SecretKey... masters) {
        return new PeerIdentity(node, 1, node.equals("node-a") ? JOURNAL_A : JOURNAL_B, List.of(masters));
    }

    FrameRelay relay() throws Exception {
        FrameRelay r = new FrameRelay("127.0.0.1", server.port());
        relays.add(r);
        return r;
    }

    /** node-b's reader of node-a, which it finds behind {@code relay}. */
    PeerReads reader(PeerIdentity self, FrameRelay relay) {
        PeerReads r = new PeerReads(vertx, CONFIG, self, b, timer, owner -> owner.equals("node-a") ? Optional.of(relay.address()) : Optional.empty());
        readers.add(r);
        return r;
    }

    /** A read of m-1's record from node-a under {@code journal}, on a virtual thread of its own. */
    OnVirtualThread.Call<byte[]> read(PeerReads r, UUID journal) {
        return OnVirtualThread.start(() -> {
            try (InputStream in = r.open(KEY, "node-a", journal, ref)) {
                return in.readAllBytes();
            }
        });
    }

    static byte[] bytesOf(OnVirtualThread.Call<byte[]> call, String what) throws Exception {
        try {
            return call.outcome().get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError(what + ": the read failed", failed.getCause());
        }
    }

    /** The read failed — retryable, never bytes — within the bound. */
    static RemoteBodyException failedRetryable(OnVirtualThread.Call<byte[]> call, String what) throws Exception {
        byte[] answered;
        try {
            answered = call.outcome().get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            RemoteBodyException retryable = assertInstanceOf(RemoteBodyException.class, failed.getCause(),
                    what + ": a retryable RemoteBodyException: " + JournalWorld.causes(failed.getCause()));
            assertTrue(String.valueOf(retryable.getMessage()).contains("node-a"), what + ": naming the owner: " + retryable.getMessage());
            return retryable;
        } catch (java.util.concurrent.TimeoutException pending) {
            throw new AssertionError(what + ": the read neither answered nor failed within " + BOUND_SECONDS + " s (its thread: "
                    + Arrays.toString(call.thread().getStackTrace()) + ")");
        }
        fail(what + ": answered " + answered.length + " bytes; a link it cannot trust answers none");
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- A1, W7 on the wire

    /**
     * node-b's HELLO names a master key the environment does not hold: node-a refuses it and closes the link. node-a's frames
     * are held at the relay, so node-b never sees them — the link can end only by node-a's close (nor any timeout: the timer
     * stands still). The read fails retryable. The refusal is per link: the same server then serves a reader under its key.
     */
    @Test
    void aHelloUnderAKeyTheEnvironmentDoesNotHoldIsRefusedAndTheLinkClosed() throws Exception {
        FrameRelay wire = relay();
        wire.toDialer().hold();

        OnVirtualThread.Call<byte[]> refused = read(reader(identity("node-b", UNKNOWN), wire), JOURNAL_A);

        assertEquals(FrameRelay.Side.LISTENER, wire.awaitClosedBy(0, "node-a refuses the HELLO"), "node-a closed the link: " + wire.describe());
        assertTrue(wire.toListener().frames() >= 1, "fixture: node-b spoke first (its HELLO): " + wire.describe());
        failedRetryable(refused, "a read over a refused link");

        FrameRelay open = relay();
        assertArrayEquals(body, bytesOf(read(reader(identity("node-b", ENV), open), JOURNAL_A), "witness: under the environment's key"),
                "the same server serves a peer under the environment's key");
    }

    /** W7: a node still on the previous master key (rolling rotation, D-106) is accepted by one that holds it as its previous. */
    @Test
    void aHelloUnderThePreviousKeyIsAccepted() throws Exception {
        assertArrayEquals(body, bytesOf(read(reader(identity("node-b", PREVIOUS), relay()), JOURNAL_A), "under the previous key"),
                "node-a holds [current, previous]: a reader under the previous key reads");
        assertArrayEquals(body, bytesOf(read(reader(identity("node-b", ENV, PREVIOUS), relay()), JOURNAL_A), "under the current key"),
                "and one under the current key");
    }

    // ---------------------------------------------------------------------------------------------------- A1: tags and sizes

    /**
     * One flipped bit in a frame's tag closes the link at the side that checks it: node-a for node-b's READ, node-b for node-a's
     * BYTES (the twin direction). The read fails retryable, never with the bytes. Witness: the same link read m-1 before.
     */
    @ParameterizedTest(name = "a flipped tag bit toward the {0}")
    @CsvSource({"LISTENER", "DIALER"})
    void aFrameWithOneFlippedTagBitClosesTheLink(FrameRelay.Side receiver) throws Exception {
        FrameRelay wire = relay();
        PeerReads reader = reader(identity("node-b", ENV), wire);
        assertArrayEquals(body, bytesOf(read(reader, JOURNAL_A), "witness: the untouched link"));
        assertEquals(1, wire.connections(), "one read link per peer (S1)");
        assertFalse(wire.closed(0), "fixture: the link is up");

        (receiver == FrameRelay.Side.LISTENER ? wire.toListener() : wire.toDialer()).flipLastBitOfNext();
        OnVirtualThread.Call<byte[]> tampered = read(reader, JOURNAL_A);

        failedRetryable(tampered, "a read over a tampered frame");
        assertEquals(receiver, wire.awaitClosedBy(0, "the tampered frame's receiver closes"), "closed by its receiver: " + wire.describe());
    }

    /**
     * A frame whose length announces more than any frame may carry closes the link at its receiver from the length alone: only
     * the u32 is sent, so a receiver that waited for the bytes it announces would never close. 0xFFFFFFFF is the u32's
     * largest (negative as a Java int); 64 MiB + 1 is above a 4 MiB DATA frame with any header. Both directions.
     */
    @ParameterizedTest(name = "a length of {1} toward the {0}")
    @CsvSource({"LISTENER, 4294967295", "LISTENER, 67108865", "DIALER, 4294967295", "DIALER, 67108865"})
    void anOversizedFrameClosesTheLink(FrameRelay.Side receiver, long announced) throws Exception {
        FrameRelay wire = relay();
        PeerReads reader = reader(identity("node-b", ENV), wire);
        assertArrayEquals(body, bytesOf(read(reader, JOURNAL_A), "witness: the link before"));
        assertFalse(wire.closed(0), "fixture: the link is up");

        byte[] header = {(byte) (announced >>> 24), (byte) (announced >>> 16), (byte) (announced >>> 8), (byte) announced};
        (receiver == FrameRelay.Side.LISTENER ? wire.toListener() : wire.toDialer()).inject(header);

        assertEquals(receiver, wire.awaitClosedBy(0, "the oversized frame's receiver closes"), "closed by its receiver: " + wire.describe());
    }

    // ---------------------------------------------------------------------------------------------------- W3: identity

    /**
     * W3: a READ names (owner, journal id, n). One naming node-a and m-1's segment under another journal id — a journal that
     * replaced the one the ref was written in — answers MISS: the read fails retryable, never with the bytes of this journal's
     * segment n. MISS is an answer, not a protocol error: the link stays up and the next read on it, under node-a's journal
     * id, answers the bytes.
     */
    @Test
    void aReadNamingTheOwnerAndSegmentUnderAnotherJournalIdAnswersMissNeverBytes() throws Exception {
        FrameRelay wire = relay();
        PeerReads reader = reader(identity("node-b", ENV), wire);

        failedRetryable(read(reader, UUID.fromString("0c0c0c0c-0000-4000-8000-00000000000c")), "a READ under another journal id");

        assertFalse(wire.closed(0), "MISS keeps the link: " + wire.describe());
        assertArrayEquals(body, bytesOf(read(reader, JOURNAL_A), "under node-a's journal id"), "the next read on the same link");
        assertEquals(1, wire.connections(), "on the same link: " + wire.describe());
    }

    // ---------------------------------------------------------------------------------------------------- W7: the proof

    static Supplier<byte[]> nonces(int seed) {
        byte[] n = new byte[32];
        new java.util.SplittableRandom(seed).nextBytes(n);
        return () -> n.clone();
    }

    static final PeerIdentity A = identity("node-a", ENV);
    static final PeerIdentity B = identity("node-b", ENV);

    /** A listener node-a and a dialer node-b with fixed nonces: two calls give two handshakes that send the same bytes. */
    static PeerHandshake listenerA() { return PeerHandshake.listener(A, nonces(0xA1)); }

    static PeerHandshake dialerB() { return PeerHandshake.dialer(B, "node-a", nonces(0xB1)); }

    /** Witness of the cases below: an honest exchange with these nonces is accepted by both sides. */
    @Test
    void anHonestExchangeIsAcceptedByBothSides() throws Exception {
        PeerHandshake listener = listenerA();
        PeerHandshake dialer = dialerB();
        Buffer lh = listener.hello();
        Buffer dh = dialer.hello();
        Buffer lp = listener.proof(dh);
        Buffer dp = dialer.proof(lh);
        assertNotNull(listener.accept(dp), "the listener accepts the dialer's proof");
        assertNotNull(dialer.accept(lp), "the dialer accepts the listener's proof");
    }

    /**
     * W7: a side's own proof sent back to it (reflection) is refused — by the listener and, the twin, by the dialer. The
     * honest proofs of the same exchange (same nonces) are accepted ({@link #anHonestExchangeIsAcceptedByBothSides}).
     */
    @Test
    void aReflectedProofIsRefused() throws Exception {
        PeerHandshake listener = listenerA();
        PeerHandshake dialer = dialerB();
        Buffer lh = listener.hello();
        Buffer dh = dialer.hello();
        Buffer lp = listener.proof(dh);
        Buffer dp = dialer.proof(lh);

        assertThrows(PeerRefusedException.class, () -> listener.accept(lp), "the listener's own proof, reflected");
        assertThrows(PeerRefusedException.class, () -> dialer.accept(dp), "the dialer's own proof, reflected");
    }

    /**
     * W7: the proof binds the direction. With the same two nodes, nonces and epochs in the other roles (node-b listening,
     * node-a dialing), node-b's proof is a listener's: node-a, listening, refuses it as the dialer's proof; node-a's proof from
     * there is a dialer's: node-b, dialing, refuses it as the listener's.
     */
    @Test
    void aProofForTheOtherDirectionIsRefused() throws Exception {
        PeerHandshake listener = listenerA();
        PeerHandshake dialer = dialerB();
        Buffer lh = listener.hello();
        Buffer dh = dialer.hello();
        listener.proof(dh);
        dialer.proof(lh);

        PeerHandshake bListening = PeerHandshake.listener(B, nonces(0xB1));
        PeerHandshake aDialing = PeerHandshake.dialer(A, "node-b", nonces(0xA1));
        Buffer bHello = bListening.hello();
        Buffer aHello = aDialing.hello();
        Buffer bAsListener = bListening.proof(aHello);
        Buffer aAsDialer = aDialing.proof(bHello);

        assertThrows(PeerRefusedException.class, () -> listener.accept(bAsListener), "node-b's proof for the other direction");
        assertThrows(PeerRefusedException.class, () -> dialer.accept(aAsDialer), "node-a's proof for the other direction");
    }

    /** W7: a HELLO under a master key this side holds neither as current nor as previous is refused at once. */
    @Test
    void aHelloUnderAnUnknownKeyIsRefused() throws Exception {
        PeerHandshake listener = PeerHandshake.listener(identity("node-a", ENV, PREVIOUS), nonces(0xA1));
        PeerHandshake stranger = PeerHandshake.dialer(identity("node-b", UNKNOWN), "node-a", nonces(0xB1));
        listener.hello();
        Buffer hello = stranger.hello();
        assertThrows(PeerRefusedException.class, () -> listener.proof(hello), "a HELLO under an unknown key");

        PeerHandshake rotated = PeerHandshake.listener(identity("node-a", ENV, PREVIOUS), nonces(0xA1));
        PeerHandshake old = PeerHandshake.dialer(identity("node-b", PREVIOUS), "node-a", nonces(0xB1));
        Buffer rh = rotated.hello();
        Buffer oh = old.hello();
        Buffer rp = rotated.proof(oh);
        Buffer op = old.proof(rh);
        assertNotNull(rotated.accept(op), "witness: a HELLO under the previous key is accepted");
        assertNotNull(old.accept(rp), "both ways");
    }
}
