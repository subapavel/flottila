package io.flottila.mapping.xdm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one number rule of the standard functions (#82, ADR-0005): every function that reads a number
 * reads it through {@link #parse}, as the export reads it through {@code po:decimal} in {@code
 * core.xsl}, and writes it through {@link #canonical}. A function that meets no decimal raises its
 * own data error at its box (F-19). A quotient is {@link #quotient}'s, and XPath's {@code round} is
 * {@link #round}.
 */
public final class XsDecimal {

  /** The {@code xs:decimal} lexical form, XML whitespace on either side; group 1 is the number. */
  private static final Pattern LEXICAL =
      Pattern.compile("[ \t\r\n]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))[ \t\r\n]*");

  /** The fractional digits of a quotient, at least, and beyond the dividend's own (ADR-0005). */
  private static final int DIVISION_SCALE = 18;

  private static final BigDecimal HALF = new BigDecimal("0.5");

  private XsDecimal() {}

  /**
   * {@code text} as a decimal, its scale kept: the {@code xs:decimal} lexical form with XML
   * whitespace (space, tab, line feed, return) around it ignored, as the export's {@code
   * xs:decimal} cast reads it. Empty for anything else: an exponent, {@code NaN}, a sign or a point
   * alone, {@code ""}, other whitespace or other digits.
   */
  public static Optional<BigDecimal> parse(String text) {
    Matcher m = LEXICAL.matcher(text);
    return m.matches() ? Optional.of(new BigDecimal(m.group(1))) : Optional.empty();
  }

  /**
   * The XPath canonical form of {@code value}: no exponent, no trailing zero, {@code 0} for every
   * zero.
   */
  public static String canonical(BigDecimal value) {
    // since JDK 8 a zero of any scale strips to BigDecimal.ZERO, which prints as 0
    return value.stripTrailingZeros().toPlainString();
  }

  /**
   * {@code dividend / divisor} by ADR-0005 as amended by F-20, as Saxon-HE 12.5 divides: every
   * quotient, one that terminates too, rounded half-down to {@code max(18, scale of dividend -
   * scale of divisor + 18)} fractional digits, each scale without trailing zeros and never below 0.
   *
   * @throws ArithmeticException when {@code divisor} is zero; a function raises its own data error
   *     before
   */
  public static BigDecimal quotient(BigDecimal dividend, BigDecimal divisor) {
    int scale = Math.max(DIVISION_SCALE, scale(dividend) - scale(divisor) + DIVISION_SCALE);
    return dividend.divide(divisor, scale, RoundingMode.HALF_DOWN);
  }

  /** XPath's {@code round}: the nearest whole number, a half toward positive infinity. */
  public static BigDecimal round(BigDecimal value) {
    return value.add(HALF).setScale(0, RoundingMode.FLOOR);
  }

  /** The fractional digits of {@code value} without its trailing zeros, never below 0. */
  private static int scale(BigDecimal value) {
    return Math.max(0, value.stripTrailingZeros().scale());
  }
}
