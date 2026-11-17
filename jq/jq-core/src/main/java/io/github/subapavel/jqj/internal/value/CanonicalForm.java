package io.github.subapavel.jqj.internal.value;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * A literal number's canonical form and values (ADR-0012, ADR-0028; GLOSSARY "Canonical form", "Adjusted exponent",
 * "Exponent limits", "Infinity literal"), computed from its strict token. The token is read as a coefficient D, its
 * digits without leading zeros ("0" when all are zero), and the exponent e of D's last digit; the adjusted exponent is
 * {@code e + |D| - 1}. Inside the exponent limits the text is the General Decimal Arithmetic to-scientific-string of
 * D and e; the sign of a zero is kept. The exponent is a {@code long} that saturates, so no token wraps round.
 */
public final class CanonicalForm {
  /** Where the text goes: runs of the token's digits, and single characters. */
  public interface Out {
    void put(byte[] src, int off, int len);

    void put(char c);
  }

  /** The largest adjusted exponent of a finite literal; above it the literal is an Infinity literal. */
  private static final long EMAX = 999_999_999L;

  /** The exponent of the smallest literal quantum, 1E-1147483646: a zero's lowest exponent and the rounding unit. */
  private static final long ETINY = -1_147_483_646L;

  /** Where an exponent's magnitude stops growing: far past both limits, and far from a {@code long}'s overflow. */
  private static final long SATURATED = 1L << 40;

  /** What an Infinity literal prints, after its sign. */
  private static final byte[] INFINITY_TEXT = {
    '1', '.', '7', '9', '7', '6', '9', '3', '1', '3', '4', '8', '6', '2', '3', '1', '5', '7', 'e', '+', '3', '0', '8'
  };

  /** A double's exponent range lies well inside this adjusted exponent: past it the double is an infinity or a zero. */
  private static final long DOUBLE_RANGE = 400;

  /** The powers of ten a double holds exactly. */
  private static final double[] EXACT_POWERS = {
    1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19,
    1e20, 1e21, 1e22
  };

  private static final MathContext SEVENTEEN_DIGITS = new MathContext(17, RoundingMode.HALF_EVEN);

  private CanonicalForm() {}

  /** Writes the canonical form of the strict token {@code b[off, off + len)} into {@code out}. */
  public static void write(byte[] b, int off, int len, Out out) {
    Token t = new Token(b, off, len);
    if (t.negative) {
      out.put('-');
    }
    if (t.zero) {
      layout(t, Math.clamp(t.e, ETINY, EMAX), out);
    } else if (t.adjusted() > EMAX) {
      out.put(INFINITY_TEXT, 0, INFINITY_TEXT.length);
    } else if (t.e >= ETINY) {
      layout(t, t.e, out);
    } else {
      belowEtiny(t, out);
    }
  }

  /**
   * The double of the strict token {@code b[off, off + len)}: an Infinity literal is an infinity, any other literal its
   * value rounded HALF_EVEN to 17 significant digits, then correctly rounded (ADR-0012 "Values", ADR-0028).
   */
  public static double toDouble(byte[] b, int off, int len) {
    Token t = new Token(b, off, len);
    double magnitude;
    long adjusted = t.adjusted();
    int nd = t.digitCount();
    if (t.zero || adjusted < -DOUBLE_RANGE) {
      magnitude = 0.0;
    } else if (adjusted > DOUBLE_RANGE) {
      magnitude = Double.POSITIVE_INFINITY;
    } else if (nd <= 15 && t.e >= -22 && t.e <= 22) {
      // D below 10^15 and 10^|e| are exact doubles, so one IEEE operation rounds the value correctly.
      double d = t.coefficient();
      magnitude = t.e < 0 ? d / EXACT_POWERS[(int) -t.e] : d * EXACT_POWERS[(int) t.e];
    } else if (nd <= 17 && t.e == 0) {
      // An integer of at most 17 digits: a long, whose conversion rounds correctly.
      magnitude = t.coefficient();
    } else {
      // HALF_EVEN to 17 digits reads only D's first 18 digits and whether any digit after them is non-zero. So D is
      // cut to those 18, followed by 1 for a non-zero rest and 0 otherwise: the same rounding, and a BigDecimal of at
      // most 19 digits, where parsing all of a long D would take time quadratic in its length.
      int kept = Math.min(nd, 18);
      boolean rest = false;
      for (int i = kept; i < nd && !rest; i++) {
        rest = t.digit(i) != '0';
      }
      char[] digits = new char[kept + 1];
      for (int i = 0; i < kept; i++) {
        digits[i] = (char) t.digit(i);
      }
      digits[kept] = rest ? '1' : '0';
      // The last digit kept stands for 10^(e + nd - kept); |e| is at most DOUBLE_RANGE plus the token's length here.
      BigDecimal value = new BigDecimal(digits).scaleByPowerOfTen((int) (t.e + nd - kept - 1));
      magnitude = Double.parseDouble(value.round(SEVENTEEN_DIGITS).toString());
    }
    return t.negative ? -magnitude : magnitude;
  }

