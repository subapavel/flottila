package io.github.subapavel.jqj.internal.value;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.JqEngine;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * GLOSSARY "Canonical form", "Adjusted exponent", "Exponent limits" and "Infinity literal"; ADR-0012 "Printing" and
 * "Exponent limits"; the #36 design's table A. {@code CanonicalForm.write(bytes, off, len, out)} prints a strict
 * number token in jq's canonical form. Every expected text of the unit tests is a jq-ref 1.8.2 output recorded in
 * {@code oracle/src/test/resources/diff/number.rec} (2026-11-12, scripts/oracle-record.sh, every case {@code -c .});
 * each table names its case. The properties take {@code BigDecimal.toString} as their oracle, which ADR-0012 says
 * jq's canonical form is inside the limits, apart from the sign of zero.
 */
class CanonicalFormTest {

  /**
   * The canonical form of {@code token}, written from the middle of a larger array: digits before and after it, so a
   * write that ignored {@code off} or read past {@code len} would print them.
   */
  private static String canonical(String token) {
    byte[] bytes = ("98" + token + "76").getBytes(US_ASCII);
    StringBuilder text = new StringBuilder();
    CanonicalForm.write(
        bytes,
        2,
        token.length(),
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
    return text.toString();
  }

  private static void assertCanonical(Map<String, String> expected) {
    expected.forEach((token, text) -> assertThat(canonical(token)).as("the canonical form of %s", token).isEqualTo(text));
  }

  /** Pairs of token and expected text, in order. */
  private static Map<String, String> table(String... pairs) {
    Map<String, String> table = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) table.put(pairs[i], pairs[i + 1]);
    return table;
  }

  @Test
  void layoutFollowsTheAdjustedExponent() {
    // Plain when the exponent is at most 0 and the adjusted exponent at least -6, else exponent form (GLOSSARY
    // "Adjusted exponent"). number.rec, cases exponent-to-plain, exponent-to-scientific, fraction-zeros,
    // exponent-digits, long-coefficient and corpus.
    assertCanonical(
        table(
            // exponent-to-plain: [0.01,1.2,15,10,1.50,-0.000001,12.3,0.5]
            "1e-2", "0.01",
            "12e-1", "1.2",
            "1.5e1", "15",
            "1.0e1", "10",
            "150e-2", "1.50",
            "-1e-6", "-0.000001",
            "123e-1", "12.3",
            "0.5e0", "0.5",
            // exponent-to-scientific: [1E+2,1E+2,2.0E+2,1.23456E+80,-1.0E+28,1E-7,1.2E-7,1.5E+2]
            "1e2", "1E+2",
            "1E+2", "1E+2",
            "20e1", "2.0E+2",
            "123.456e78", "1.23456E+80",
            "-1.0e+28", "-1.0E+28",
            "1e-7", "1E-7",
            "12e-8", "1.2E-7",
            "1.5e2", "1.5E+2",
            // fraction-zeros: 0.0000010 -> 0.0000010, 0.00000010 -> 1.0E-7, -0.0000001 -> -1E-7, 0.00000012 -> 1.2E-7
            "0.0000010", "0.0000010",
            "0.00000010", "1.0E-7",
            "-0.0000001", "-1E-7",
            "0.00000012", "1.2E-7",
            // exponent-digits: [1,1,1E+7,0.01]
            "1e00", "1",
            "1e-0", "1",
            "1e007", "1E+7",
            "1E-000000000000000000002", "0.01",
            // long-coefficient: [1234567890123456789012345.67890,-9.8765432109876543210E+39]
            "123456789012345678901234567890e-5", "1234567890123456789012345.67890",
            "-98765432109876543210e20", "-9.8765432109876543210E+39",
            // corpus: the adjusted exponent -6 against -7 with more than one digit, and the exponent 0 against 1.
            "12e-7", "0.0000012",
            "123e-8", "0.00000123",
            "1234567e-12", "0.000001234567",
            "123e-9", "1.23E-7",
            "1234567e-13", "1.234567E-7",
            "123e0", "123",
            "120e-1", "12.0",
            "1.20e2", "120",
            "12e1", "1.2E+2",
            "0.01e3", "1E+1",
            "0.010e3", "10",
            "-45E+0", "-45",
            "100e-2", "1.00",
            "0.001e3", "1",
            "0.00012e0", "0.00012",
            "1e+000", "1",
            "1e0000000000000000000000000005", "1E+5",
            // corpus: tokens without an exponent come out unchanged when jq copies them.
            "1.10", "1.10",
            "3.00", "3.00",
            "100", "100",
            "0.000001", "0.000001",
            "12345678901234567890123456789012345678901234567890", "12345678901234567890123456789012345678901234567890"));
  }

