package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.US_ASCII;

import io.github.subapavel.jqj.value.JqNumber;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * A literal number written in a filter, a negated literal, or one that {@code tonumber} read (ADR-0012, ADR-0028,
 * ADR-0031; GLOSSARY "Literal number"): its canonical form, its double and whether it fits a long, all computed once,
 * when it is made. Public only so the kind can permit it.
 */
public final class LiteralNumber implements JqNumber, Literal {
  /** {@link Long#MAX_VALUE}'s digits: a 19-digit integer fits a long when it is at most these. */
  private static final String LONG_MAX = Long.toString(Long.MAX_VALUE);

  /** The digits of {@link Long#MIN_VALUE} without its sign: the most a negative 19-digit integer may have. */
  private static final String LONG_MIN = LONG_MAX.substring(0, 18) + "8";

  /** A short token's most bytes (ADR-0012 "Flags"). */
  private static final int SHORT = 15;

  /** The strict token of an Infinity literal, without its sign: an adjusted exponent above decNumber's limit. */
  private static final String INFINITY = "1e1000000000";

  /**
   * A strict token of ADR-0012's grammar: a normalised lexeme, which has no sign; the token of a negated literal, which
   * has one unless its value is a zero; or a normalised text of {@link #ofText}, which keeps its minus sign.
   */
  private final byte[] token;

  /** The canonical form's ASCII bytes. */
  private final byte[] canonical;

  private final double doubleValue;
  private final boolean fitsLong;

  /** {@link #isShort(byte[], byte[])} of the token, computed once. */
  private final boolean isShort;

  private LiteralNumber(String token) {
    this(token, fitsLong(token));
  }

  /** The literal of {@code token}, whose FITS_LONG flag (ADR-0012) is {@code fitsLong}. */
  private LiteralNumber(String token, boolean fitsLong) {
    this.token = token.getBytes(US_ASCII);
    StringBuilder text = new StringBuilder();
    CanonicalForm.write(
        this.token,
        0,
        this.token.length,
        new CanonicalForm.Out() {
          @Override
          public void put(byte[] src, int off, int len) {
            text.append(new String(src, off, len, US_ASCII));
          }

          @Override
          public void put(char c) {
            text.append(c);
          }
        });
    this.canonical = text.toString().getBytes(US_ASCII);
    this.doubleValue = CanonicalForm.toDouble(this.token, 0, this.token.length);
    this.fitsLong = fitsLong;
    this.isShort = isShort(this.token, this.canonical);
  }

  /**
   * ADR-0012 FITS_LONG (ADR-0031) of a filter's token: an integer token, never -0, in the long range. A sign is only
   * ever on a non-zero value. Two digit strings of the same length compare as their values.
   */
  private static boolean fitsLong(String token) {
    int sign = token.startsWith("-") ? 1 : 0;
    String digits = token.substring(sign);
    String limit = sign == 0 ? LONG_MAX : LONG_MIN;
    return digits.chars().allMatch(c -> c >= '0' && c <= '9')
        && (digits.length() < limit.length() || digits.length() == limit.length() && digits.compareTo(limit) <= 0);
  }

  /**
   * The literal of a document's number {@code view}, for a detach (ADR-0039): its token copied, so it outlives the
   * document, and its FITS_LONG flag the tape's, which is false for a document's {@code -0}.
   */
  static LiteralNumber copyOf(NumberView view) {
    int off = view.tokenOffset();
    return new LiteralNumber(new String(view.tokenBytes(), off, view.tokenLength(), US_ASCII), view.fitsLong());
  }