  /**
   * The decimal value of the strict token {@code b[off, off + len)}: its canonical form's value with that form's
   * scale, so after the exponent limits; a negative zero gives a zero (ADR-0028).
   *
   * @throws ArithmeticException for an Infinity literal, which has no decimal value
   */
  public static BigDecimal toDecimal(byte[] b, int off, int len) {
    Token t = new Token(b, off, len);
    if (!t.zero && t.adjusted() > EMAX) {
      throw new ArithmeticException("an Infinity literal has no decimal value");
    }
    StringBuilder text = new StringBuilder(len + 16);
    write(
        b,
        off,
        len,
        new Out() {
          @Override
          public void put(byte[] src, int from, int length) {
            for (int i = from; i < from + length; i++) {
              text.append((char) src[i]);
            }
          }

          @Override
          public void put(char c) {
            text.append(c);
          }
        });
    return new BigDecimal(text.toString());
  }

  /** Whether the strict token {@code b[off, off + len)} is a zero, of either sign and any exponent. */
  static boolean zero(byte[] b, int off, int len) {
    return new Token(b, off, len).zero;
  }

  /**
   * The order of two strict tokens' literals (ADR-0012 "Comparison"): their canonical forms' values, as
   * {@code BigDecimal.compareTo} orders them, so a negative zero equals zero and a value below the quantum is rounded
   * first; an Infinity literal lies past every finite literal of its sign, and two Infinity literals of one sign are
   * equal. Returns -1, 0 or 1. It reads each digit once, where a BigDecimal of a long coefficient takes quadratic time.
   */
  static int compare(byte[] a, int aoff, int alen, byte[] b, int boff, int blen) {
    Token x = rounded(a, aoff, alen);
    Token y = rounded(b, boff, blen);
    int rx = x.rank();
    int ry = y.rank();
    if (rx != ry || Math.abs(rx) != 1) {
      return Integer.compare(rx, ry);
    }
    return rx * magnitude(x, y);
  }

  /**
   * The token {@code b[off, off + len)}, or the token of its canonical form when its last digit lies below
   * 1E-1147483646, where the form rounds it (to a zero, possibly).
   */
  private static Token rounded(byte[] b, int off, int len) {
    Token t = new Token(b, off, len);
    if (t.zero || t.e >= ETINY) {
      return t;
    }
    Bytes text = new Bytes();
    write(b, off, len, text);
    return new Token(text.toByteArray(), 0, text.size());
  }

  /**
   * The order of the magnitudes of two non-zero finite tokens: by adjusted exponent, then digit by digit, the shorter
   * D followed by zeros.
   */
  private static int magnitude(Token x, Token y) {
    int byExponent = Long.compare(x.adjusted(), y.adjusted());
    if (byExponent != 0) {
      return byExponent;
    }
    int nx = x.digitCount();
    int ny = y.digitCount();
    for (int i = 0; i < Math.max(nx, ny); i++) {
      int dx = i < nx ? x.digit(i) : '0';
      int dy = i < ny ? y.digit(i) : '0';
      if (dx != dy) {
        return Integer.compare(dx, dy);
      }
    }
    return 0;
  }

  /** An {@link Out} that keeps the text's bytes. */
  private static final class Bytes extends ByteArrayOutputStream implements Out {
    @Override
    public void put(byte[] src, int off, int len) {
      write(src, off, len);
    }

    @Override
    public void put(char c) {
      write(c);
    }
  }

  /**
   * D with the exponent {@code e} of its last digit: plain when {@code e <= 0} and the adjusted exponent is at least
   * -6, in exponent form otherwise.
   */
  private static void layout(Token t, long e, Out out) {
    int nd = t.digitCount();
    long adjusted = e + nd - 1;
    if (e > 0 || adjusted < -6) {
      exponentForm(t, nd, (char) 0, 0, e, out);
    } else if (e == 0) {
      t.digits(0, nd, out);
    } else if (nd > -e) {
      int point = (int) (nd + e);
      t.digits(0, point, out);
      out.put('.');
      t.digits(point, nd, out);
    } else {
      out.put('0');
      out.put('.');
      for (long z = -e - nd; z > 0; z--) {
        out.put('0');
      }
      t.digits(0, nd, out);
    }
  }

  /**
   * A non-zero value whose last digit lies below 1E-1147483646: rounded HALF_UP to a multiple of it. The k digits of D
   * at or above the quantum are kept; the first digit dropped decides the rounding.
   */
  private static void belowEtiny(Token t, Out out) {
    long kept = t.digitCount() - (ETINY - t.e);
    if (kept < 0 || kept == 0 && t.digit(0) < '5') {
      out.put('0');
      exponent(ETINY, out);
      return;
    }
    int k = (int) kept;
    if (t.digit(k) < '5') {
      exponentForm(t, k, (char) 0, 0, ETINY, out);
      return;
    }
    // Round up: the last digit below 9 grows by one and the nines after it become zeros; all nines carry to a 1.
    int j = k - 1;
    while (j >= 0 && t.digit(j) == '9') {
      j--;
    }
    if (j < 0) {
      exponentForm(t, 0, '1', k, ETINY, out);
    } else {
      exponentForm(t, j, (char) (t.digit(j) + 1), k - 1 - j, ETINY, out);
    }
  }