  @Test
  void zeroKeepsItsSignAndExponent() {
    // A zero keeps its sign and its exponent, clamped into [-1147483646, 999999999] (GLOSSARY "Exponent limits").
    assertCanonical(
        table(
            // zeros: [0E+1,-0E+5,0,-0,0E+2,0.000000,0.0]
            "0e1", "0E+1",
            "-0e5", "-0E+5",
            "0e0", "0",
            "-0e0", "-0",
            "0.000e5", "0E+2",
            "0e-6", "0.000000",
            "0E-1", "0.0",
            // fraction-zeros: 0.0000000 -> 0E-7
            "0.0000000", "0E-7",
            // corpus
            "-0", "-0",
            "-0.0", "-0.0",
            "-0.000000", "-0.000000",
            "-0.0000000", "-0E-7",
            "0.00000000000", "0E-11",
            "0e-7", "0E-7",
            "-0.0e-5", "-0.000000",
            "0.00e2", "0",
            "0e+1", "0E+1",
            // zero-limits: [0E-1147483646,-0E+999999999]; corpus: the clamp at and past both ends
            "0e-1147483650", "0E-1147483646",
            "-0e1000000000", "-0E+999999999",
            "0e999999999", "0E+999999999",
            "0e1000000000", "0E+999999999",
            "0e-1147483646", "0E-1147483646",
            "0e-1147483647", "0E-1147483646",
            "-0e-1147483650", "-0E-1147483646"));
  }

  @Test
  void pastTheUpperExponentLimitIsAnInfinityLiteral() {
    // An adjusted exponent above 999 999 999 makes an Infinity literal, printed +-1.7976931348623157e+308 with a
    // lowercase e; at 999 999 999 the value prints in exponent form (GLOSSARY "Infinity literal").
    assertCanonical(
        table(
            // upper-limit: [1E+999999999,9.999E+999999999,1E+999999999,1.7976931348623157e+308,-1.7976931348623157e+308]
            "1e999999999", "1E+999999999",
            "9.999e999999999", "9.999E+999999999",
            "0.1e1000000000", "1E+999999999",
            "10e999999999", "1.7976931348623157e+308",
            "-1E1000000000", "-1.7976931348623157e+308",
            // corpus: leading fraction zeros lower the adjusted exponent; trailing digits do not raise it.
            "-9e999999999", "-9E+999999999",
            "9.99999999999999999999e999999999", "9.99999999999999999999E+999999999",
            "0.00001e1000000004", "1E+999999999",
            "0.0001e1000000003", "1E+999999999",
            "99e999999998", "9.9E+999999999",
            "1.5e999999999", "1.5E+999999999",
            "100e999999998", "1.7976931348623157e+308",
            "1e1000000000", "1.7976931348623157e+308",
            "1e1000000001", "1.7976931348623157e+308",
            // corpus: a literal past the double's range but inside the limits is no Infinity literal.
            "1.7976931348623157e308", "1.7976931348623157E+308",
            "2e308", "2E+308",
            "-1e400", "-1E+400"));
  }

  @Test
  void exponentPastTenDigitsSaturates() {
    // An exponent of more digits than an int or a long holds saturates: it never wraps round to a small one.
    // saturated-exponent: [1.7976931348623157e+308,1.7976931348623157e+308,1.7976931348623157e+308,0E-1147483646,
    // 0E+999999999]; corpus for the rest. 2^32, 2^32+1, 2^63 and 2^64 are where an int or a long wraps.
    String infinity = "1.7976931348623157e+308";
    assertCanonical(
        table(
            "1e1999999999", infinity,
            "1e2000000000", infinity,
            "1E+12345678901", infinity,
            "1e-2000000000", "0E-1147483646",
            "0e2000000000", "0E+999999999",
            "1e2147483647", infinity,
            "1e2147483648", infinity,
            "1e4294967296", infinity,
            "1e4294967297", infinity,
            "1e9223372036854775807", infinity,
            "1e9223372036854775808", infinity,
            "1e18446744073709551616", infinity,
            "1e18446744073709551617", infinity,
            "1e99999999999999999999", infinity,
            "0e99999999999", "0E+999999999",
            "0e18446744073709551616", "0E+999999999",
            "0e-99999999999", "0E-1147483646",
            "1e-2147483648", "0E-1147483646",
            "1e-4294967296", "0E-1147483646",
            "1e-4294967297", "0E-1147483646",
            "1e-18446744073709551616", "0E-1147483646",
            "-1e-99999999999999999999", "-0E-1147483646",
            "1e-9223372036854775808", "0E-1147483646"));
  }