  /**
   * The literal of the filter lexer's number lexeme {@code ([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?}. The lexeme
   * is normalised to a strict token first, as jq's decNumber reads it: the integer part loses its leading zeros (an
   * all-zero or missing one becomes {@code 0}) and a point with no digit after it goes ({@code 007} is {@code 7},
   * {@code .5} is {@code 0.5}, {@code 1.e2} is {@code 1e2}).
   */
  public static LiteralNumber ofLexeme(String lexeme) {
    int point = lexeme.indexOf('.');
    int exponent = Math.max(lexeme.indexOf('e'), lexeme.indexOf('E'));
    int integerEnd = point >= 0 ? point : exponent >= 0 ? exponent : lexeme.length();
    int fractionEnd = exponent >= 0 ? exponent : lexeme.length();
    int digit = 0;
    while (digit < integerEnd - 1 && lexeme.charAt(digit) == '0') {
      digit++;
    }
    StringBuilder token = new StringBuilder(lexeme.length() + 1);
    token.append(integerEnd == 0 ? "0" : lexeme.substring(digit, integerEnd));
    if (point >= 0 && fractionEnd > point + 1) {
      token.append(lexeme, point, fractionEnd);
    }
    token.append(lexeme, fractionEnd, lexeme.length());
    return new LiteralNumber(token.toString());
  }

  /**
   * jq's {@code tonumber} of a string, its {@code jv_number_with_literal}: the number that the numeric-string grammar
   * of the General Decimal Arithmetic specification, decNumber's, reads from all of {@code text}, or Java {@code null}
   * when it reads none (written from the grammar, not from decNumber's code; ADR-0012 "tonumber", ADR-0015). That is an
   * optional sign, then digits with at most one point and at least one digit, then an optional exponent {@code e} or
   * {@code E} with an optional sign and at least one digit; or {@code Inf} or {@code Infinity}; or {@code NaN} or
   * {@code sNaN} with a payload of zeros, each in any case. The number is a literal of the strict token the text
   * normalises to, as {@link #ofLexeme} normalises: {@code +} and the integer part's leading zeros go, a missing integer
   * part is {@code 0}, a point without digits after it goes, and {@code -} stays, on a zero too ({@code "-0"} gives
   * {@code -0}). An infinity is an Infinity literal of its sign; a NaN is a computed NaN, as jq makes it (jq-ref 1.8.2,
   * recorded: natives/tonumber-strings, natives/tonumber-nonfinite). It reads each byte once.
   */
  public static JqNumber ofText(byte[] text) {
    int n = text.length;
    int p = 0;
    boolean negative = false;
    if (p < n && (text[p] == '+' || text[p] == '-')) {
      negative = text[p] == '-';
      p++;
    }
    StringBuilder token = new StringBuilder(n + 1);
    if (negative) {
      token.append('-');
    }
    if (p < n && !digit(text[p]) && text[p] != '.') {
      if (starts(text, p, "inf") && p + 3 == n || starts(text, p, "infinity") && p + 8 == n) {
        return new LiteralNumber(token.append(INFINITY).toString(), false);
      }
      int payload = starts(text, p, "nan") ? p + 3 : starts(text, p, "snan") ? p + 4 : -1;
      if (payload < 0) {
        return null;
      }
      while (payload < n && text[payload] == '0') {
        payload++;
      }
      return payload == n ? new DoubleNumber(Double.NaN) : null;
    }
    int integer = p;
    while (p < n && digit(text[p])) {
      p++;
    }
    int integerEnd = p;
    int fraction = p;
    if (p < n && text[p] == '.') {
      fraction = ++p;
      while (p < n && digit(text[p])) {
        p++;
      }
    }
    int fractionEnd = p;
    if (integerEnd == integer && fractionEnd == fraction) {
      return null;
    }
    int exponent = p;
    if (p < n && (text[p] == 'e' || text[p] == 'E')) {
      p++;
      if (p < n && (text[p] == '+' || text[p] == '-')) {
        p++;
      }
      int digits = p;
      while (p < n && digit(text[p])) {
        p++;
      }
      if (p == digits) {
        return null;
      }
    }
    if (p != n) {
      return null;
    }
    while (integer < integerEnd - 1 && text[integer] == '0') {
      integer++;
    }
    token.append(integer == integerEnd ? "0" : new String(text, integer, integerEnd - integer, US_ASCII));
    if (fractionEnd > fraction) {
      token.append('.').append(new String(text, fraction, fractionEnd - fraction, US_ASCII));
    }
    token.append(new String(text, exponent, n - exponent, US_ASCII));
    String strict = token.toString();
    // An integer token fits a long by its digits, but -0 never does (ADR-0012 FITS_LONG).
    return new LiteralNumber(strict, fitsLong(strict) && !strict.equals("-0"));
  }

