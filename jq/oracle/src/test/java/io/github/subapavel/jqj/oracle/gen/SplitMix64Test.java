package io.github.subapavel.jqj.oracle.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The generators' own PRNG (ADR-0025): SplitMix64 with the seed as its state, integer draws only. */
class SplitMix64Test {

  /**
   * JDK 25's {@code SplittableRandom(long)} is SplitMix64 with the golden gamma (javap: {@code seed=s},
   * {@code gamma=-7046029254386353131}, {@code nextLong = mix64(seed += gamma)}). It is the reference here, never a
   * source of cases: it promises its sequence only within one program.
   */
  @Test
  void splitMix64MatchesReference() {
    for (long seed : new long[] {0, 1, 2, 41, 2099, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x9E3779B97F4A7C15L}) {
      SplitMix64 ours = new SplitMix64(seed);
      SplittableRandom reference = new SplittableRandom(seed);
      for (int i = 0; i < 1000; i++) assertThat(ours.nextLong()).as("seed %d draw %d", seed, i).isEqualTo(reference.nextLong());
    }
  }

  @Test
  void belowStaysInRangeAndReachesEveryValue() {
    SplitMix64 r = new SplitMix64(7);
    int[] seen = new int[7];
    for (int i = 0; i < 7000; i++) seen[r.below(7)]++;
    for (int v = 0; v < 7; v++) assertThat(seen[v]).as("value %d", v).isBetween(850, 1150);
    assertThat(new SplitMix64(7).below(1)).isZero();
  }

  /**
   * A bound that does not divide 2^63 must reject the draws of its last, partial block. With {@code bound = 2^62 + 1}
   * the only full block is [0, bound), so a bounded draw is the first raw 63-bit draw below the bound, and nearly half
   * of the raw draws are rejected.
   */
  @Test
  void belowRejectsTheBiasedTail() {
    SplitMix64 r = new SplitMix64(3);
    for (int i = 0; i < 1000; i++) assertThat(r.below(Integer.MAX_VALUE)).isBetween(0, Integer.MAX_VALUE - 1);
    long big = (1L << 62) + 1;
    SplitMix64 bounded = new SplitMix64(11);
    SplitMix64 raw = new SplitMix64(11);
    int rejected = 0;
    for (int i = 0; i < 64; i++) {
      long u = raw.nextLong() >>> 1;
      while (u >= big) {
        rejected++;
        u = raw.nextLong() >>> 1;
      }
      assertThat(bounded.belowLong(big)).as("draw %d", i).isEqualTo(u);
    }
    assertThat(rejected).isBetween(16, 200);
  }

  /** A bound that divides 2^63 has no partial block: no draw is ever rejected, the top half included. */
  @Test
  void aPowerOfTwoBoundRejectsNothing() {
    long half = 1L << 62;
    SplitMix64 bounded = new SplitMix64(13);
    SplitMix64 raw = new SplitMix64(13);
    for (int i = 0; i < 64; i++) assertThat(bounded.belowLong(half)).as("draw %d", i).isEqualTo((raw.nextLong() >>> 1) % half);
  }

  @Test
  void betweenIsInclusive() {
    SplitMix64 r = new SplitMix64(5);
    boolean low = false;
    boolean high = false;
    for (int i = 0; i < 1000; i++) {
      int v = r.between(9, 11);
      assertThat(v).isBetween(9, 11);
      low |= v == 9;
      high |= v == 11;
    }
    assertThat(low && high).isTrue();
  }

  @Test
  void aBoundBelowOneIsRefused() {
    assertThatThrownBy(() -> new SplitMix64(0).below(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SplitMix64(0).belowLong(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
