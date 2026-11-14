package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerHandshake;
import io.flottila.runtime.journal.peer.PeerRefusedException;
import io.flottila.runtime.journal.peer.ScriptedPeer;
import io.flottila.runtime.journal.peer.ScriptedPeer.Hello;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Function;

import static io.flottila.runtime.journal.PeerLinkTest.A;
import static io.flottila.runtime.journal.PeerLinkTest.B;
import static io.flottila.runtime.journal.PeerLinkTest.ENV;
import static io.flottila.runtime.journal.PeerLinkTest.nonces;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORE-5a2 acceptance A1, A2 at the handshake, without a socket (packet P1, P2, P5): a HELLO of version 1 or above is taken, its
 * bytes after the v1 fields too, and both proofs cover the whole HELLO as it came; version 0 and a HELLO shorter than its v1
 * fields are refused, both ways; a 5a2 listener refuses a dialer on channel repl or bulk with a refusal naming the channel.
 * The other side is a {@link ScriptedPeer} HELLO, its proofs derived from the packet's text (checked against known answers in
 * {@code PeerKnownAnswerTest#theScriptedPeerDerivesTheKnownAnswers}), never by {@link PeerHandshake}.
 *
 * <p>The API assumed (CORE-5a2, test-author): HELLO v1 = u8 version · u8 role · <b>u8 channel</b> (1 read, 2 repl, 3 bulk; the
 * third payload byte, frame byte 15) · u16 node length · node · epoch · journal id · nonce · fingerprint; the version an unsigned
 * byte; the refusal of a channel names it as {@code repl} or {@code bulk}; {@code PeerHandshake.dialer}/{@code listener}
 * unchanged, both sending channel read.
 */
class PeerHandshakeVersionTest {

    static final byte[] TRAILING = {(byte) 0xfe, 0, 1, 2, 3, 4, 5, 6, (byte) 0xff};

    static byte[] nonce(int seed) {
        return nonces(seed).get();
    }

    static Hello dialerHello() {
        return Hello.dialer(B, nonce(2));
    }

    static Hello listenerHello() {
        return Hello.listener(A, nonce(1));
    }

    /**
     * A1, toward the listener: a dialer's HELLO of version {@code version} with {@code trailing} bytes after its v1 fields is
     * taken; the listener's proof is the one derived over the whole HELLO, trailing bytes included, and the dialer's proof over
     * the same is accepted. A listener proving over the v1 fields only, or refusing the bytes after them, fails it.
     */
    @ParameterizedTest(name = "version {0}, {1} trailing bytes")
    @CsvSource({"2, 9", "255, 9", "1, 9", "2, 0"})
    void aListenerTakesADialersHelloOfVersionOneOrAboveWithTrailingBytesAndProvesOverItWhole(int version, int trailing) throws Exception {
        Hello mine = dialerHello().version(version).trailing(java.util.Arrays.copyOf(TRAILING, trailing));
        PeerHandshake listener = PeerHandshake.listener(A, nonces(1));
        byte[] listenerHello = ScriptedPeer.payloadOf(listener.hello());
        byte[][] proofs = ScriptedPeer.proofFrames(mine.payload(), listenerHello, ENV);

        Buffer theirs = listener.proof(mine.frame());

        assertArrayEquals(proofs[1], theirs.getBytes(), "the listener's proof covers the dialer's whole HELLO");
        assertNotNull(listener.accept(Buffer.buffer(proofs[0])), "the dialer's proof over the same HELLOs is accepted");
    }

    /** A1, toward the dialer: the same for a listener's HELLO of version {@code version} with trailing bytes. */
    @ParameterizedTest(name = "version {0}, {1} trailing bytes")
    @CsvSource({"2, 9", "255, 9", "1, 9", "2, 0"})
    void aDialerTakesAListenersHelloOfVersionOneOrAboveWithTrailingBytesAndProvesOverItWhole(int version, int trailing) throws Exception {
        Hello mine = listenerHello().version(version).trailing(java.util.Arrays.copyOf(TRAILING, trailing));
        PeerHandshake dialer = PeerHandshake.dialer(B, "node-a", nonces(2));
        byte[] dialerHello = ScriptedPeer.payloadOf(dialer.hello());
        byte[][] proofs = ScriptedPeer.proofFrames(dialerHello, mine.payload(), ENV);

        Buffer theirs = dialer.proof(mine.frame());

        assertArrayEquals(proofs[0], theirs.getBytes(), "the dialer's proof covers the listener's whole HELLO");
        assertNotNull(dialer.accept(Buffer.buffer(proofs[1])), "the listener's proof over the same HELLOs is accepted");
    }

    /** Each side's own HELLO names version 1 and channel read (the link speaks min(own, peer) = 1 today). */
    @Test
    void bothSidesSendAV1HelloOnTheReadChannel() {
        for (Buffer hello : new Buffer[] {PeerHandshake.dialer(B, "node-a", nonces(2)).hello(), PeerHandshake.listener(A, nonces(1)).hello()}) {
            byte[] p = ScriptedPeer.payloadOf(hello);
            assertTrue(p[0] == 1 && p[2] == ScriptedPeer.CHANNEL_READ, "version 1, channel read: " + java.util.HexFormat.of().formatHex(p, 0, 3));
        }
    }

    /**
     * A1: version 0, a HELLO one byte shorter than its v1 fields, and a 5a HELLO without the channel byte are refused, both
     * ways. Witness: the same HELLO as written (version 1, whole) is taken by the same side.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"version 0", "one byte short", "no channel byte"})
    void aHelloOfVersion0OrShorterThanItsV1FieldsIsRefusedBothWays(String what) throws Exception {
        Function<Hello, Hello> broken = switch (what) {
            case "version 0" -> h -> h.version(0);
            case "one byte short" -> h -> h.cut(1);
            default -> Hello::withoutChannel;
        };
        assertThrows(PeerRefusedException.class, () -> PeerHandshake.listener(A, nonces(1)).proof(broken.apply(dialerHello()).frame()),
                "a dialer's HELLO, " + what);
        assertThrows(PeerRefusedException.class, () -> PeerHandshake.dialer(B, "node-a", nonces(2)).proof(broken.apply(listenerHello()).frame()),
                "a listener's HELLO, " + what);
        assertNotNull(PeerHandshake.listener(A, nonces(1)).proof(dialerHello().frame()), "witness: the dialer's HELLO as written");
        assertNotNull(PeerHandshake.dialer(B, "node-a", nonces(2)).proof(listenerHello().frame()), "witness: the listener's HELLO as written");
    }

    /**
     * A2: a dialer's HELLO on channel bulk (3) is refused by the listener, the refusal naming that channel. CORE-5b1 C1: the
     * listener takes channel repl (2) now (PeerReplHandshakeTest), so repl left this case.
     */
    @ParameterizedTest(name = "channel {0}: {1}")
    @CsvSource({"3, bulk, repl"})
    void aListenerRefusesADialerOnTheBulkChannelNamingIt(int channel, String name, String other) {
        PeerRefusedException e = assertThrows(PeerRefusedException.class,
                () -> PeerHandshake.listener(A, nonces(1)).proof(dialerHello().channel(channel).frame()), "channel " + name);
        assertTrue(String.valueOf(e.getMessage()).contains(name), "names the channel " + name + ": " + e.getMessage());
        assertFalse(String.valueOf(e.getMessage()).contains(other), "names its own channel, not " + other + ": " + e.getMessage());
    }

    /** A2 witness: channel read links as today, both proofs over the same HELLOs. */
    @Test
    void aDialerOnTheReadChannelLinksAsToday() throws Exception {
        Hello mine = dialerHello().channel(ScriptedPeer.CHANNEL_READ);
        PeerHandshake listener = PeerHandshake.listener(A, nonces(1));
        byte[][] proofs = ScriptedPeer.proofFrames(mine.payload(), ScriptedPeer.payloadOf(listener.hello()), ENV);
        assertArrayEquals(proofs[1], listener.proof(mine.frame()).getBytes(), "the listener's proof");
        assertNotNull(listener.accept(Buffer.buffer(proofs[0])), "the dialer's proof is accepted");
    }
}
