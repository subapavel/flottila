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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Known answers for the peer link's key derivations (CORE-5a r3): the master key's fingerprint, HKDF-SHA256 and the proof
 * frames of one fixed exchange. Every expected value is a hex literal computed outside the code under test (commands in the
 * comments: Python 3 {@code hmac}/{@code hashlib}, OpenSSL), never by {@link PeerHandshake}'s own helpers. In this package
 * (test tree) to reach the package-private {@code PeerHandshake.hkdf}.
 *
 * <p>Inputs: K1 = bytes 0x00..0x1f, K2 = bytes 0x80..0x9f (32 each); the dialer node-b (epoch 4, journal 0b0b0b0b-…-0b, masters
 * [K1], nonce 0x40..0x5f) dials the listener node-a (epoch 3, journal 0a0a0a0a-…-0a, masters [K2, K1]: K1 its previous key,
 * nonce 0x60..0x7f). The fingerprint label is {@code INFO + " fingerprint"} with {@code INFO = "flottila-peer-v1"}
 * (PeerHandshake.java:35, :39) = {@code "flottila-peer-v1 fingerprint"}; the HKDF info is {@code "flottila-peer-v1"} (:114),
 * the proof label {@code "flottila-peer-v1 proof"} (:38).
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
     * printf 'flottila-peer-v1 fingerprint' | openssl dgst -sha256 -mac HMAC \
     *     -macopt hexkey:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
     *   → 0a2082a6b58e11eaeff30aed4885922c5e79cf75c342e94d60ffc2f24762880a, its first 8 bytes:
     */
    private static final String FP_K1 = "0a2082a6b58e11ea";
    /* python3 -c "import hmac,hashlib;print(hmac.new(bytes(range(0x80,0xa0)),b'flottila-peer-v1 fingerprint',hashlib.sha256).hexdigest()[:16])" */
    private static final String FP_K2 = "99a7d37570fe2dfc";

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

    /** The fingerprint a HELLO sends is HMAC-SHA256(key, "flottila-peer-v1 fingerprint")[0..8]: never the key's own bytes. */
    @Test
    void theHelloCarriesTheKnownFingerprintOfItsCurrentKeyAndNotTheKeysBytes() {
        byte[] hello = dialer().hello().getBytes();
        byte[] sent = Arrays.copyOfRange(hello, hello.length - Frames.TAG - 8, hello.length - Frames.TAG);
        assertEquals(FP_K1, HEX.formatHex(sent), "the dialer's HELLO names K1 by its known fingerprint");
        assertFalse(Arrays.equals(sent, Arrays.copyOf(K1_BYTES, 8)), "the fingerprint sent before auth is not a prefix of the key");

        byte[] listenerHello = listener().hello().getBytes();
        byte[] named = Arrays.copyOfRange(listenerHello, listenerHello.length - Frames.TAG - 8, listenerHello.length - Frames.TAG);
        assertEquals(FP_K2, HEX.formatHex(named), "the listener's HELLO names its current key K2");
        assertFalse(Arrays.equals(named, Arrays.copyOf(K2_BYTES, 8)), "not a prefix of K2 either");
    }

    /**
     * The whole HELLO frame of the dialer: u32 len · u8 type 1 · u64 seq 0 · v1 · role 1 · u16 6 "node-b" · epoch 4 · journal ·
     * nonce · fingerprint · zero tag (python3 below: {@code struct.pack('>I', 99) + b'\x01' + bytes(8) + dh + bytes(16)}).
     */
    @Test
    void theDialersHelloFrameIsTheKnownBytes() {
        assertEquals("00000063010000000000000000" + "0101" + "0006" + "6e6f64652d62" + "0000000000000004"
                        + "0b0b0b0b00004000800000000000000b"
                        + "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f" + FP_K1
                        + "00000000000000000000000000000000",
                HEX.formatHex(dialer().hello().getBytes()));
    }

    /** PeerIdentity.toString prints each key's known fingerprint, current first, and no key byte prefix. */
    @Test
    void anIdentityPrintsItsKeysKnownFingerprintsAndNotTheirBytes() {
        String printed = new PeerIdentity("node-a", 3, JOURNAL_A, List.of(K2, K1)).toString();
        assertTrue(printed.contains("masters=[" + FP_K2 + ", " + FP_K1 + "]"), printed);
        assertFalse(printed.contains(HEX.formatHex(K1_BYTES, 0, 8)), "no prefix of K1's bytes: " + printed);
        assertFalse(printed.contains(HEX.formatHex(K2_BYTES, 0, 8)), "no prefix of K2's bytes: " + printed);
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
     * HKDF-SHA256(salt = dialer nonce ‖ listener nonce, ikm = K1, info "flottila-peer-v1", 128) split into dialer proof, listener
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
     * fp = lambda k: H(k, b'flottila-peer-v1 fingerprint')[:8]
     * def hello(role, node, epoch, j, nonce, k):
     *     return bytes([1, role]) + struct.pack('>H', len(node)) + node + struct.pack('>q', epoch) + uuid.UUID(j).bytes + nonce + fp(k)
     * dh = hello(1, b'node-b', 4, '0b0b0b0b-0000-4000-8000-00000000000b', dn, k1)
     * lh = hello(2, b'node-a', 3, '0a0a0a0a-0000-4000-8000-00000000000a', ln, k2)
     * keys = hkdf(dn + ln, k1, b'flottila-peer-v1', 128)
     * def proof(pk, fk):
     *     f = struct.pack('>I', 57) + bytes([2]) + struct.pack('>q', 1) + H(pk, b'flottila-peer-v1 proof' + dh + lh)
     *     return (f + H(fk, f)[:16]).hex()
     * print(proof(keys[0:32], keys[64:96]), proof(keys[32:64], keys[96:128]))   # dialer's, listener's
     * </pre>
     */
    @Test
    void bothProofFramesOfAFixedExchangeAreTheKnownBytes() throws Exception {
        PeerHandshake dialer = dialer();
        PeerHandshake listener = listener();
        Buffer dialerProof = dialer.proof(listener.hello());
        Buffer listenerProof = listener.proof(dialer.hello());
        assertEquals("00000039020000000000000001"
                        + "d41e97ca9cad9303362557f3822a61d66e4626ceb8abbe1462f7bbe2f4d13029" + "4a8ad23ee2d4c068a6ba16a87c3f7580",
                HEX.formatHex(dialerProof.getBytes()), "the dialer's proof frame");
        assertEquals("00000039020000000000000001"
                        + "cefbf0ee72e8488d221dee3150dd9e8601960b8115688271668ffcdb3924b76d" + "87df587e51cd6c8ea682a2a648b59a20",
                HEX.formatHex(listenerProof.getBytes()), "the listener's proof frame");
    }
}
