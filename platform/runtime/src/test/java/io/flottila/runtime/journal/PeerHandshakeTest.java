package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerHandshake;
import io.flottila.runtime.journal.peer.PeerIdentity;
import io.flottila.runtime.journal.peer.PeerRefusedException;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.flottila.runtime.journal.PeerLinkTest.ENV;
import static io.flottila.runtime.journal.PeerLinkTest.identity;
import static io.flottila.runtime.journal.PeerLinkTest.nonces;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HELLO a handshake takes (CORE-5a S2, W7; implementer, beside {@link PeerLinkTest}'s proofs): only a HELLO of version 1 or above (CORE-5a2 P2) of the other
 * role, as frame sequence 0 with a zero tag, naming — for a dialer — the node it dialled; a proof only after this side's own,
 * and only a proof frame. Each refusal is a {@link PeerRefusedException}; the honest exchange of the same two sides is
 * accepted ({@link PeerLinkTest#anHonestExchangeIsAcceptedByBothSides}).
 */
class PeerHandshakeTest {

    static final PeerIdentity A = identity("node-a", ENV);
    static final PeerIdentity B = identity("node-b", ENV);

    static PeerHandshake listener() { return PeerHandshake.listener(A, nonces(1)); }

    static PeerHandshake dialer() { return PeerHandshake.dialer(B, "node-a", nonces(2)); }

    static Buffer changed(Buffer frame, int at, int xor) {
        Buffer b = frame.copy();
        b.setByte(at, (byte) (b.getByte(at) ^ xor));
        return b;
    }

    @Test
    void aHelloOfTheSameRoleIsRefused() {
        assertThrows(PeerRefusedException.class, () -> listener().proof(PeerHandshake.listener(B, nonces(3)).hello()), "a listener's HELLO to a listener");
        assertThrows(PeerRefusedException.class, () -> dialer().proof(PeerHandshake.dialer(A, "node-b", nonces(3)).hello()), "a dialer's HELLO to a dialer");
    }

    @Test
    void aDialerAnsweredByAnotherNodeThanItDialledIsRefused() throws Exception {
        PeerRefusedException e = assertThrows(PeerRefusedException.class,
                () -> dialer().proof(PeerHandshake.listener(identity("node-c", ENV), nonces(3)).hello()), "node-c answers a dial of node-a");
        assertTrue(e.getMessage().contains("node-c"), e.getMessage());
        assertNotNull(dialer().proof(listener().hello()), "witness: node-a's HELLO is taken");
    }

    /** The bytes of a HELLO are checked: its version, its sequence number, its tag, its length against the node it names. */
    @Test
    void aHelloThatIsNotAV1HelloIsRefused() {
        Buffer hello = listener().hello();
        int payload = 13;   // u32 len · u8 type · u64 seq
        // CORE-5a2 P2 (test-author): a version >= 1 is taken (PeerHandshakeVersionTest), so the refused version here is 0, not 3
        assertThrows(PeerRefusedException.class, () -> dialer().proof(changed(hello, payload, 0x01)), "version 0");
        assertThrows(PeerRefusedException.class, () -> dialer().proof(changed(hello, 12, 0x01)), "sequence 1");
        assertThrows(PeerRefusedException.class, () -> dialer().proof(changed(hello, hello.length() - 1, 0x01)), "a tag not zero");
        assertThrows(PeerRefusedException.class, () -> dialer().proof(changed(hello, payload + 3, 0x01)), "a node length past its end");
        assertThrows(PeerRefusedException.class, () -> dialer().proof(changed(hello, 4, 0x07)), "another type");
        assertThrows(PeerRefusedException.class, () -> dialer().proof(Buffer.buffer(new byte[20])), "shorter than any frame");
        assertThrows(PeerRefusedException.class, () -> dialer().proof(changed(hello, 3, 0x01)), "a length field not the frame's");
        Buffer stub = Buffer.buffer().appendInt(1 + 8 + 5 + 16).appendByte((byte) 1).appendLong(0).appendBytes(new byte[] {1, 2, 0, 0, 0})
                .appendBytes(new byte[16]);
        assertThrows(PeerRefusedException.class, () -> dialer().proof(stub), "a HELLO of five bytes: shorter than its fixed fields");
    }

    /** A proof is taken after this side's own only, and only in a proof frame: a HELLO in its place is refused. */
    @Test
    void aProofIsTakenAfterThisSidesOwnAndOnlyInAProofFrame() throws Exception {
        PeerHandshake l = listener();
        PeerHandshake d = dialer();
        Buffer lh = l.hello();
        Buffer dh = d.hello();
        assertThrows(IllegalStateException.class, () -> l.accept(dh), "before this side's own proof");
        l.proof(dh);
        Buffer dp = d.proof(lh);
        assertThrows(PeerRefusedException.class, () -> l.accept(dh), "a HELLO where the proof is due");
        assertThrows(PeerRefusedException.class, () -> l.accept(changed(dp, 3, 0x01)), "a proof whose length field is not its own");
        assertThrows(PeerRefusedException.class, () -> l.accept(Buffer.buffer(new byte[20])), "shorter than any frame");
        assertNotNull(l.accept(dp), "witness: the dialer's proof");
    }

    /**
     * W7: the proof binds both HELLOs. Two exchanges with the same nonces and key (so the same session keys) whose HELLOs differ
     * in one field only — the dialer's or the listener's epoch, the dialer's or the listener's journal id — do not take each
     * other's proofs.
     */
    @Test
    void theProofBindsBothEpochsAndBothJournalIds() throws Exception {
        java.util.UUID other = java.util.UUID.fromString("0c0c0c0c-0000-4000-8000-00000000000c");
        List<PeerIdentity> dialers = List.of(new PeerIdentity("node-b", 2, B.journal(), B.masters()), new PeerIdentity("node-b", 1, other, B.masters()));
        for (PeerIdentity changed : dialers) {
            PeerHandshake l = listener();
            Buffer lh = l.hello();
            l.proof(dialer().hello());
            Buffer foreign = PeerHandshake.dialer(changed, "node-a", nonces(2)).proof(lh);
            assertThrows(PeerRefusedException.class, () -> l.accept(foreign), "a dialer's proof for " + changed);
        }
        List<PeerIdentity> listeners = List.of(new PeerIdentity("node-a", 2, A.journal(), A.masters()), new PeerIdentity("node-a", 1, other, A.masters()));
        for (PeerIdentity changed : listeners) {
            PeerHandshake d = dialer();
            Buffer dh = d.hello();
            d.proof(listener().hello());
            Buffer foreign = PeerHandshake.listener(changed, nonces(1)).proof(dh);
            assertThrows(PeerRefusedException.class, () -> d.accept(foreign), "a listener's proof for " + changed);
        }
        PeerHandshake l = listener();
        PeerHandshake d = dialer();
        Buffer lh = l.hello();
        Buffer lp = l.proof(d.hello());
        assertNotNull(l.accept(d.proof(lh)), "witness: the unchanged dialer's proof");
        assertNotNull(d.accept(lp), "witness: the unchanged listener's proof");
    }

    /**
     * O6: a dialer proves under its current key, the first it holds: a listener that holds only that one accepts it, one that
     * holds only the previous refuses it — whatever else the dialer holds.
     */
    @Test
    void aDialerProvesUnderItsCurrentKey() throws Exception {
        PeerHandshake rotating = PeerHandshake.dialer(identity("node-b", ENV, PeerLinkTest.PREVIOUS), "node-a", nonces(2));
        PeerHandshake current = listener();
        Buffer ch = current.hello();
        Buffer cp = current.proof(rotating.hello());
        assertNotNull(current.accept(rotating.proof(ch)), "a listener on the current key accepts it");
        assertNotNull(rotating.accept(cp), "both ways");
        PeerHandshake previousOnly = PeerHandshake.listener(identity("node-a", PeerLinkTest.PREVIOUS), nonces(1));
        previousOnly.hello();
        Buffer rh = PeerHandshake.dialer(identity("node-b", ENV, PeerLinkTest.PREVIOUS), "node-a", nonces(2)).hello();
        assertThrows(PeerRefusedException.class, () -> previousOnly.proof(rh), "a listener holding only the previous key refuses it");
    }

    @Test
    void aNonceNotOf32BytesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PeerHandshake.listener(A, () -> new byte[31]), "31 bytes");
        assertThrows(IllegalArgumentException.class, () -> PeerHandshake.dialer(B, "node-a", () -> new byte[33]), "33 bytes");
        assertNotNull(PeerHandshake.listener(A, () -> new byte[32]).hello(), "witness: 32 bytes");
    }

    @Test
    void anIdentityNeedsTheEnvironmentsKey() {
        assertThrows(IllegalArgumentException.class, () -> new PeerIdentity("node-a", 1, PeerLinkTest.JOURNAL_A, List.of()));
    }
}
