package io.flottila.sim;

/** The run's arithmetic of chance (D-101): SplitMix64 and the counter-based folds built on it. */
final class Mix {

    private Mix() {}

    /** One SplitMix64 step: {@code z} plus the golden gamma, mixed. */
    static long splitMix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** {@code h} folded with each part in turn: {@code h = splitMix64(h ^ part)}. */
    static long mix(long h, long... parts) {
        for (long part : parts) h = splitMix64(h ^ part);
        return h;
    }

    /** A text as a long: its length folded with each of its chars. */
    static long of(String text) {
        long h = text.length();
        for (int i = 0; i < text.length(); i++) h = splitMix64(h ^ text.charAt(i));
        return h;
    }

    /** The top 53 bits of {@code h} as a double in [0, 1). */
    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
