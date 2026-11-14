package io.flottila.runtime.journal.peer;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Known answers for the peer link's key derivations (CORE-5a r3; CORE-5a2 P1, P5): the peer subkey K_peer of a master key, its
 * fingerprint, HKDF-SHA256 and the HELLO and proof frames of one fixed exchange. Every expected value is a hex literal computed outside the code under test (commands in the
 * comments: Python 3 {@code hmac}/{@code hashlib}, OpenSSL), never by {@link PeerHandshake}'s own helpers. In this package
 * (test tree) to reach the package-private {@code PeerHandshake.hkdf}.
 *
 * <p>CORE-5a2: the literals below were recomputed outside the code by the test-author, who wrote this class (CORE-5a r3), for the
 * channel byte (P1) and the peer subkey (P5); the python3 commands in the comments are updated with them.
 *
 * <p>Inputs: K1 = bytes 0x00..0x1f, K2 = bytes 0x80..0x9f (32 each); the dialer node-b (epoch 4, journal 0b0b0b0b-…-0b, masters
 * [K1], nonce 0x40..0x5f) dials the listener node-a (epoch 3, journal 0a0a0a0a-…-0a, masters [K2, K1]: K1 its previous key,
 * nonce 0x60..0x7f). The fingerprint label is {@code INFO + " fingerprint"} with {@code INFO = "flottila-peer-v1"}
 * (PeerHandshake.java:35, :39) = {@code "flottila-peer-v1 fingerprint"}; the HKDF info is {@code "flottila-peer-v1"} (:114),
 * the proof label {@code "flottila-peer-v1 proof"} (:38). P5: K_peer = HKDF-SHA256(salt empty, ikm master, info
 * {@code "flottila-peer-v1 subkey"}, 32); the fingerprint is HMAC-SHA256(K_peer, label)[0..8] and the session keys HKDF over K_peer:
 * the master key keys no HMAC on the link; its only use there is the input of the one HKDF that makes the subkey. Both HELLOs
 * name channel read (1); the listener's too (assumed: a 5a2 listener's HELLO goes out before the dialer's, on the only channel
 * 5a2 uses).
 */
class PeerKnownAnswerTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] K1_BYTES = range(0x00, 32);
    private static final byte[] K2_BYTES = range(0x80, 32);
    private static final SecretKey K1 = new SecretKeySpec(K1_BYTES, "AES");
    private static final SecretKey K2 = new SecretKeySpec(K2_BYTES, "AES");
    private static final UUID JOURNAL_A = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");
    private static final UUID JOURNAL_B = UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b");

    /*
     * K_peer (P5), with H and hkdf of the python3 block at bothProofFramesOfAFixedExchangeAreTheKnownBytes:
     * python3 -c "import hmac,hashlib;H=lambda k,m:hmac.new(k,m,hashlib.sha256).digest();prk=H(b'',bytes(range(32)));print(H(prk,b'flottila-peer-v1 subkey'+bytes([1])).hex())"
     *   (one expand block: L = 32 = HashLen; hmac.new(b'') pads the empty salt to the zeros RFC 5869 §2.2 names)
     */
    private static final String SUB_K1 = "29270d2bedfc61712f9fd2011df34155d0e57e067591f705e25c765d56a8b969";
    /* the same with bytes(range(0x80,0xa0)) */
    private static final String SUB_K2 = "373384c9a9bf6c4c3cabf482d24052a756c7b31e19d4f4c5683a6b1a5b96b953";
    /*
     * python3 -c "import hmac,hashlib;print(hmac.new(bytes.fromhex(SUB_K1),b'flottila-peer-v1 fingerprint',hashlib.sha256).hexdigest()[:16])"
     *   (SUB_K1 written out)
     */
    private static final String FP_K1 = "921381e52ce9d750";
    /* the same under SUB_K2 */
    private static final String FP_K2 = "aa0aa5b0de650e76";
    /*
     * The master-keyed values of 5a, a mutant's answers (the master key used directly, P5 says never):
     * python3 -c "import hmac,hashlib;print(hmac.new(bytes(range(32)),b'flottila-peer-v1 fingerprint',hashlib.sha256).hexdigest()[:16])"
     */
    private static final String MASTER_FP_K1 = "0a2082a6b58e11ea";
    /* the same under bytes(range(0x80,0xa0)) */
    private static final String MASTER_FP_K2 = "99a7d37570fe2dfc";

    private static byte[] range(int from, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (from + i);
        return b;
    }

    private static Supplier<byte[]> nonce(int from) {
        return () -> range(from, PeerHandshake.NONCE);
    }

    private static PeerHandshake dialer() {
        return PeerHandshake.dialer(new PeerIdentity("node-b", 4, JOURNAL_B, List.of(K1)), "node-a", nonce(0x40));
    }

    private static PeerHandshake listener() {
        return PeerHandshake.listener(new PeerIdentity("node-a", 3, JOURNAL_A, List.of(K2, K1)), nonce(0x60));
    }

    /** P5: K_peer is HKDF-SHA256 of the master key with an empty salt under its own label, 32 bytes. */
    @Test
    void thePeerSubkeyIsTheKnownHkdfOfTheMasterKeyUnderItsOwnLabel() {
        byte[] info = "flottila-peer-v1 subkey".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(SUB_K1, HEX.formatHex(PeerHandshake.hkdf(new byte[0], K1_BYTES, info, 32)), "K_peer of K1");
        assertEquals(SUB_K2, HEX.formatHex(PeerHandshake.hkdf(new byte[0], K2_BYTES, info, 32)), "K_peer of K2");
    }

    /**
     * P5: the fingerprint a HELLO sends is HMAC-SHA256(K_peer, "flottila-peer-v1 fingerprint")[0..8]: not the master-keyed one of
     * 5a, and neither a prefix of the master key's bytes nor of the subkey's.
     */
    @Test
    void theHelloCarriesTheKnownFingerprintOfItsCurrentKeysSubkeyAndNoKeyBytes() {
        byte[] hello = dialer().hello().getBytes();
        byte[] sent = Arrays.copyOfRange(hello, hello.length - Frames.TAG - 8, hello.length - Frames.TAG);
        assertNotEquals(MASTER_FP_K1, HEX.formatHex(sent), "not HMAC under the master key itself (the 5a fingerprint)");
        assertEquals(FP_K1, HEX.formatHex(sent), "the dialer's HELLO names K1 by its subkey's known fingerprint");
        assertFalse(Arrays.equals(sent, Arrays.copyOf(K1_BYTES, 8)), "the fingerprint sent before auth is not a prefix of the key");
        assertFalse(Arrays.equals(sent, Arrays.copyOf(HEX.parseHex(SUB_K1), 8)), "nor a prefix of its subkey");

        byte[] listenerHello = listener().hello().getBytes();
        byte[] named = Arrays.copyOfRange(listenerHello, listenerHello.length - Frames.TAG - 8, listenerHello.length - Frames.TAG);
        assertNotEquals(MASTER_FP_K2, HEX.formatHex(named), "not HMAC under K2 itself");
        assertEquals(FP_K2, HEX.formatHex(named), "the listener's HELLO names its current key K2");
        assertFalse(Arrays.equals(named, Arrays.copyOf(K2_BYTES, 8)), "not a prefix of K2 either");
        assertFalse(Arrays.equals(named, Arrays.copyOf(HEX.parseHex(SUB_K2), 8)), "nor of K2's subkey");
    }

    /**
     * The whole HELLO frame of the dialer: u32 len · u8 type 1 · u64 seq 0 · v1 · role 1 · channel read 1 (P1) · u16 6 "node-b" ·
     * epoch 4 · journal · nonce · fingerprint · zero tag (python3 below: {@code struct.pack('>I', 100) + b'\x01' + bytes(8) + dh +
     * bytes(16)}).
     */
    @Test
    void theDialersHelloFrameIsTheKnownBytes() {
        assertEquals("00000064010000000000000000" + "010101" + "0006" + "6e6f64652d62" + "0000000000000004"
                        + "0b0b0b0b00004000800000000000000b"
                        + "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f" + FP_K1
                        + "00000000000000000000000000000000",
                HEX.formatHex(dialer().hello().getBytes()));
    }

    /** The listener's: v1 · role 2 · channel read 1 · u16 6 "node-a" · epoch 3 · journal · nonce · K2's fingerprint ({@code lh} below). */
    @Test
    void theListenersHelloFrameIsTheKnownBytes() {
        assertEquals("00000064010000000000000000" + "010201" + "0006" + "6e6f64652d61" + "0000000000000003"
                        + "0a0a0a0a00004000800000000000000a"
                        + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" + FP_K2
                        + "00000000000000000000000000000000",
                HEX.formatHex(listener().hello().getBytes()));
    }

    /** PeerIdentity.toString prints each key's known fingerprint, current first, and no key byte prefix. */
    @Test
    void anIdentityPrintsItsKeysKnownFingerprintsAndNotTheirBytes() {
        String printed = new PeerIdentity("node-a", 3, JOURNAL_A, List.of(K2, K1)).toString();
        assertTrue(printed.contains("masters=[" + FP_K2 + ", " + FP_K1 + "]"), printed);
        assertFalse(printed.contains(HEX.formatHex(K1_BYTES, 0, 8)), "no prefix of K1's bytes: " + printed);
        assertFalse(printed.contains(HEX.formatHex(K2_BYTES, 0, 8)), "no prefix of K2's bytes: " + printed);
        assertFalse(printed.contains(SUB_K1.substring(0, 16)) || printed.contains(SUB_K2.substring(0, 16)), "no prefix of a subkey: " + printed);
    }

    /** RFC 5869 Appendix A.3, test case 3 (SHA-256): IKM 22 × 0x0b, salt and info empty, L = 42 — the empty salt K_peer takes. */
    @Test
    void hkdfAnswersRfc5869TestCase3WithAnEmptySalt() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                HEX.formatHex(PeerHandshake.hkdf(new byte[0], ikm, new byte[0], 42)));
    }

    /** RFC 5869 Appendix A.1, test case 1 (SHA-256): IKM 22 × 0x0b, salt 0x00..0x0c, info 0xf0..0xf9, L = 42. */
    @Test
    void hkdfAnswersRfc5869TestCase1() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HEX.formatHex(PeerHandshake.hkdf(salt, ikm, info, 42)));
    }

    /**
     * Both proof frames of the fixed exchange (the listener finds K1 by the dialer's fingerprint among [K2, K1]): keys =
     * HKDF-SHA256(salt = dialer nonce ‖ listener nonce, ikm = K_peer of K1, info "flottila-peer-v1", 128) split into dialer proof, listener
     * proof, dialer frames, listener frames; a proof = HMAC(direction proof key, "flottila-peer-v1 proof" ‖ dialer HELLO payload ‖
     * listener HELLO payload) in a PROOF frame of sequence 1 whose tag is HMAC(direction frame key, frame without tag)[0..16].
     * Computed outside the code under test (python3; the same hkdf answers RFC 5869 TC1 above):
     * <pre>
     * import hmac, hashlib, struct, uuid
     * H = lambda k, m: hmac.new(k, m, hashlib.sha256).digest()
     * def hkdf(salt, ikm, info, n):
     *     prk, out, t, i = H(salt, ikm), b'', b'', 1
     *     while len(out) < n: t = H(prk, t + info + bytes([i])); out += t; i += 1
     *     return out[:n]
     * k1, k2 = bytes(range(32)), bytes(range(0x80, 0xa0)); dn, ln = bytes(range(0x40, 0x60)), bytes(range(0x60, 0x80))
     * sub = lambda k: hkdf(b'', k, b'flottila-peer-v1 subkey', 32)            # SUB_K1, SUB_K2
     * fp = lambda k: H(sub(k), b'flottila-peer-v1 fingerprint')[:8]          # FP_K1, FP_K2
     * def hello(role, node, epoch, j, nonce, k):                              # channel read: the 1 after role
     *     return bytes([1, role, 1]) + struct.pack('>H', len(node)) + node + struct.pack('>q', epoch) + uuid.UUID(j).bytes + nonce + fp(k)
     * dh = hello(1, b'node-b', 4, '0b0b0b0b-0000-4000-8000-00000000000b', dn, k1)
     * lh = hello(2, b'node-a', 3, '0a0a0a0a-0000-4000-8000-00000000000a', ln, k2)
     * keys = hkdf(dn + ln, sub(k1), b'flottila-peer-v1', 128)
     * def proof(pk, fk):
     *     f = struct.pack('>I', 57) + bytes([2]) + struct.pack('>q', 1) + H(pk, b'flottila-peer-v1 proof' + dh + lh)
     *     return (f + H(fk, f)[:16]).hex()
     * print(proof(keys[0:32], keys[64:96]), proof(keys[32:64], keys[96:128]))   # dialer's, listener's
     * </pre>
     * The MASTER_* frames: the same with {@code fp = lambda k: H(k, ...)[:8]} and {@code keys = hkdf(dn + ln, k1, ...)}, the 5a2
     * HELLOs otherwise — what a mutant using the master key directly answers.
     */
    @Test
    void bothProofFramesOfAFixedExchangeAreTheKnownBytes() throws Exception {
        PeerHandshake dialer = dialer();
        PeerHandshake listener = listener();
        Buffer dialerProof = dialer.proof(listener.hello());
        Buffer listenerProof = listener.proof(dialer.hello());
        assertNotEquals(MASTER_DIALER_PROOF, HEX.formatHex(dialerProof.getBytes()), "the dialer's proof is not derived from K1 itself");
        assertNotEquals(MASTER_LISTENER_PROOF, HEX.formatHex(listenerProof.getBytes()), "nor the listener's");
        assertEquals(DIALER_PROOF, HEX.formatHex(dialerProof.getBytes()), "the dialer's proof frame");
        assertEquals(LISTENER_PROOF, HEX.formatHex(listenerProof.getBytes()), "the listener's proof frame");
    }

    private static final String DIALER_PROOF = "00000039020000000000000001"
            + "54cd8faac02fec83412db46d4ce3dbb3e172e5222b6d95a9323ffb9d1ff1aeb5" + "7b0061c6c1681546864de81b39dfcb23";
    private static final String LISTENER_PROOF = "00000039020000000000000001"
            + "7ff9e01b14a6c639384d69a3413659a39f9fec853847d12489333a257810e905" + "379ee1237dbc381a9bb523d80c5114c5";
    private static final String MASTER_DIALER_PROOF = "00000039020000000000000001"
            + "5f2257d558968dbd5d559e69ea56adcfe24bd17a0a4b75e7ce83356f5c9dd202" + "eb95677b6c3be6fed605e5425e9265ee";
    private static final String MASTER_LISTENER_PROOF = "00000039020000000000000001"
            + "7088689f0c1bb7293af99f0888deb8df707635c7accf9d25b16f751f47e7f115" + "aa17533675d435f5def6f0b5fca9469f";

    /**
     * Fixture self-test: {@link ScriptedPeer}, which the CORE-5a2 HELLO cases dial with, derives the same known answers from the
     * packet's text — the subkeys, the fingerprints, the HELLO frames, both proof frames, the empty-salt HKDF of RFC 5869 TC3.
     */
    @Test
    void theScriptedPeerDerivesTheKnownAnswers() {
        assertEquals(SUB_K1, HEX.formatHex(ScriptedPeer.subkey(K1)));
        assertEquals(SUB_K2, HEX.formatHex(ScriptedPeer.subkey(K2)));
        assertEquals(FP_K1, HEX.formatHex(ScriptedPeer.fingerprint(K1)));
        assertEquals(FP_K2, HEX.formatHex(ScriptedPeer.fingerprint(K2)));
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                HEX.formatHex(ScriptedPeer.hkdf(new byte[0], ikm, new byte[0], 42)));
        ScriptedPeer.Hello dh = ScriptedPeer.Hello.dialer(new PeerIdentity("node-b", 4, JOURNAL_B, List.of(K1)), range(0x40, 32));
        ScriptedPeer.Hello lh = ScriptedPeer.Hello.listener(new PeerIdentity("node-a", 3, JOURNAL_A, List.of(K2, K1)), range(0x60, 32));
        assertEquals("00000064010000000000000000" + "010101" + "0006" + "6e6f64652d62" + "0000000000000004" + "0b0b0b0b00004000800000000000000b"
                + "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f" + FP_K1 + "00000000000000000000000000000000",
                HEX.formatHex(dh.frame().getBytes()));
        byte[][] proofs = ScriptedPeer.proofFrames(dh.payload(), lh.payload(), K1);
        assertEquals(DIALER_PROOF, HEX.formatHex(proofs[0]));
        assertEquals(LISTENER_PROOF, HEX.formatHex(proofs[1]));
    }
}
