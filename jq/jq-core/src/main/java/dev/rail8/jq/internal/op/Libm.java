package dev.rail8.jq.internal.op;

/**
 * The C library functions behind jq 1.8.2's math builtins that Java has no exact counterpart for, as jq-ref 1.8.2
 * gives them on x86-64.
 *
 * <p>The exact ones follow the C standard and POSIX, and match jq-ref bit for bit. That includes the sign of a NaN,
 * which {@code copysign} can observe: a NaN argument is passed on as it is, and a NaN the function makes itself is
 * {@link #INVALID}, the NaN that x86-64 makes for an invalid operation. When two arguments are NaN, the one passed on
 * is the one jq-ref passes on. The conversions of an exponent or an order to {@code int} or {@code long} are x86-64's:
 * NaN and any value whose truncation does not fit become that type's minimum value.
 *
 * <p>{@link #acosh}, {@link #asinh}, {@link #atanh}, {@link #exp2}, {@link #exp10} and {@link #log2} are computed from
 * their definitions over {@code StrictMath}. jq-ref's results for them come from glibc and vary with the CPU, so their
 * last digits are this engine's own. They are exact where jq-ref is: on powers of two and of ten, and at zero, one
 * and infinity.
 */
final class Libm {
  /** The NaN x86-64 makes for an invalid operation, such as {@code 0 * inf}: quiet, with its sign bit set. */
  static final double INVALID = Double.longBitsToDouble(0xfff8_0000_0000_0000L);

  /** ln 2, rounded to the nearest double. */
  private static final double LN2 = 0x1.62e42fefa39efp-1;

  /** 2^28: from here on, a term of 1 next to x squared no longer changes a double. */
  private static final double TWO28 = 0x1p28;

  /** 2^-28: below this, asinh and atanh equal their argument to within half an ulp. */
  private static final double TWO_M28 = 0x1p-28;

  /** 2^54: scales a subnormal into the normal range. */
  private static final double TWO54 = 0x1p54;

  private Libm() {}

