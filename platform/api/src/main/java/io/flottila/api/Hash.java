package io.flottila.api;

/**
 * A 64-bit content hash together with the algorithm that produced it. Two hashes are equal only if
 * both agree; a deployment uses one {@link Hasher} everywhere, so the algorithm name travels with
 * the value instead of being assumed.
 *
 * @param algorithm short algorithm id, e.g. {@code xxh3-64} or {@code sha256-64}
 * @param value the 64-bit hash
 * @since 0.1
 */
public record Hash(String algorithm, long value) {

    public Hash {
        if (algorithm == null || algorithm.isBlank()) throw new IllegalArgumentException("algorithm");
    }

    /** The value as sixteen lower-case hex digits. */
    public String hex() {
        return String.format("%016x", value);
    }
}