  /**
   * Exponent form of the coefficient D[0, m), then the digit {@code last} unless it is 0, then {@code zeros} zeros,
   * whose last digit has the exponent {@code e}: its first digit, a point and the others when there are any, then
   * {@code E}, the adjusted exponent's sign and its magnitude.
   */
  private static void exponentForm(Token t, int m, char last, int zeros, long e, Out out) {
    int n = m + (last == 0 ? 0 : 1) + zeros;
    if (m > 0) {
      t.digits(0, 1, out);
      if (n > 1) {
        out.put('.');
      }
      t.digits(1, m, out);
      if (last != 0) {
        out.put(last);
      }
    } else {
      // m is 0 only after a carry, so the first digit is `last`.
      out.put(last);
      if (n > 1) {
        out.put('.');
      }
    }
    for (int z = zeros; z > 0; z--) {
      out.put('0');
    }
    exponent(e + n - 1, out);
  }

  /** {@code E}, the sign of {@code adjusted} and its decimal digits. */
  private static void exponent(long adjusted, Out out) {
    out.put('E');
    out.put(adjusted < 0 ? '-' : '+');
    long magnitude = Math.abs(adjusted);
    long power = 1;
    while (power <= magnitude / 10) {
      power *= 10;
    }
    for (; power > 0; power /= 10) {
      out.put((char) ('0' + magnitude / power % 10));
    }
  }

  /**
   * A strict token ({@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}) read as D and e. D is the bytes
   * {@code b[first, first + firstLength)} followed by {@code b[second, second + secondLength)}: the integer part and
   * the fraction, or the fraction from its first non-zero digit, or the integer part's single 0 for a zero.
   */
  private static final class Token {
    final byte[] b;
    final boolean negative;
    final boolean zero;

    /** The exponent of D's last digit, saturated. */
    final long e;

    final int first;
    final int firstLength;
    final int second;
    final int secondLength;

    Token(byte[] b, int off, int len) {
      this.b = b;
      int end = off + len;
      int p = off;
      negative = b[p] == '-';
      if (negative) {
        p++;
      }
      int integer = p;
      while (p < end && b[p] >= '0' && b[p] <= '9') {
        p++;
      }
      int integerEnd = p;
      int fraction = p;
      if (p < end && b[p] == '.') {
        fraction = ++p;
        while (p < end && b[p] >= '0' && b[p] <= '9') {
          p++;
        }
      }
      int fractionEnd = p;
      long exponent = 0;
      if (p < end) {
        // The e or E, then an optional sign.
        boolean negativeExponent = b[++p] == '-';
        if (negativeExponent || b[p] == '+') {
          p++;
        }
        for (; p < end; p++) {
          exponent = Math.min(exponent * 10 + (b[p] - '0'), SATURATED);
        }
        if (negativeExponent) {
          exponent = -exponent;
        }
      }
      e = exponent - (fractionEnd - fraction);
      if (b[integer] != '0') {
        zero = false;
        first = integer;
        firstLength = integerEnd - integer;
        second = fraction;
        secondLength = fractionEnd - fraction;
      } else {
        int nonZero = fraction;
        while (nonZero < fractionEnd && b[nonZero] == '0') {
          nonZero++;
        }
        zero = nonZero == fractionEnd;
        // A zero's D is the integer part's 0; otherwise D starts at the fraction's first non-zero digit.
        first = zero ? integer : nonZero;
        firstLength = zero ? 1 : fractionEnd - nonZero;
        second = fractionEnd;
        secondLength = 0;
      }
    }

    int digitCount() {
      return firstLength + secondLength;
    }

    long adjusted() {
      return e + digitCount() - 1;
    }

    /** Where the value lies among the five ranks: an Infinity literal ±2, a finite non-zero value ±1, a zero 0. */
    int rank() {
      if (zero) {
        return 0;
      }
      int rank = adjusted() > EMAX ? 2 : 1;
      return negative ? -rank : rank;
    }

    /** D's digit {@code i}, an ASCII byte. */
    int digit(int i) {
      return b[i < firstLength ? first + i : second + i - firstLength];
    }

    /** D[0, |D|) as a long; only called when |D| is at most 17. */
    long coefficient() {
      long value = 0;
      for (int i = 0; i < digitCount(); i++) {
        value = value * 10 + (digit(i) - '0');
      }
      return value;
    }

    /** Writes D[from, to) as at most two runs of the token's bytes. */
    void digits(int from, int to, Out out) {
      int split = Math.clamp(firstLength, from, to);
      out.put(b, first + from, split - from);
      out.put(b, second + split - firstLength, to - split);
    }
  }
}