  /** C's {@code round}: to the nearest integer, a half away from zero. */
  static double round(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x) || Math.abs(x) >= 0x1p52) {
      return x;
    }
    double t = trunc(x);
    return Math.abs(x - t) >= 0.5 ? t + Math.copySign(1.0, x) : t;
  }

  /** C's {@code trunc}: toward zero, keeping the sign of a zero. */
  static double trunc(double x) {
    if (Double.isNaN(x)) {
      return x;
    }
    return x < 0 ? Math.ceil(x) : Math.floor(x);
  }

  /** C's {@code floor}. */
  static double floor(double x) {
    return Double.isNaN(x) ? x : Math.floor(x);
  }

  /** C's {@code ceil}. */
  static double ceil(double x) {
    return Double.isNaN(x) ? x : Math.ceil(x);
  }

  /** C's {@code rint} and {@code nearbyint} in the default rounding mode: to the nearest integer, a half to even. */
  static double rint(double x) {
    return Double.isNaN(x) ? x : Math.rint(x);
  }

  /** C's {@code sqrt}: {@link #INVALID} below zero, {@code -0} for {@code -0}. */
  static double sqrt(double x) {
    if (Double.isNaN(x)) {
      return x;
    }
    return x < 0 ? INVALID : Math.sqrt(x);
  }

  /**
   * C's {@code fmod}: the remainder of the division truncated toward zero, with the sign of {@code x}; {@link
   * #INVALID} when {@code y} is zero or {@code x} infinite.
   */
  static double fmod(double x, double y) {
    if (Double.isNaN(x) || Double.isNaN(y)) {
      return x87Nan(x, y);
    }
    return y == 0 || Double.isInfinite(x) ? INVALID : x % y;
  }

  /**
   * C's {@code remainder}: the remainder of the division rounded to the nearest integer, a half to even; {@link
   * #INVALID} when {@code y} is zero or {@code x} infinite.
   */
  static double remainder(double x, double y) {
    if (Double.isNaN(x) || Double.isNaN(y)) {
      return x87Nan(x, y);
    }
    return y == 0 || Double.isInfinite(x) ? INVALID : Math.IEEEremainder(x, y);
  }

  /** glibc's {@code drem}: {@link #remainder}, but {@code y} when both are NaN. */
  static double drem(double x, double y) {
    return Double.isNaN(x) && Double.isNaN(y) ? y : remainder(x, y);
  }

  /**
   * The NaN that glibc's x87 code for {@code fmod}, {@code remainder} and {@code nexttoward} passes on when {@code x}
   * or {@code y} is NaN: the one that is NaN, and when both are, the positive one if either is.
   */
  private static double x87Nan(double x, double y) {
    if (!Double.isNaN(x)) {
      return y;
    }
    if (!Double.isNaN(y)) {
      return x;
    }
    return Double.doubleToRawLongBits(x) < 0 ? y : x;
  }

  /** C's {@code fmin}: the smaller, {@code x} when they are equal, the other one when one is NaN, {@code y} when both are. */
  static double fmin(double x, double y) {
    if (Double.isNaN(x)) {
      return y;
    }
    if (Double.isNaN(y)) {
      return x;
    }
    return y < x ? y : x;
  }

  /** C's {@code fmax}: the larger, {@code x} when they are equal, the other one when one is NaN, {@code y} when both are. */
  static double fmax(double x, double y) {
    if (Double.isNaN(x)) {
      return y;
    }
    if (Double.isNaN(y)) {
      return x;
    }
    return y > x ? y : x;
  }

  /** C's {@code fdim}: {@code x - y} when {@code x} is the larger, else {@code +0}; a NaN, {@code x}'s first. */
  static double fdim(double x, double y) {
    if (Double.isNaN(x)) {
      return x;
    }
    if (Double.isNaN(y)) {
      return y;
    }
    return x <= y ? 0.0 : x - y;
  }

  /**
   * C's {@code fma}: {@code x * y + z} rounded once; the first NaN of {@code x}, {@code y} and {@code z}, else {@link
   * #INVALID} for an invalid product or sum.
   */
  static double fma(double x, double y, double z) {
    if (Double.isNaN(x)) {
      return x;
    }
    if (Double.isNaN(y)) {
      return y;
    }
    if (Double.isNaN(z)) {
      return z;
    }
    double r = Math.fma(x, y, z);
    return Double.isNaN(r) ? INVALID : r;
  }

  /** C's {@code nextafter}: the next double after {@code x} toward {@code y}, {@code y} when they are equal; {@code y} when it is NaN. */
  static double nextafter(double x, double y) {
    if (Double.isNaN(y)) {
      return y;
    }
    if (Double.isNaN(x)) {
      return x;
    }
    return Math.nextAfter(x, y);
  }

  /**
   * C's {@code nexttoward} with a direction that is a double: {@link #nextafter}'s result, but glibc's x87 code picks
   * between two NaNs.
   */
  static double nexttoward(double x, double y) {
    if (Double.isNaN(x) || Double.isNaN(y)) {
      return x87Nan(x, y);
    }
    return Math.nextAfter(x, y);
  }

  /** C's {@code copysign}: {@code x}'s magnitude with {@code y}'s sign bit, a NaN's included. */
  static double copysign(double x, double y) {
    return Math.copySign(x, y);
  }

  /** C's {@code ldexp}: {@code x} times 2 to the power of {@code e} converted to {@code int}, rounded once. */
  static double ldexp(double x, double e) {
    return Double.isNaN(x) ? x : Math.scalb(x, toInt(e));
  }

  /** C's {@code scalbln}: {@code x} times 2 to the power of {@code e} converted to {@code long}, rounded once. */
  static double scalbln(double x, double e) {
    if (Double.isNaN(x)) {
      return x;
    }
    return Math.scalb(x, Math.clamp(toLong(e), Integer.MIN_VALUE, Integer.MAX_VALUE));
  }

  /**
   * POSIX's {@code scalb}: {@code x} times 2 to the power of {@code fn}, an integer-valued double. {@link #INVALID}
   * when {@code fn} has a fraction, for 0 times 2^inf and for inf times 2^-inf. An {@code fn} beyond the {@code int}
   * range overflows or underflows as the largest {@code int} of its sign does.
   */
  static double scalb(double x, double fn) {
    if (Double.isNaN(x)) {
      return x;
    }
    if (Double.isNaN(fn)) {
      return fn;
    }
    if (Double.isInfinite(fn)) {
      double r = fn > 0 ? x * fn : x == 0 ? x : x / -fn;
      return Double.isNaN(r) ? INVALID : r;
    }
    if (Math.rint(fn) != fn) {
      return INVALID;
    }
    int n = fn >= 0x1p31 ? Integer.MAX_VALUE : fn < -0x1p31 ? Integer.MIN_VALUE : (int) fn;
    return Math.scalb(x, n);
  }

  /** C's {@code logb}: the binary exponent as a double, subnormals included; {@code -inf} for zero, {@code +inf} for an infinity. */
  static double logb(double x) {
    if (Double.isNaN(x)) {
      return x;
    }
    if (Double.isInfinite(x)) {
      return Double.POSITIVE_INFINITY;
    }
    return x == 0 ? Double.NEGATIVE_INFINITY : exponent(x);
  }

  /** glibc's {@code significand}: {@code x} scaled into [1, 2) by its binary exponent; a zero, an infinity and NaN as they are. */
  static double significand(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x) || x == 0) {
      return x;
    }
    return Math.scalb(x, -exponent(x));
  }

  /**
   * C's {@code frexp}'s mantissa and exponent of {@code x}: {@code x = m * 2^e} with {@code m} in [0.5, 1); a zero, an
   * infinity and NaN give themselves and 0.
   */
  static double[] frexp(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x) || x == 0) {
      return new double[] {x, 0};
    }
    int e = exponent(x) + 1;
    return new double[] {Math.scalb(x, -e), e};
  }

  /** C's {@code modf}'s fraction and integer part of {@code x}, both with {@code x}'s sign; an infinity's fraction is a zero. */
  static double[] modf(double x) {
    if (Double.isNaN(x)) {
      return new double[] {x, x};
    }
    if (Double.isInfinite(x)) {
      return new double[] {Math.copySign(0.0, x), x};
    }
    double whole = trunc(x);
    return new double[] {Math.copySign(x - whole, x), whole};
  }

  /** The binary exponent of a finite non-zero {@code x}, as {@code ilogb} gives it for a subnormal too. */
  private static int exponent(double x) {
    int e = Math.getExponent(x);
    return e >= Double.MIN_EXPONENT ? e : Math.getExponent(x * TWO54) - 54;
  }

  /** x86-64's conversion to {@code int}: truncation, and {@code Integer.MIN_VALUE} for NaN and out of range. */
  static int toInt(double d) {
    return d > -0x1p31 - 1 && d < 0x1p31 ? (int) d : Integer.MIN_VALUE;
  }

  /** x86-64's conversion to {@code long}: truncation, and {@code Long.MIN_VALUE} for NaN and out of range. */
  static long toLong(double d) {
    return d >= -0x1p63 && d < 0x1p63 ? (long) d : Long.MIN_VALUE;
  }

  /** {@code log2}: the binary exponent plus the base-2 logarithm of the mantissa, taken near 1, so a power of two is exact. */
  static double log2(double x) {
    if (Double.isNaN(x)) {
      return x;
    }
    if (x < 0) {
      return INVALID;
    }
    if (x == 0) {
      return Double.NEGATIVE_INFINITY;
    }
    if (Double.isInfinite(x)) {
      return x;
    }
    int e = exponent(x);
    double m = Math.scalb(x, -e);
    if (m > Math.sqrt(2)) {
      m /= 2;
      e++;
    }
    return e + StrictMath.log(m) / LN2;
  }

  /**
   * C's {@code pow}: {@code StrictMath.pow}, but 1 for a base of 1 whatever the exponent, NaN included, and for a base
   * of -1 with an infinite exponent, where Java gives NaN.
   */
  static double pow(double x, double y) {
    return x == 1 || (x == -1 && Double.isInfinite(y)) ? 1 : StrictMath.pow(x, y);
  }

  /** {@code exp2}: {@code StrictMath.pow(2, x)}, exact on integers. */
  static double exp2(double x) {
    return StrictMath.pow(2, x);
  }

  /** {@code exp10}: {@code StrictMath.pow(10, x)}, exact on integers whose power of ten a double holds. */
  static double exp10(double x) {
    return StrictMath.pow(10, x);
  }

  /**
   * {@code acosh}: ln(x + sqrt(x^2 - 1)), for x of at least 1, else {@link #INVALID}. Near 1 through {@code log1p} of
   * {@code x - 1}; past 2^28 as ln(x) + ln(2), where the square root is x.
   */
  static double acosh(double x) {
    if (Double.isNaN(x)) {
      return x;
    }
    if (x < 1) {
      return INVALID;
    }
    if (x >= TWO28) {
      return Double.isInfinite(x) ? x : StrictMath.log(x) + LN2;
    }
    if (x > 2) {
      return StrictMath.log(2 * x - 1 / (x + Math.sqrt(x * x - 1)));
    }
    double t = x - 1;
    return StrictMath.log1p(t + Math.sqrt(2 * t + t * t));
  }

  /**
   * {@code asinh}: sign(x) ln(|x| + sqrt(x^2 + 1)), odd, so {@code -0} stays {@code -0}. Below 2^-28 it is x; up to 2
   * through {@code log1p}; past 2^28 as ln|x| + ln(2).
   */
  static double asinh(double x) {
    double a = Math.abs(x);
    if (Double.isNaN(x) || Double.isInfinite(x) || a < TWO_M28) {
      return x;
    }
    double r;
    if (a > TWO28) {
      r = StrictMath.log(a) + LN2;
    } else if (a > 2) {
      r = StrictMath.log(2 * a + 1 / (Math.sqrt(a * a + 1) + a));
    } else {
      double s = a * a;
      r = StrictMath.log1p(a + s / (1 + Math.sqrt(1 + s)));
    }
    return Math.copySign(r, x);
  }

  /**
   * {@code atanh}: sign(x) ln((1 + |x|) / (1 - |x|)) / 2, as {@code log1p} of {@code 2|x| / (1 - |x|)}; infinite at
   * 1, {@link #INVALID} beyond it. Odd, so {@code -0} stays {@code -0}.
   */
  static double atanh(double x) {
    double a = Math.abs(x);
    if (Double.isNaN(x) || a < TWO_M28) {
      return x;
    }
    if (a > 1) {
      return INVALID;
    }
    if (a == 1) {
      return Math.copySign(Double.POSITIVE_INFINITY, x);
    }
    double r;
    if (a < 0.5) {
      double t = a + a;
      r = 0.5 * StrictMath.log1p(t + t * a / (1 - a));
    } else {
      r = 0.5 * StrictMath.log1p((a + a) / (1 - a));
    }
    return Math.copySign(r, x);
  }
}
