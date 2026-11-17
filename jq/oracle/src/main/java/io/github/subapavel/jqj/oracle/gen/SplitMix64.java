package io.github.subapavel.jqj.oracle.gen;

/**
 * The PRNG of every generator (ADR-0025): SplitMix64 (Steele, Lea and Flood, 2014) with the seed as its state, written
 * here so that no JDK release can change a case. Every bounded draw is integer arithmetic on its 64-bit output.
 */
public final class SplitMix64 {

  private static final long GAMMA = 0x9E3779B97F4A7C15L;

  private long state;

  public SplitMix64(long seed) {
    this.state = seed;
  }

  /** The next 64 bits: the state advances by the golden gamma and is mixed (Stafford's variant 13). */
  public long nextLong() {
    long z = state += GAMMA;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** A uniform int in {@code [0, bound)}. */
  public int below(int bound) {
    return (int) belowLong(bound);
  }

  /** A uniform int in {@code [low, high]}. */
  public int between(int low, int high) {
    return low + below(high - low + 1);
  }

  /**
   * A uniform long in {@code [0, bound)}: the remainder of the top 63 bits, redrawn while they fall in the last,
   * partial block of {@code bound} values, which would favour the low remainders.
   *
   * @throws IllegalArgumentException if {@code bound < 1}
   */
  public long belowLong(long bound) {
    if (bound < 1) throw new IllegalArgumentException("bound " + bound + " < 1");
    long u = nextLong() >>> 1;
    long r = u % bound;
    while (u - r + (bound - 1) < 0) {
      u = nextLong() >>> 1;
      r = u % bound;
    }
    return r;
  }
}
