package io.flottila.api;

import io.vertx.core.buffer.Buffer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes the {@link Hash} of a body. Injected through {@link Runtime} so that every node of a
 * deployment hashes the same way; the production hasher (xxh3-64) lives in the runtime module,
 * {@link #sha256()} is the JDK-only default for tests and small installs.
 *
 * @since 0.1
 */
public interface Hasher {

    /** Short algorithm id carried in every {@link Hash}. */
    String algorithm();

    /** One-shot hash of a complete byte array. */
    long hash(byte[] data);

    /** One-shot hash of a complete buffer. */
    default Hash hashOf(Buffer data) {
        return new Hash(algorithm(), hash(data.getBytes()));
    }

    /** Starts a streaming computation; {@link Session#finish()} yields the same value as one-shot hashing. */
    Session begin();

    /**
     * Streaming hash state for bodies that arrive in chunks.
     *
     * @since 0.1
     */
    interface Session {
        void update(Buffer chunk);

        Hash finish();
    }

    /** SHA-256 truncated to its first eight bytes (big-endian), algorithm id {@code sha256-64}. JDK only. */
    static Hasher sha256() {
        return Sha256Hasher.INSTANCE;
    }
}

/** Package-private default: SHA-256 truncated to 64 bits, as the milestone-1 prototype's fallback did. */
final class Sha256Hasher implements Hasher {

    static final Sha256Hasher INSTANCE = new Sha256Hasher();
    static final String ALGORITHM = "sha256-64";

    private Sha256Hasher() {}

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    static long firstEightBytes(byte[] digest) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (digest[i] & 0xffL);
        return v;
    }

    @Override public String algorithm() { return ALGORITHM; }

    @Override public long hash(byte[] data) { return firstEightBytes(digest().digest(data)); }

    @Override public Session begin() {
        MessageDigest md = digest();
        return new Session() {
            @Override public void update(Buffer chunk) { md.update(chunk.getBytes()); }
            @Override public Hash finish() { return new Hash(ALGORITHM, firstEightBytes(md.digest())); }
        };
    }
}
