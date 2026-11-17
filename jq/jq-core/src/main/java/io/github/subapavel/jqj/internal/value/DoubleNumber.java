package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.value.JqNumber;
import java.math.BigDecimal;

/**
 * A number arithmetic computed (ADR-0003, ADR-0012; GLOSSARY "Computed number"): a double, which never becomes literal
 * text, printed in jq's {@code jvp_dtoa_fmt} layout. Public only so the kind can permit it.
 */
public final class DoubleNumber implements JqNumber {
  /** What NaN prints: jq prints it as null (ADR-0012). */
  private static final String NAN = "null";

  /** What an infinity prints after its sign: DBL_MAX (ADR-0012). */
  private static final String INFINITY = "1.7976931348623157e+308";

  /** The most digits before the point the plain form shows beyond the shortest digits ({@code jvp_dtoa_fmt}). */
  private static final int PLAIN_ZEROS = 15;

  private final double value;

  public DoubleNumber(double value) {
    this.value = value;
  }

  /** jq's text: NaN as null, an infinity as ±DBL_MAX, any other value in {@link #layout}. */
  @Override
  public String toJson() {
    if (Double.isNaN(value)) {
      return NAN;
    }
    String sign = Double.doubleToRawLongBits(value) < 0 ? "-" : "";
    return sign + (Double.isInfinite(value) ? INFINITY : layout(Math.abs(value)));
  }

  @Override
  public double doubleValue() {
    return value;
  }

  /** An integer in the long range; -0, NaN and the infinities are not. */
  @Override
  public boolean fitsLong() {
    // The cast saturates, and 2^63 is the one double it maps to a long that converts back to it.
    return value == (double) (long) value && value != 0x1p63 && Double.doubleToRawLongBits(value) != Long.MIN_VALUE;
  }

  @Override
  public long longValue() {
    if (!fitsLong()) {
      throw new ArithmeticException("the number is not an integer that a long holds");
    }
    return (long) value;
  }

  /** ADR-0028: the value of its printed digits; NaN and the infinities throw {@code ArithmeticException}. */
  @Override
  public BigDecimal decimalValue() {
    if (!Double.isFinite(value)) {
      throw new ArithmeticException("NaN and the infinities have no decimal value");
    }
    return new BigDecimal(toJson());
  }

  /**
   * jq's {@code jvp_dtoa_fmt} of a finite {@code d >= 0} (ADR-0012): the shortest digits, as {@link #digits} reads
   * them from {@code Double.toString}, in exponent form when the point lies before the fourth place after it or more
   * than 15 places past the last digit, otherwise plain.
   */
  private static String layout(double d) {
    if (d == 0) {
      return "0";
    }
    StringBuilder digits = new StringBuilder(24);
    int point = digits(Double.toString(d), digits);
    // Java gives a subnormal two digits where one suffices (4.9E-324): jq's shortest is the next one-digit decimal when
    // that reads back as d (5e-324); only a 9 rounds so, so the digit below is never the shorter one (#65 probe).
    if (d < Double.MIN_NORMAL && digits.length() == 2) {
      StringBuilder shorter = new StringBuilder(2);
      String text = (digits.charAt(0) - '0' + 1) + ".0E" + (point - 1);
      int shorterPoint = digits(text, shorter);
      if (Double.parseDouble(text) == d) {
        digits = shorter;
        point = shorterPoint;
      }
    }
    int n = digits.length();
    StringBuilder out = new StringBuilder(n + 8);
    if (point <= -4 || point > n + PLAIN_ZEROS) {
      out.append(digits.charAt(0));
      if (n > 1) {
        out.append('.').append(digits, 1, n);
      }
      int exponent = point - 1;
      out.append(exponent < 0 ? "e-" : "e+");
      if (Math.abs(exponent) < 10) {
        out.append('0');
      }
      return out.append(Math.abs(exponent)).toString();
    }
    if (point <= 0) {
      return out.append("0.").repeat('0', -point).append(digits).toString();
    }
    if (point >= n) {
      return out.append(digits).repeat('0', point - n).toString();
    }
    return out.append(digits, 0, point).append('.').append(digits, point, n).toString();
  }

  /**
   * The significant digits of the decimal {@code text} of a positive number ({@code Double.toString}'s form, with a
   * point and an optional {@code E} exponent) without leading or trailing zeros, into {@code digits}; returns where the
   * point lies after them: the value is {@code 0.digits} times ten to it.
   */
  private static int digits(String text, StringBuilder digits) {
    int e = text.indexOf('E');
    int end = e < 0 ? text.length() : e;
    int dot = text.indexOf('.');
    int point = dot + (e < 0 ? 0 : Integer.parseInt(text, e + 1, text.length(), 10));
    for (int i = 0; i < end; i++) {
      char c = text.charAt(i);
      if (c != '.') {
        digits.append(c);
      }
    }
    int lead = 0;
    while (digits.charAt(lead) == '0') {
      lead++;
    }
    int last = digits.length();
    while (digits.charAt(last - 1) == '0') {
      last--;
    }
    digits.setLength(last);
    digits.delete(0, lead);
    return point - lead;
  }
}
