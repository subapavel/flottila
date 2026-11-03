package io.flottila.sim;

import java.util.SplittableRandom;

/**
 * The seed of one run (D-101). {@link #of} gives each {@link Stream} its own seed, {@code SplitMix64(run ^ salt)}; {@link #tick}
 * gives each tick of a stream a generator of its own, independent of what earlier ticks drew.
 *
 * @param run the run seed, printed as {@link #hex()}
 */
public record SimSeed(long run) {

    /** {@code SplitMix64(run ^ stream.salt())}. */
    public long of(Stream stream) {
        return Mix.splitMix64(run ^ stream.salt());
    }

    /** A fresh generator for {@code tick} of {@code stream}: {@code new SplittableRandom(mix(of(stream), tick))}. */
    public SplittableRandom tick(Stream stream, long tick) {
        return new SplittableRandom(Mix.mix(of(stream), tick));
    }

    /** The run seed as the failure line and the replay command print it: {@code 0x} and its unsigned hex digits. */
    public String hex() {
        return "0x" + Long.toHexString(run);
    }

    /** The seed {@link #hex()} printed. */
    public static SimSeed parse(String hex) {
        if (!hex.startsWith("0x")) throw new IllegalArgumentException("a seed is printed 0x<hex digits>, was '" + hex + "'");
        return new SimSeed(Long.parseUnsignedLong(hex.substring(2), 16));
    }
}
