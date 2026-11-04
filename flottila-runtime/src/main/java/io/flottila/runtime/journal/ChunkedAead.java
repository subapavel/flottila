package io.flottila.runtime.journal;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;

/**
 * The chunked AEAD construction of the encrypted spool, shared by the journal's records (CORE-3 item 2): a key per object,
 * HKDF-SHA256 (RFC 5869: extract with a random 16-byte salt, expand one block under a fixed info label) of a master key,
 * and AES-256-GCM per chunk with a 16-byte tag under the nonce {@code salt[0..8) ‖ u32 counter}, whose top bit marks the
 * last chunk. A fresh salt gives a fresh key, so one master key never sees a (key, nonce) pair twice; a chunk swapped,
 * repeated or cut off fails because its counter and the last flag are in its nonce.
 *
 * @since 0.1
 */
public final class ChunkedAead {

    /** The salt's length, written beside the ciphertext. */
    public static final int SALT_BYTES = 16;
    /** The GCM tag's length, one per chunk. */
    public static final int TAG_BYTES = 16;
    /** The counter bit that marks the last chunk. */
    public static final int LAST = 0x80000000;
    private static final int NONCE_PREFIX_BYTES = 8;

    private ChunkedAead() { }

    /** The key material of {@code master}; refused unless it is an encodable AES key ({@code what} names it). */
    public static byte[] material(SecretKey master, String what) throws InvalidKeyException {
        byte[] ikm = master.getEncoded();
        if (ikm == null || !"AES".equals(master.getAlgorithm())) throw new InvalidKeyException(what + " must be an encodable AES key");
        return ikm;
    }

    /** HKDF-SHA256 with the JDK's Mac: extract with {@code salt}, expand one block with {@code info} → an AES-256 key. */
    public static SecretKey derive(SecretKey master, byte[] salt, String info, String what) throws GeneralSecurityException {
        byte[] ikm = material(master, what);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 1);
        return new SecretKeySpec(mac.doFinal(), "AES");
    }

    /** AES/GCM for one chunk: {@code counterWithFlag} is the chunk's counter, ORed with {@link #LAST} on the last one. */
    public static Cipher cipher(SecretKey key, int mode, byte[] salt, int counterWithFlag) throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).put(salt, 0, NONCE_PREFIX_BYTES).putInt(counterWithFlag).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        return c;
    }
}
