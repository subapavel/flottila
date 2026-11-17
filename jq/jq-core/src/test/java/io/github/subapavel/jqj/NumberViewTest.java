package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNumber;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A document's number through the exported API (ADR-0016 {@code JqNumber}, ADR-0012 "Values", ADR-0028): its
 * {@code doubleValue()} and {@code decimalValue()}. Doubles are compared by their bits, so -0.0 is not 0.0. Every
 * expected value is a jq-ref 1.8.2 observation quoted beside it (2026-11-12), or ADR-0012's rule checked against
 * jq-ref.
 */
class NumberViewTest {

  private static JqNumber number(String token) {
    return kind(JqNumber.class, read(newWorkspace(), token).root(), token);
  }

  private static void assertDouble(String token, double actual, double expected, String why) {
    assertThat(Double.doubleToRawLongBits(actual))
        .as("doubleValue() of %s is %s, expected %s (%s)", token, actual, expected, why)
        .isEqualTo(Double.doubleToRawLongBits(expected));
  }

  @Test
  void doubleValueRoundsToSeventeenDigitsFirst() {
    // ADR-0012: more than 17 significant digits are rounded HALF_EVEN to 17 first, then parsed. jq-ref 1.8.2:
    //   jq-ref -n '1.0000000000000001111 + 0'                          -> 1
    //   printf '1.0000000000000001111' | jq-ref -c '[., . + 0]'        -> [1.0000000000000001111,1]
    // The correctly rounded double of the token is 1.0000000000000002, the next double above 1.
    assertDouble("1.0000000000000001111", number("1.0000000000000001111").doubleValue(), Double.parseDouble("1"), "jq-ref prints 1");
    //   printf '1.62171207762624005' | jq-ref -c '[., . + 0]'          -> [1.62171207762624005,1.62171207762624]
    // An exact tie at the 18th digit: HALF_EVEN keeps ...400 and gives 1.62171207762624, while HALF_UP to 17 digits
    // and the correctly rounded parse both give 1.6217120776262401.
    assertDouble("1.62171207762624005", number("1.62171207762624005").doubleValue(), Double.parseDouble("1.62171207762624"), "jq-ref prints 1.62171207762624");
    //   printf '[1.7976931348623157e308,4.9e-324,123456789012345678]' | jq-ref -c 'map([., . + 0])'
    //     -> [[1.7976931348623157E+308,1.7976931348623157e+308],[4.9E-324,5e-324],[123456789012345678,123456789012345680]]
    assertDouble("1.7976931348623157e308", number("1.7976931348623157e308").doubleValue(), Double.MAX_VALUE, "the largest double");
    assertDouble("4.9e-324", number("4.9e-324").doubleValue(), Double.MIN_VALUE, "jq-ref prints 5e-324, the smallest double");
    assertDouble("123456789012345678", number("123456789012345678").doubleValue(), Double.parseDouble("123456789012345680"), "jq-ref prints 123456789012345680");
  }

  @Test
  void doubleValueOfAnInfinityLiteralIsInfinite() {
    // ADR-0028: the double of an Infinity literal is +-Infinity, though it prints +-1.7976931348623157e+308. jq-ref:
    //   printf '[1E1000000000,-1E1000000000,10e999999999,1e2000000000,1e999999999]' \
    //     | jq-ref -c 'map([., isinfinite, . > 0])'
    //   -> [[1.7976931348623157e+308,true,true],[-1.7976931348623157e+308,true,false],[1.7976931348623157e+308,true,true],
    //       [1.7976931348623157e+308,true,true],[1E+999999999,true,true]]
    for (String token : List.of("1E1000000000", "10e999999999", "1e2000000000")) {
      JqNumber n = number(token);
      assertDouble(token, n.doubleValue(), Double.POSITIVE_INFINITY, "isinfinite and > 0 on jq-ref");
      assertThat(n.toJson()).as("toJson() of %s", token).isEqualTo("1.7976931348623157e+308");
    }
    JqNumber negative = number("-1E1000000000");
    assertDouble("-1E1000000000", negative.doubleValue(), Double.NEGATIVE_INFINITY, "isinfinite and not > 0 on jq-ref");
    assertThat(negative.toJson()).as("toJson() of -1E1000000000").isEqualTo("-1.7976931348623157e+308");
    // Witness: the largest finite literal is no Infinity literal; its double overflows all the same.
    assertDouble("1e999999999", number("1e999999999").doubleValue(), Double.POSITIVE_INFINITY, "isinfinite on jq-ref");
    assertThat(number("1e999999999").toJson()).as("toJson() of 1e999999999").isEqualTo("1E+999999999");
  }

  @Test
  void doubleValueKeepsNegativeZero() {
    // jq-ref: printf '[-0,-0.0,-0e5,-0E-1147483650,-4e-1147483647,0]' | jq-ref -c 'map([., . * 1])'
    //   -> [[-0,-0],[-0.0,-0],[-0E+5,-0],[-0E-1147483646,-0],[-0E-1147483646,-0],[0,0]]
    for (String token : List.of("-0", "-0.0", "-0e5", "-0E-1147483650", "-4e-1147483647")) {
      assertDouble(token, number(token).doubleValue(), -0.0, "jq-ref's . * 1 prints -0");
    }
    // Witness: a positive zero stays positive, so the sign is the token's.
    assertDouble("0", number("0").doubleValue(), 0.0, "jq-ref's . * 1 prints 0");
  }