  @Test
  void belowEtinyRoundsHalfUp() {
    // Below 1E-1147483646 the value is rounded HALF_UP to a multiple of it (GLOSSARY "Exponent limits"), a carry
    // through nines adding a digit; above it every digit is kept, subnormal or not.
    assertCanonical(
        table(
            // lower-limit: [1E-1147483646,0E-1147483646,-0E-1147483646,2E-1147483646,1E-1147483646,1.0E-1147483645]
            "5e-1147483647", "1E-1147483646",
            "4e-1147483647", "0E-1147483646",
            "-4e-1147483647", "-0E-1147483646",
            "15e-1147483647", "2E-1147483646",
            "99e-1147483648", "1E-1147483646",
            "95e-1147483647", "1.0E-1147483645",
            // corpus
            "-5e-1147483647", "-1E-1147483646",
            "1e-1147483647", "0E-1147483646",
            "14e-1147483647", "1E-1147483646",
            "149e-1147483648", "1E-1147483646",
            "150e-1147483648", "2E-1147483646",
            "999e-1147483648", "1.0E-1147483645",
            "9999e-1147483649", "1.0E-1147483645",
            "1.5e-1147483646", "2E-1147483646",
            "0.5e-1147483646", "1E-1147483646",
            "1e-1147483648", "0E-1147483646",
            "9e-1147483648", "0E-1147483646",
            "-9e-1147483648", "-0E-1147483646",
            "1234567e-1147483650", "1.23E-1147483644",
            "123e-1147483648", "1E-1147483646",
            // corpus and below-emin-exact: at the limit and above it nothing is rounded.
            "1e-1147483646", "1E-1147483646",
            "5e-1147483646", "5E-1147483646",
            "123456789e-1147483646", "1.23456789E-1147483638",
            "1e-1000000000", "1E-1000000000",
            "1.5e-1100000000", "1.5E-1100000000",
            "-7e-1100000000", "-7E-1100000000"));
  }

