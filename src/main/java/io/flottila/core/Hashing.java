package io.flottila.core;

import java.security.MessageDigest;

/**
 * Content hashing for Body: xxh3-64 via zero-allocation-hashing (Apache 2). If the library is
 * missing at runtime, falls back to the first 8 bytes of JDK SHA-256 — correct but roughly an
 * order of magnitude more CPU per byte; fine for tests, not for the hot path.
 */
final class Hashing {
    private Hashing() {}

    private static final java.util.function.ToLongFunction<byte[]> FN = pick();

    private static java.util.function.ToLongFunction<byte[]> pick() {
        try {
            var xx3 = net.openhft.hashing.LongHashFunction.xx3();
            return xx3::hashBytes;
        } catch (NoClassDefFoundError e) {
            return data -> {
                try {
                    byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
                    long h = 0;
                    for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xffL);
                    return h;
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            };
        }
    }

    static long hash(byte[] data) { return FN.applyAsLong(data); }
}