  private static boolean digit(byte b) {
    return b >= '0' && b <= '9';
  }

  /**
   * Whether {@code text} has {@code lower}, a word of ASCII small letters, at {@code p}, in any case: {@code | 0x20}
   * turns an ASCII capital into its small letter, keeps a small letter, and turns no other byte into a letter.
   */
  private static boolean starts(byte[] text, int p, String lower) {
    if (text.length - p < lower.length()) {
      return false;
    }
    for (int i = 0; i < lower.length(); i++) {
      if ((text[p + i] | 0x20) != lower.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * jq's {@code jv_number_negate} of the literal whose strict token is {@code b[off, off + len)}, decNumber's
   * {@code decNumberMinus} (ADR-0012): the token with its sign flipped, except that a zero of either sign gives a
   * positive zero with the same exponent ({@code -0} gives {@code 0}, {@code 0E-7} gives {@code 0E-7}).
   */
  static LiteralNumber negated(byte[] b, int off, int len) {
    String token = new String(b, off, len, US_ASCII);
    if (b[off] == '-') {
      return new LiteralNumber(token.substring(1));
    }
    return new LiteralNumber(CanonicalForm.zero(b, off, len) ? token : "-" + token);
  }

  /** The canonical form's ASCII bytes, which the writer copies; never written. */
  public byte[] canonical() {
    return canonical;
  }

  /** jq's compact text: the canonical form (ADR-0012). */
  @Override
  public String toJson() {
    return new String(canonical, US_ASCII);
  }

  /** jq's printed form (ADR-0016), the canonical form: what a host sees of a detached payload number (ADR-0039). */
  @Override
  public String toString() {
    return toJson();
  }

  /** ADR-0012 "Values", ADR-0028: an Infinity literal gives an infinity. */
  @Override
  public double doubleValue() {
    return doubleValue;
  }

  @Override
  public boolean fitsLong() {
    return fitsLong;
  }

  @Override
  public long longValue() {
    if (!fitsLong) {
      throw new ArithmeticException("the number is not an integer that a long holds");
    }
    return Long.parseLong(new String(token, US_ASCII));
  }

  /** ADR-0028: the canonical form's value, its scale kept; an Infinity literal throws {@code ArithmeticException}. */
  @Override
  public BigDecimal decimalValue() {
    return CanonicalForm.toDecimal(token, 0, token.length);
  }

  @Override
  public byte[] tokenBytes() {
    return token;
  }

  @Override
  public int tokenOffset() {
    return 0;
  }

  @Override
  public int tokenLength() {
    return token.length;
  }

  @Override
  public boolean isShort() {
    return isShort;
  }

  /**
   * ADR-0012's short token, as the reader's CANONICAL flag gives it to a view: at most {@link #SHORT} bytes, equal to
   * the canonical form and without an exponent. A canonical exponent token such as {@code 1E+400}, {@code 1E-400} or
   * {@code 1.5E-323} has a double (+Infinity, 0, a rounded subnormal) that does not order it as its decimal does.
   */
  private static boolean isShort(byte[] token, byte[] canonical) {
    if (token.length > SHORT || !Arrays.equals(token, canonical)) {
      return false;
    }
    for (byte b : token) {
      if (b == 'E') {
        return false;
      }
    }
    return true;
  }
}
