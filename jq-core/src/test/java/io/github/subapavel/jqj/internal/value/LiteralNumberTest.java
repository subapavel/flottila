package io.github.subapavel.jqj.internal.value;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A literal number written in a filter (GLOSSARY "Literal number", "Lenient token"; ADR-0012, ADR-0031):
 * {@code LiteralNumber.ofLexeme} takes the filter lexer's number lexeme ({@code ([0-9]+(\.[0-9]*)?|\.[0-9]+)
 * ([eE][+-]?[0-9]+)?}), normalises it, and prints its canonical form; its flags come from the normalised lexeme.
 */
class LiteralNumberTest {

  /** One case per seed (docs/agents/environment.md, "Property tests"). */
  static IntStream seeds() {
    return IntStream.range(0, 1000);
  }

  /**
   * Within the exponent limits a literal prints as {@code BigDecimal.toString} prints the lexeme (ADR-0012
   * "Printing"); a filter lexeme has no sign, so the sign of zero never differs. The oracle was checked on jq-ref
   * 1.8.2 for seeds 0 to 999 of this generator (2026-11-12): {@code jq-ref -n -c '<lexeme>, <lexeme>, ...'} printed
   * {@code new BigDecimal(lexeme).toString()} for every seed, byte for byte.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void lenientMatchesBigDecimal(int seed) {
    String lexeme = lexeme(seed);

    assertThat(LiteralNumber.ofLexeme(lexeme).toJson())
        .as("canonical form of %s (seed %d)", lexeme, seed)
        .isEqualTo(new BigDecimal(lexeme).toString());
  }

  @Test
  void leadingZerosFitALong() {
    // ADR-0031: the flags are the normalised lexeme's, so 007 fits a long (ADR-0012 FITS_LONG); jq-ref 1.8.2,
    // recorded in diff/literal.rec: 007 -> 7, 1. -> 1, 00.50 -> 0.50, 1e2 -> 1E+2.
    LiteralNumber seven = LiteralNumber.ofLexeme("007");
    assertThat(seven.fitsLong()).as("fitsLong of 007").isTrue();
    assertThat(seven.longValue()).as("longValue of 007").isEqualTo(7L);
    assertThat(seven.toJson()).as("canonical form of 007").isEqualTo("7");

    LiteralNumber one = LiteralNumber.ofLexeme("1.");
    assertThat(one.fitsLong()).as("fitsLong of 1.").isTrue();
    assertThat(one.longValue()).as("longValue of 1.").isEqualTo(1L);

    // The long range's edge, behind leading zeros: Long.MAX_VALUE fits, one more does not.
    assertThat(LiteralNumber.ofLexeme("0009223372036854775807").longValue())
        .as("longValue of 0009223372036854775807")
        .isEqualTo(Long.MAX_VALUE);
    assertThat(LiteralNumber.ofLexeme("0009223372036854775808").fitsLong()).as("fitsLong of 0009223372036854775808").isFalse();

    // Witnesses: a fraction or an exponent is no integer token, so neither fits a long.
    assertThat(LiteralNumber.ofLexeme("00.50").fitsLong()).as("fitsLong of 00.50").isFalse();
    assertThat(LiteralNumber.ofLexeme("1e2").fitsLong()).as("fitsLong of 1e2").isFalse();
  }

  @Test
  void aShortTokenIsCanonicalWithoutExponentAndAtMost15Bytes() {
    // ADR-0012 "Flags": the short token the reader's CANONICAL flag gives a view (#90).
    assertThat(LiteralNumber.ofLexeme("1.5").isShort()).as("isShort of 1.5").isTrue();
    assertThat(LiteralNumber.ofLexeme("12345678901.234").isShort()).as("isShort of 15 bytes").isTrue();
    assertThat(LiteralNumber.ofLexeme("12345678901.2345").isShort()).as("isShort of 16 bytes").isFalse();
    assertThat(LiteralNumber.ofLexeme("1e2").isShort()).as("isShort of 1e2, not canonical").isFalse();
    assertThat(LiteralNumber.ofLexeme("1E+400").isShort()).as("isShort of 1E+400, canonical").isFalse();
  }

  @Test
  void canonicalExponentLiteralsCompareByDecimalValue() {
    // jq-ref 1.8.2, recorded in diff/big-literal.rec: [1E+400 == 1E+401, 1E-400 == 0] -> [false,false],
    // 1E+400 < 1E+401 and 0 < 1E-400 are true; through double both pairs would be equal (#90).
    assertThat(Numbers.compare(LiteralNumber.ofLexeme("1E+400"), LiteralNumber.ofLexeme("1E+401")))
        .as("compare 1E+400 with 1E+401")
        .isEqualTo(-1);
    assertThat(Numbers.compare(LiteralNumber.ofLexeme("1E-400"), LiteralNumber.ofLexeme("0")))
        .as("compare 1E-400 with 0")
        .isEqualTo(1);
  }

  /**
   * A lexeme of the filter's number grammar: an integer part, a fraction after it (possibly empty, as {@code 1.}) or
   * alone ({@code .5}), and an optional exponent of 1 to 3 digits, so the adjusted exponent stays inside the exponent
   * limits. Digits lean to 0 for leading and trailing zeros.
   */
  static String lexeme(long seed) {
    Random r = new Random(seed);
    StringBuilder s = new StringBuilder();
    int form = r.nextInt(3);
    if (form != 2) {
      s.append(digits(r, 1 + r.nextInt(22)));
    }
    if (form != 0) {
      s.append('.');
      s.append(digits(r, (form == 2 ? 1 : 0) + r.nextInt(22)));
    }
    if (r.nextInt(2) == 0) {
      s.append(r.nextBoolean() ? 'e' : 'E');
      int sign = r.nextInt(3);
      if (sign == 1) {
        s.append('+');
      }
      if (sign == 2) {
        s.append('-');
      }
      s.append(digits(r, 1 + r.nextInt(3)));
    }
    return s.toString();
  }

  private static String digits(Random r, int n) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < n; i++) {
      s.append(r.nextInt(3) == 0 ? '0' : (char) ('0' + r.nextInt(10)));
    }
    return s.toString();
  }
}
