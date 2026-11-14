package io.flottila.runtime.journal;

import io.flottila.api.Hash;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * A large body's source for the CORE-4b cases: {@code size} deterministic bytes of {@code seed}, made 64 KiB at a time (never
 * held whole, so a body many times the heap passes), with no mark or reset (it can be read once), counting what it handed
 * out. The bytes have no period: any two 1 MiB chunks differ, so a swapped chunk can never read back as the right bytes.
 */
final class GeneratedInput extends InputStream {

    private static final int BLOCK = 64 * 1024;

    private final long seed;
    private final long size;
    private final byte[] block = new byte[BLOCK];
    private long blockIndex = -1;
    private volatile long delivered;
    private volatile boolean closed;

    GeneratedInput(long seed, long size) {
        if (size < 0) throw new IllegalArgumentException("size " + size);
        this.seed = seed;
        this.size = size;
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (closed) throw new IOException("the generated body was closed");
        if (len == 0) return 0;
        long at = delivered;
        if (at >= size) return -1;
        int n = (int) Math.min(len, size - at);
        for (int done = 0; done < n; ) {
            long index = at / BLOCK;
            if (index != blockIndex) {
                new SplittableRandom(seed * 0x9E3779B97F4A7C15L + index).nextBytes(block);
                blockIndex = index;
            }
            int from = (int) (at % BLOCK);
            int k = Math.min(n - done, BLOCK - from);
            System.arraycopy(block, from, b, off + done, k);
            done += k;
            at += k;
        }
        delivered = at;
        return n;
    }

    @Override public boolean markSupported() { return false; }

    @Override public void close() { closed = true; }

    /** The bytes handed out so far. */
    long delivered() { return delivered; }

    /** The body of {@code seed} and {@code size} as an array (small bodies only). */
    static byte[] bytes(long seed, int size) {
        try (GeneratedInput in = new GeneratedInput(seed, size)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** What a stream held: its length and its {@code sha256-64} hash. */
    record Digest(long size, Hash hash) { }

    /**
     * The digest of the body of {@code seed} and {@code size}: the {@code sha256-64} hash computed here with the JDK's
     * SHA-256, independent of the journal and of the api's hasher (the first eight bytes of the digest, big-endian).
     */
    static Digest expected(long seed, long size) {
        try (GeneratedInput in = new GeneratedInput(seed, size)) {
            return digestOf(in);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The digest of what {@code in} holds, read to its end 64 KiB at a time (never held whole). */
    static Digest digestOf(InputStream in) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[BLOCK];
        long size = 0;
        for (int n; (n = in.read(buf)) >= 0; ) {
            md.update(buf, 0, n);
            size += n;
        }
        return new Digest(size, hashOf(md.digest()));
    }

    static Hash hashOf(byte[] digest) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (digest[i] & 0xFFL);
        return new Hash("sha256-64", v);
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
