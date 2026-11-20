/*
 * Translated from Arm's optimized-routines (https://github.com/ARM-software/optimized-routines), revision
 * 20859d3fd9e797dde592c489c8ef7d383f778620: erf in math/erf.c, with the coefficients it reads from math/erf_data.c.
 *
 * math/erf.c:
 * Double-precision erf(x) function.
 *
 * Copyright (c) 2020-2026, Arm Limited.
 * SPDX-License-Identifier: MIT OR Apache-2.0 WITH LLVM-exception
 *
 * math/erf_data.c:
 * Shared data between erf and erfc.
 *
 * Copyright (c) 2019-2026, Arm Limited.
 * SPDX-License-Identifier: MIT OR Apache-2.0 WITH LLVM-exception
 *
 * Used under the MIT License; its full text ships in the jq-core jar as META-INF/LICENSE-arm-optimized-routines.
 */
package dev.rail8.jq.internal.op;

/**
 * {@code erf}, the error function, as Arm's optimized-routines compute it: a polynomial or rational approximation on
 * each of seven intervals of |x|, odd in x, ±1 from |x| = 5.90625 on. Its highest measured error, by Arm, is 1.01
 * ulp. {@code Math.fma} is C's {@code fma}: exact, rounded once.
 */
final class Erf {
  /** 2/sqrt(pi) - 1. */
  private static final double TWO_OVER_SQRT_PI_MINUS_ONE = 0x1.06eba8214db69p-3;

  /** erf(1) rounded to single precision. */
  private static final double C = 0x1.b0ac16p-1;

  /** The polynomial for |x| below 0.5. */
  private static final double[] PA = {
    0x1.06eba8214db68p-3, -0x1.812746b037948p-2, 0x1.ce2f21a03872p-4,
    -0x1.b82ce30e6548p-6, 0x1.565bcc360a2f2p-8, -0x1.c02d812bc979ap-11,
    0x1.f99bddfc1ebe9p-14, -0x1.f42c457cee912p-17, 0x1.b0e414ec20ee9p-20,
    -0x1.18c47fd143c5ep-23,
  };

  /** The numerator of the rational approximation on [0x1p-28, 0.84375]. */
  private static final double[] NA = {
    0x1.06eba8214db68p-3, -0x1.4cd7d691cb913p-2, -0x1.d2a51dbd7194fp-6,
    -0x1.7a291236668e4p-8, -0x1.8ead6120016acp-16,
  };

  /** The denominator of the rational approximation on [0x1p-28, 0.84375]. */
  private static final double[] DA = {
    0x1.97779cddadc09p-2, 0x1.0a54c5536cebap-4, 0x1.4d022c4d36b0fp-8,
    0x1.15dc9221c1a1p-13, -0x1.09c4342a2612p-18,
  };

  /** The numerator of the rational approximation on [0.84375, 1.25]. */
  private static final double[] NB = {
    -0x1.359b8bef77538p-9, 0x1.a8d00ad92b34dp-2, -0x1.7d240fbb8c3f1p-2,
    0x1.45fca805120e4p-2, -0x1.c63983d3e28ecp-4, 0x1.22a36599795ebp-5,
    -0x1.1bf380a96073fp-9,
  };

  /** The denominator of the rational approximation on [0.84375, 1.25]. */
  private static final double[] DB = {
    0x1.b3e6618eee323p-4, 0x1.14af092eb6f33p-1, 0x1.2635cd99fe9a7p-4,
    0x1.02660e763351fp-3, 0x1.bedc26b51dd1cp-7, 0x1.88b545735151dp-7,
  };

  /** The polynomial of erfc on [1.25, 2], in |x| - 1.25. */
  private static final double[] PC = {
    0x1.3bcd133aa0ffcp-4, -0x1.e4652fadcb702p-3, 0x1.2ebf3dcca0446p-2,
    -0x1.571d01c62d66p-3, 0x1.93a9a8f5b3413p-8, 0x1.8281cbcc2cd52p-5,
    -0x1.5cffd86b4de16p-6, -0x1.db4ccf595053ep-9, 0x1.757cbf8684edap-8,
    -0x1.ce7dfd2a9e56ap-11, -0x1.99ee3bc5a3263p-11, 0x1.3c57cf9213f5fp-12,
    0x1.60692996bf254p-14, -0x1.6e44cb7c1fa2ap-14, 0x1.9d4484ac482b2p-16,
    -0x1.578c9e375d37p-19,
  };

