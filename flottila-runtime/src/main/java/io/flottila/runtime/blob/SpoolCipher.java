package io.flottila.runtime.blob;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * The encrypted local spool as a <em>stream</em>: AES-256-GCM in segments of {@value #SEGMENT} plaintext
 * bytes, one tag per segment, under a <em>per-file key</em> derived from the node's master key and a random
 * 16-byte salt written at the start of the file (HKDF-SHA256, RFC 5869; the shape of Tink's
 * AES-GCM-HKDF streaming). Nonce = the salt's first 8 bytes ‖ 4-byte segment counter whose top bit marks
 * the last segment — fresh per file because the key is fresh per file, so one master key never sees a
 * repeated (key, nonce) pair. A reader decrypts one segment at a time and hands bytes out only after that
 * segment's tag verified: memory is one segment, never the body; a flipped byte fails the read at its
 * segment, every byte delivered before it was authenticated; a truncated file fails at the missing last
 * segment; a swapped or repeated segment fails because its counter is in the nonce; bytes after the last
 * segment are an error. (The JDK's plain {@code CipherInputStream} over GCM releases nothing before the
 * final tag — measured 330 MiB of heap for one 64 MiB read — which is why the format is segmented.)
 *
 * <p>Memory: a reader holds one decrypted segment; a writer holds one plaintext segment plus its ciphertext
 * (2 MiB per open spill, bounded by the flow's admission, never the body).
 *
 * <p>File layout: {@code salt[16]} then per segment {@code flag[1] (1 = last) · length[4] (ciphertext incl.
 * tag) · ciphertext}. The empty body is one empty last segment, so truncation is always detectable.
 */
final class SpoolCipher {

    static final int SEGMENT = 1 << 20;
    /** The cipher is fed in slices of this size: a one-shot doFinal per segment is invoked too rarely for the JIT to compile
     *  the GCM path (measured 55 MB/s against 1.5–2.3 GB/s once compiled), many update() calls warm it up within a body. */
    static final int SLICE = 64 * 1024;
    static final int SALT_BYTES = 16;
    private static final int NONCE_PREFIX_BYTES = 8;
    static final int TAG_BYTES = 16;
    private static final int HEADER_BYTES = 5;
    private static final int LAST = 0x80000000;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    SpoolCipher(SecretKey key) { this.key = key; }

    OutputStream encrypting(Path file) throws IOException {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        OutputStream raw = Files.newOutputStream(file);
        try {
            SecretKey fileKey = fileKey(key, salt);
            cipher(fileKey, Cipher.ENCRYPT_MODE, salt, 0);   // fails here, at open, for a key the cipher cannot use
            raw.write(salt);
            return new Encrypting(raw, salt, fileKey);
        } catch (GeneralSecurityException | IOException e) {
            raw.close();
            throw new IOException("cannot open the spool cipher", e);
        }
    }

    InputStream decrypting(Path file) throws IOException {
        InputStream raw = Files.newInputStream(file);
        try {
            masterMaterial(key);   // refused before any byte of the file is read
            byte[] salt = raw.readNBytes(SALT_BYTES);
            if (salt.length != SALT_BYTES) throw new IOException("spool file too short for its salt: " + file);
            SecretKey fileKey = fileKey(key, salt);
            cipher(fileKey, Cipher.DECRYPT_MODE, salt, 0);
            return new Decrypting(raw, salt, fileKey);
        } catch (GeneralSecurityException | IOException e) {
            raw.close();
            throw new IOException("cannot open the spool cipher", e);
        }
    }

    /** HKDF-SHA256 (RFC 5869) with the JDK's Mac: extract with the salt, expand one block with a fixed info string → AES-256 key. */
    static SecretKey fileKey(SecretKey master, byte[] salt) throws GeneralSecurityException {
        byte[] ikm = masterMaterial(master);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update("flottila-spool-v1".getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 1);
        return new SecretKeySpec(mac.doFinal(), "AES");
    }

    private static byte[] masterMaterial(SecretKey master) throws java.security.InvalidKeyException {
        byte[] ikm = master.getEncoded();
        if (ikm == null || !"AES".equals(master.getAlgorithm())) throw new java.security.InvalidKeyException("spool master key must be an encodable AES key");
        return ikm;
    }

    private static Cipher cipher(SecretKey fileKey, int mode, byte[] salt, int counterWithFlag) throws GeneralSecurityException {
        byte[] nonce = ByteBuffer.allocate(12).put(salt, 0, NONCE_PREFIX_BYTES).putInt(counterWithFlag).array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, fileKey, new GCMParameterSpec(TAG_BYTES * 8, nonce));
        return c;
    }

    private final class Encrypting extends OutputStream {
        private final OutputStream out;
        private final byte[] salt;
        private final SecretKey fileKey;
        private final byte[] segment = new byte[SEGMENT];
        private int filled;
        private int counter;
        private boolean closed;

        Encrypting(OutputStream out, byte[] salt, SecretKey fileKey) { this.out = out; this.salt = salt; this.fileKey = fileKey; }

        @Override public void write(int b) throws IOException {
            if (filled == SEGMENT) seal(false);
            segment[filled++] = (byte) b;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (filled == SEGMENT) seal(false);
                int n = Math.min(len, SEGMENT - filled);
                System.arraycopy(b, off, segment, filled, n);
                filled += n;
                off += n;
                len -= n;
            }
        }

        private void seal(boolean last) throws IOException {
            try {
                Cipher c = cipher(fileKey, Cipher.ENCRYPT_MODE, salt, counter | (last ? LAST : 0));
                byte[] ct = new byte[filled + TAG_BYTES];
                int produced = 0;
                for (int off = 0; off < filled; off += SLICE) produced += c.update(segment, off, Math.min(SLICE, filled - off), ct, produced);
                produced += c.doFinal(ct, produced);
                out.write(ByteBuffer.allocate(HEADER_BYTES).put((byte) (last ? 1 : 0)).putInt(produced).array());
                out.write(ct, 0, produced);
                counter++;
                filled = 0;
            } catch (GeneralSecurityException e) {
                throw new IOException("spool encryption failed", e);
            }
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                seal(true);
            } finally {
                out.close();
            }
        }
    }

    private final class Decrypting extends InputStream {
        private final InputStream in;
        private final byte[] salt;
        private final SecretKey fileKey;
        private byte[] plain = new byte[0];
        private int pos;
        private int counter;
        private boolean finished;
        private boolean trailingChecked;

        Decrypting(InputStream in, byte[] salt, SecretKey fileKey) { this.in = in; this.salt = salt; this.fileKey = fileKey; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos == plain.length) {
                if (finished) {
                    if (!trailingChecked) {
                        trailingChecked = true;
                        if (in.read() >= 0) throw new IOException("spool file has data after its last segment");
                    }
                    return -1;
                }
                nextSegment();
            }
            int n = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, n);
            pos += n;
            return n;
        }

        private void nextSegment() throws IOException {
            byte[] header = in.readNBytes(HEADER_BYTES);
            if (header.length == 0) throw new EOFException("spool file truncated: no last segment after " + counter + " segment(s)");
            if (header.length != HEADER_BYTES) throw new EOFException("spool file truncated inside a segment header");
            ByteBuffer h = ByteBuffer.wrap(header);
            boolean last = h.get() == 1;
            int length = h.getInt();
            if (length < TAG_BYTES || length > SEGMENT + TAG_BYTES) throw new IOException("spool segment length out of range: " + length);
            byte[] ct = in.readNBytes(length);
            if (ct.length != length) throw new EOFException("spool file truncated inside segment " + counter);
            try {
                Cipher c = cipher(fileKey, Cipher.DECRYPT_MODE, salt, counter | (last ? LAST : 0));
                byte[] out = new byte[length - TAG_BYTES];   // exactly the plaintext: an authenticated GCM segment yields length - TAG_BYTES bytes
                int produced = 0;
                for (int off = 0; off < length; off += SLICE) produced += c.update(ct, off, Math.min(SLICE, length - off), out, produced);
                c.doFinal(out, produced);
                plain = out;
            } catch (GeneralSecurityException e) {
                throw new IOException("spool segment " + counter + " failed authentication", e);
            }
            pos = 0;
            counter++;
            finished = last;
        }

        @Override public void close() throws IOException { in.close(); }
    }
}