  @Test
  void roundingUpBelowEtinyGrowsTheLastDigitBelowNine() {
    // Below 1E-1147483646 a literal is rounded HALF_UP to a multiple of it (GLOSSARY "Exponent limits"). These cases
    // round up where the kept digits do not end in a carry through all of them, so the digit that grows is not the
    // first one (beside belowEtinyRoundsHalfUp, whose round-ups grow the first digit or carry through all nines).
    // jq-ref 1.8.2, observed 2026-11-12:
    //   printf '[1295e-1147483648,12995e-1147483648,-1995e-1147483648,1249e-1147483648,1250e-1147483648]' | jq-ref -c .
    //   -> [1.3E-1147483645,1.30E-1147483644,-2.0E-1147483645,1.2E-1147483645,1.3E-1147483645]
    assertCanonical(
        table(
            "1295e-1147483648", "1.3E-1147483645",
            "12995e-1147483648", "1.30E-1147483644",
            "-1995e-1147483648", "-2.0E-1147483645",
            "1249e-1147483648", "1.2E-1147483645",
            "1250e-1147483648", "1.3E-1147483645"));
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 1_000);
  }

  /**
   * Inside the exponent limits the canonical form is {@code BigDecimal.toString} of the token, with a {@code -} in
   * front of a negative zero (ADR-0012 "Printing"; the #36 design, section 2). Each seed makes one strict token with
   * an exponent of magnitude at most 10^6. The oracle was checked against jq-ref 1.8.2 on seeds 0 to 999,
   * 2026-11-12: {@code jq-ref -c '.[]'} over the array of the 1 000 tokens printed exactly these texts.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void canonicalFormIsBigDecimalToString(long seed) {
    String token = strictToken(new SplittableRandom(seed));
    assertThat(canonical(token)).as("seed %d: the canonical form of %s", seed, token).isEqualTo(bigDecimalText(token));
  }

  /**
   * ADR-0012 "CANONICAL": the reader sets the flag exactly on the tokens without an exponent whose canonical form is
   * the token itself, which is what lets the writer copy them; for a flagged token {@code CanonicalForm} must agree.
   * Each seed makes one token around ADR-0012's edges: a {@code [-]0.} fraction with 0 to 9 leading zeros, an integer
   * part, an exponent now and then. The oracle text is {@code BigDecimal.toString} as in
   * {@link #canonicalFormIsBigDecimalToString}, checked against jq-ref 1.8.2 on seeds 0 to 999 the same way.
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void canonicalFlagMeansUnchanged(long seed) {
    String token = flagToken(new SplittableRandom(seed));
    JqValue root = JqEngine.builder().build().newWorkspace().read(MemorySegment.ofArray(token.getBytes(US_ASCII))).root();
    assertThat(root).as("seed %d: the root of %s", seed, token).isInstanceOf(NumberView.class);
    NumberView number = (NumberView) root;
    boolean flagged = (Tape.span(number.doc().live()[number.index()]) & Tape.CANONICAL) != 0;
    boolean exponent = token.indexOf('e') >= 0 || token.indexOf('E') >= 0;
    assertThat(flagged).as("seed %d: CANONICAL on %s", seed, token).isEqualTo(!exponent && bigDecimalText(token).equals(token));
    if (flagged) {
      assertThat(canonical(token)).as("seed %d: the canonical form of the CANONICAL token %s", seed, token).isEqualTo(token);
    }
  }

  /** The oracle: {@code BigDecimal.toString}, and a {@code -} before a negative zero, which BigDecimal has not. */
  static String bigDecimalText(String token) {
    BigDecimal value = new BigDecimal(token);
    String text = value.toString();
    return token.charAt(0) == '-' && value.signum() == 0 ? "-" + text : text;
  }

  /**
   * A strict token ({@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}): up to 25 integer digits, up to 14
   * fraction digits, and in three of four an exponent of magnitude below 30, 1 000 or 1 000 001, sometimes with
   * leading zeros.
   */
  static String strictToken(RandomGenerator random) {
    StringBuilder b = new StringBuilder();
    if (random.nextBoolean()) b.append('-');
    if (random.nextInt(4) == 0) {
      b.append('0');
    } else {
      b.append((char) ('1' + random.nextInt(9)));
      digits(random, b, random.nextInt(0, 25));
    }
    if (random.nextBoolean()) {
      b.append('.');
      digits(random, b, random.nextInt(1, 15));
    }
    if (random.nextInt(4) != 0) exponent(random, b, switch (random.nextInt(3)) {
      case 0 -> 30;
      case 1 -> 1_000;
      default -> 1_000_001;
    });
    return b.toString();
  }

  /**
   * A strict token around ADR-0012's CANONICAL edges: half of them {@code [-]0.} with 0 to 9 leading fraction zeros,
   * the others an integer part with or without a fraction; an exponent in one of four.
   */
  static String flagToken(RandomGenerator random) {
    StringBuilder b = new StringBuilder();
    if (random.nextBoolean()) b.append('-');
    boolean zeroInteger = random.nextBoolean();
    if (zeroInteger) {
      b.append('0');
    } else {
      b.append((char) ('1' + random.nextInt(9)));
      digits(random, b, random.nextInt(0, 22));
    }
    if (zeroInteger ? random.nextInt(8) != 0 : random.nextBoolean()) {
      int zeros = random.nextInt(0, 10);
      b.append('.').append("0".repeat(zeros));
      digits(random, b, random.nextInt(zeros == 0 ? 1 : 0, 4));
    }
    if (random.nextInt(4) == 0) exponent(random, b, 12);
    return b.toString();
  }

  /** {@code e} or {@code E}, no sign, {@code +} or {@code -}, at times leading zeros, then a magnitude below {@code bound}. */
  private static void exponent(RandomGenerator random, StringBuilder b, int bound) {
    b.append(random.nextBoolean() ? 'e' : 'E');
    int sign = random.nextInt(3);
    if (sign == 1) b.append('+');
    if (sign == 2) b.append('-');
    if (random.nextInt(4) == 0) b.append("0".repeat(random.nextInt(1, 4)));
    b.append(random.nextInt(bound));
  }

  /** {@code n} digits, each a zero with probability 2/5, so runs of zeros (leading in a fraction, trailing) occur. */
  private static void digits(RandomGenerator random, StringBuilder b, int n) {
    for (int i = 0; i < n; i++) b.append(random.nextInt(5) < 2 ? '0' : (char) ('1' + random.nextInt(9)));
  }
}