  /** The polynomial of erfc on [2, 3.25], in |x| / 2 - 1. */
  private static final double[] PD = {
    0x1.328f5ec350e5p-8, -0x1.529b9e8cf8e99p-5, 0x1.529b9e8cd9e71p-3,
    -0x1.8b0ae3a023bf2p-2, 0x1.1a2c592599d82p-1, -0x1.ace732477e494p-2,
    -0x1.e1a06a27920ffp-6, 0x1.bae92a6d27af6p-2, -0x1.a15470fcf5ce7p-2,
    0x1.bafe45d18e213p-6, 0x1.0d950680d199ap-2, -0x1.8c9481e8f22e3p-3,
    -0x1.158450ed5c899p-4, 0x1.c01f2973b44p-3, -0x1.73ed2827546a7p-3,
    0x1.47733687d1ff7p-4, -0x1.2dec70d00b8e1p-6, 0x1.a947ab83cd4fp-10,
  };

  /** The polynomial of erfc on [3.25, 4], in |x| - 3.25. */
  private static final double[] PE = {
    0x1.20c13035539e4p-18, -0x1.e9b5e8d16df7ep-16, 0x1.8de3cd4733bf9p-14,
    -0x1.9aa48beb8382fp-13, 0x1.2c7d713370a9fp-12, -0x1.490b12110b9e2p-12,
    0x1.1459c5d989d23p-12, -0x1.64b28e9f1269p-13, 0x1.57c76d9d05cf8p-14,
    -0x1.bf271d9951cf8p-16, 0x1.db7ea4d4535c9p-19, 0x1.91c2e102d5e49p-20,
    -0x1.e9f0826c2149ep-21, 0x1.60eebaea236e1p-23,
  };

  /** The polynomial of erfc on [4, 5.90625], in |x| / 2 - 2. */
  private static final double[] PF = {
    0x1.08ddd130d1fa6p-26, -0x1.10b146f59ff06p-22, 0x1.10b135328b7b2p-19,
    -0x1.6039988e7575fp-17, 0x1.497d365e19367p-15, -0x1.da48d9afac83ep-14,
    0x1.1024c9b1fbb48p-12, -0x1.fc962e7066272p-12, 0x1.87297282d4651p-11,
    -0x1.f057b255f8c59p-11, 0x1.0228d0eee063p-10, -0x1.b1b21b84ec41cp-11,
    0x1.1ead8ae9e1253p-11, -0x1.1e708fba37fccp-12, 0x1.9559363991edap-14,
    -0x1.68c827b783d9cp-16, 0x1.2ec4adeccf4a2p-19,
  };

  private Erf() {}