  @Test
  void decimalValueIsTheValueAfterTheLimits() {
    // ADR-0028: the canonical form's value, its scale kept; -0 gives a zero of the same scale. jq-ref:
    //   printf -- '[1.10,-0,-0.0000000,5e-1147483647,95e-1147483647,0e-1147483650,-0e1000000000,1e-2000000000,1e2,
    //   1e999999999]' | jq-ref -c .
    //   -> [1.10,-0,-0E-7,1E-1147483646,1.0E-1147483645,0E-1147483646,-0E+999999999,0E-1147483646,1E+2,1E+999999999]
    String[] tokens = {"1.10", "-0", "-0.0000000", "5e-1147483647", "95e-1147483647", "0e-1147483650", "-0e1000000000", "1e-2000000000", "1e2", "1e999999999"};
    String[] printed = {"1.10", "-0", "-0E-7", "1E-1147483646", "1.0E-1147483645", "0E-1147483646", "-0E+999999999", "0E-1147483646", "1E+2", "1E+999999999"};
    JqArray all = kind(JqArray.class, read(newWorkspace(), "[" + String.join(",", tokens) + "]").root(), "the array");
    for (int i = 0; i < tokens.length; i++) {
      BigDecimal expected = new BigDecimal(printed[i]);
      BigDecimal actual = kind(JqNumber.class, all.get(i), tokens[i]).decimalValue();
      assertThat(actual).as("decimalValue() of %s, which jq-ref prints %s", tokens[i], printed[i]).isEqualTo(expected);
      assertThat(actual.scale()).as("scale of decimalValue() of %s", tokens[i]).isEqualTo(expected.scale());
    }
  }

  @Test
  void decimalValueOfAnInfinityLiteralThrows() {
    // ADR-0028: an Infinity literal has no decimal value. jq-ref prints each of these 1.7976931348623157e+308 with
    // its sign (number.rec, cases upper-limit and saturated-exponent).
    for (String token : List.of("1E1000000000", "-1E1000000000", "10e999999999", "1e2000000000")) {
      JqNumber n = number(token);
      assertThatExceptionOfType(ArithmeticException.class).as("decimalValue() of %s", token).isThrownBy(n::decimalValue);
    }
    // Witness: at the upper limit the value is finite (number.rec, upper-limit: 1E+999999999 and 9.999E+999999999).
    assertThat(number("1e999999999").decimalValue()).as("decimalValue() of 1e999999999").isEqualTo(new BigDecimal("1E+999999999"));
    assertThat(number("9.999e999999999").decimalValue()).as("decimalValue() of 9.999e999999999").isEqualTo(new BigDecimal("9.999E+999999999"));
  }

  /** CODING_STANDARDS: one case per seed, the seed in the case name. */
  static LongStream seeds() {
    return LongStream.range(0, 1_000);
  }

  /**
   * ADR-0012 "Values": a literal's double is its value rounded HALF_EVEN to 17 significant digits, then correctly
   * rounded to a double, whatever path computes it. Each seed makes one token: a zero, a 15- to 19-digit integer
   * (FITS_LONG or just past it), or 1 to 40 digits with a point anywhere and an exponent up to 22, 330 or 420 in
   * magnitude, so the one-operation, long and BigDecimal paths and both 400 cut-offs are all met. The rule was checked
   * against jq-ref 1.8.2 on seeds 0 to 999, 2026-11-12: {@code jq-ref -c '.[] | . * 1'} over the array of the 1 000
   * tokens printed digits that parse to exactly these doubles (and 1.7976931348623157e+308, jq's text for an
   * infinite double, where the rule gives an infinity).
   */
  @ParameterizedTest(name = "seed {0}")
  @MethodSource("seeds")
  void doubleIsTheSeventeenDigitRule(long seed) {
    String token = doubleToken(new SplittableRandom(seed));
    assertDouble(token, number(token).doubleValue(), seventeenDigitRule(token), "seed " + seed + ", ADR-0012's rule");
  }

  /** ADR-0012's rule, the oracle: HALF_EVEN to 17 significant digits, then the correctly rounded parse; the sign kept. */
  static double seventeenDigitRule(String token) {
    BigDecimal exact = new BigDecimal(token);
    double magnitude = Double.parseDouble(exact.abs().round(new MathContext(17, RoundingMode.HALF_EVEN)).toString());
    return token.charAt(0) == '-' ? -magnitude : magnitude;
  }

  /** A strict token for {@link #doubleIsTheSeventeenDigitRule}. */
  static String doubleToken(RandomGenerator random) {
    StringBuilder b = new StringBuilder();
    if (random.nextBoolean()) b.append('-');
    int kind = random.nextInt(8);
    if (kind == 0) {
      b.append('0');
      if (random.nextBoolean()) b.append('.').append("0".repeat(random.nextInt(1, 4)));
      if (random.nextBoolean()) b.append('e').append(random.nextInt(-30, 31));
      return b.toString();
    }
    int nd = kind == 1 ? random.nextInt(15, 20) : switch (random.nextInt(3)) {
      case 0 -> random.nextInt(1, 16);
      case 1 -> random.nextInt(16, 20);
      default -> random.nextInt(20, 41);
    };
    char[] d = new char[nd];
    d[0] = (char) ('1' + random.nextInt(9));
    for (int i = 1; i < nd; i++) d[i] = random.nextInt(4) == 0 ? '0' : (char) ('0' + random.nextInt(10));
    if (kind == 1) return b.append(d).toString();
    int point = random.nextInt(0, nd + 1);
    if (point == 0) {
      b.append("0.").append("0".repeat(random.nextInt(0, 4))).append(d);
    } else {
      b.append(d, 0, point);
      if (point < nd) b.append('.').append(d, point, nd - point);
    }
    int range = random.nextInt(4);
    if (range > 0) {
      int bound = range == 1 ? 22 : range == 2 ? 330 : 420;
      b.append(random.nextBoolean() ? 'e' : 'E').append(random.nextInt(-bound, bound + 1));
    }
    return b.toString();
  }
}
