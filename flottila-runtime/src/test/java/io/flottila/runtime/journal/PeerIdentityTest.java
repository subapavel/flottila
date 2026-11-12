package io.flottila.runtime.journal;

import io.flottila.runtime.journal.peer.PeerHandshake;
import io.flottila.runtime.journal.peer.PeerIdentity;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * r2 R4 (CORE-5a): a {@link PeerIdentity} printed — in a log line, an assertion message, a debugger — names its node, its
 * epoch, its journal id and the fingerprints of its master keys, and never the keys: not their bytes in any common encoding,
 * not {@code SecretKeySpec}'s name or hash code (the record's default {@code toString}, which a key's hash leaks through).
 *
 * <p>A fingerprint's expected value is read off the wire, not computed here: the last 8 bytes of a dialer HELLO's payload are
 * the fingerprint of the key it proves under (its current one, PeerHandshake S2), so an identity whose current key is the one
 * asked about shows it. The case assumes the fingerprint is printed as 16 hex digits (either case).
 */
class PeerIdentityTest {

    static final SecretKey CURRENT = JournalFixture.key(7_101);
    static final SecretKey PREVIOUS = JournalFixture.key(7_102);
    static final UUID JOURNAL = UUID.fromString("5e1f5e1f-0000-4000-8000-00000000abcd");
    static final long EPOCH = 7_654_321L;

    @Test
    void anIdentityPrintsItsNodeEpochJournalAndKeyFingerprintsAndNeverItsKeys() {
        String printed = new PeerIdentity("node-printed", EPOCH, JOURNAL, List.of(CURRENT, PREVIOUS)).toString();

        assertTrue(printed.contains("node-printed"), "names its node: " + printed);
        assertTrue(printed.contains(Long.toString(EPOCH)), "names its epoch: " + printed);
        assertTrue(printed.contains(JOURNAL.toString()), "names its journal id: " + printed);
        for (SecretKey k : List.of(CURRENT, PREVIOUS)) {
            String fingerprint = HexFormat.of().formatHex(fingerprintOnTheWire(k));
            assertTrue(printed.toLowerCase(Locale.ROOT).contains(fingerprint), "names the fingerprint " + fingerprint + " of each key: " + printed);
            byte[] bytes = k.getEncoded();
            assertFalse(printed.toLowerCase(Locale.ROOT).contains(HexFormat.of().formatHex(bytes)), "no key bytes in hex: " + printed);
            assertFalse(printed.contains(Base64.getEncoder().encodeToString(bytes)), "no key bytes in Base64: " + printed);
            assertFalse(printed.contains(Base64.getEncoder().withoutPadding().encodeToString(bytes)), "no key bytes in Base64 unpadded: " + printed);
            assertFalse(printed.contains(Arrays.toString(bytes)), "no key bytes as a byte array: " + printed);
            assertFalse(printed.contains(Integer.toHexString(k.hashCode())), "not SecretKeySpec's hash code in hex: " + printed);
            assertFalse(printed.contains(Integer.toString(k.hashCode())), "not SecretKeySpec's hash code: " + printed);
        }
        assertFalse(printed.contains("SecretKeySpec"), "no SecretKeySpec of the default toString: " + printed);
    }

    /** The fingerprint a dialer HELLO under {@code key} carries: its payload's last 8 bytes (the frame's tag is the last 16). */
    static byte[] fingerprintOnTheWire(SecretKey key) {
        byte[] hello = PeerHandshake.dialer(new PeerIdentity("node-x", 1, JOURNAL, List.of(key)), "node-y", () -> new byte[32]).hello().getBytes();
        int end = hello.length - 16;
        return Arrays.copyOfRange(hello, end - 8, end);
    }
}