  /** erf(x): {@code x} itself for NaN, ±1 for ±inf. */
  static double erf(double x) {
    long bits = Double.doubleToRawLongBits(x);
    int ix = (int) (bits >>> 32);
    int ia = ix & 0x7fffffff;
    boolean negative = ix < 0;

    if (ia < 0x3feb0000) {
      // |x| < 0.84375.
      if (ia < 0x3e300000) {
        // |x| < 2^-28.
        if (ia < 0x00800000) {
          // |x| < 2^-1015.
          return Math.fma(TWO_OVER_SQRT_PI_MINUS_ONE, x, x);
        }
        return x + TWO_OVER_SQRT_PI_MINUS_ONE * x;
      }
      double x2 = x * x;
      if (ia < 0x3fe00000) {
        // |x| < 0.5: the polynomial.
        double r1 = Math.fma(x2, PA[1], PA[0]);
        double r2 = Math.fma(x2, PA[3], PA[2]);
        double r3 = Math.fma(x2, PA[5], PA[4]);
        double r4 = Math.fma(x2, PA[7], PA[6]);
        double r5 = Math.fma(x2, PA[9], PA[8]);
        double x4 = x2 * x2;
        double r = r5;
        r = Math.fma(x4, r, r4);
        r = Math.fma(x4, r, r3);
        r = Math.fma(x4, r, r2);
        r = Math.fma(x4, r, r1);
        // Arm: this fma is crucial for accuracy.
        return Math.fma(r, x, x);
      }
      // 0.5 <= |x| < 0.84375: the rational approximation.
      double r1n = Math.fma(x2, NA[1], NA[0]);
      double x4 = x2 * x2;
      double r2n = Math.fma(x2, NA[3], NA[2]);
      double x8 = x4 * x4;
      double r1d = Math.fma(x2, DA[0], 1.0);
      double r2d = Math.fma(x2, DA[2], DA[1]);
      double r3d = Math.fma(x2, DA[4], DA[3]);
      double p = r1n + x4 * r2n + x8 * NA[4];
      double q = r1d + x4 * r2d + x8 * r3d;
      return Math.fma(p / q, x, x);
    }
    if (ia < 0x3ff40000) {
      // 0.84375 <= |x| < 1.25.
      double a = Math.abs(x) - 1.0;
      double r1n = Math.fma(a, NB[1], NB[0]);
      double a2 = a * a;
      double r1d = Math.fma(a, DB[0], 1.0);
      double a4 = a2 * a2;
      double r2n = Math.fma(a, NB[3], NB[2]);
      double a6 = a4 * a2;
      double r2d = Math.fma(a, DB[2], DB[1]);
      double r3n = Math.fma(a, NB[5], NB[4]);
      double r3d = Math.fma(a, DB[4], DB[3]);
      double r4n = NB[6];
      double r4d = DB[5];
      double p = r1n + a2 * r2n + a4 * r3n + a6 * r4n;
      double q = r1d + a2 * r2d + a4 * r3d + a6 * r4d;
      return negative ? -C - p / q : C + p / q;
    }
    if (ia < 0x40000000) {
      // 1.25 <= |x| < 2.0.
      double a = Math.abs(x) - 1.25;
      return oneMinus(evenOdd(PC, a), negative);
    }
    if (ia < 0x400a0000) {
      // 2 <= |x| < 3.25.
      double a = Math.fma(0.5, Math.abs(x), -1.0);
      return oneMinus(evenOdd(PD, a), negative);
    }
    if (ia < 0x40100000) {
      // 3.25 <= |x| < 4.0.
      double a = Math.abs(x) - 3.25;
      return oneMinus(evenOdd(PE, a), negative);
    }
    if (ia < 0x4017a000) {
      // 4 <= |x| < 5.90625.
      double a = Math.fma(0.5, Math.abs(x), -2.0);
      return oneMinus(evenOdd(PF, a), negative);
    }
    if (ia >= 0x7ff00000) {
      // erf(nan) = nan, erf(+inf) = +1, erf(-inf) = -1.
      return (negative ? -1.0 : 1.0) + 1.0 / x;
    }
    return negative ? -1.0 : 1.0;
  }

  /**
   * The polynomial {@code p} at {@code a} as Arm evaluates it: its coefficients paired as {@code fma(a, p[2i+1],
   * p[2i])}, a lone last one as it is, then Horner's scheme in {@code a * a} from the last pair down.
   */
  private static double evenOdd(double[] p, double a) {
    int pairs = p.length / 2;
    double a2 = a * a;
    double r = p.length % 2 == 1 ? p[p.length - 1] : Math.fma(a, p[p.length - 1], p[p.length - 2]);
    for (int i = p.length % 2 == 1 ? pairs - 1 : pairs - 2; i >= 0; i--) {
      r = Math.fma(a2, r, Math.fma(a, p[2 * i + 1], p[2 * i]));
    }
    return r;
  }

  /** {@code 1 - r}, or {@code -1 + r} for a negative {@code x}. */
  private static double oneMinus(double r, boolean negative) {
    return negative ? -1.0 + r : 1.0 - r;
  }
}
