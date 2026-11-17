package io.github.subapavel.jqj;

import static io.github.subapavel.jqj.TestDocuments.kind;
import static io.github.subapavel.jqj.TestDocuments.newWorkspace;
import static io.github.subapavel.jqj.TestDocuments.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.subapavel.jqj.value.JqNumber;
import org.junit.jupiter.api.Test;

/**
 * ADR-0012 "Values": past 17 significant digits a literal's double is its value rounded HALF_EVEN to 17 digits, then
 * parsed. These literals sit at a tie in the 18th digit, so only the digits after it decide the rounding, however far
 * away the first non-zero one is (#36 implementer, beside NumberViewTest.doubleValueRoundsToSeventeenDigitsFirst).
 * Each token is a whole document. jq-ref 1.8.2, observed 2026-11-12:
 *
 * <pre>
 * printf '[1.621712077626240051,1.6217120776262400500,1.62171207762624005,-1.621712077626240051]' \
 *   | jq-ref -c 'map([., . + 0])'
 *   -> [[1.621712077626240051,1.6217120776262401],[1.6217120776262400500,1.62171207762624],
 *       [1.62171207762624005,1.62171207762624],[-1.621712077626240051,-1.6217120776262401]]
 * Z = 100 000 zeros:
 * printf '1.62171207762624005Z1' | jq-ref -c '. + 0'          -> 1.6217120776262401
 * printf '1.62171207762624005Z' | jq-ref -c '. + 0'           -> 1.62171207762624
 * printf '162171207762624005Z1e-100018' | jq-ref -c '. + 0'   -> 1.6217120776262401
 * </pre>
 */
class LongCoefficientDoubleTest {

  private static final String ZEROS = "0".repeat(100_000);

  private static void assertDouble(String token, double expected, String printed) {
    double actual = kind(JqNumber.class, read(newWorkspace(), token).root(), "the token").doubleValue();
    assertThat(Double.doubleToRawLongBits(actual))
        .as("doubleValue() of a %d-byte token is %s; jq-ref's . + 0 prints %s", token.length(), actual, printed)
        .isEqualTo(Double.doubleToRawLongBits(expected));
  }

  @Test
  void digitsAfterTheEighteenthBreakATie() {
    double up = Double.parseDouble("1.6217120776262401");
    double even = Double.parseDouble("1.62171207762624");
    assertDouble("1.621712077626240051", up, "1.6217120776262401");
    assertDouble("-1.621712077626240051", -up, "-1.6217120776262401");
    assertDouble("1.62171207762624005" + ZEROS + "1", up, "1.6217120776262401");
    assertDouble("162171207762624005" + ZEROS + "1e-100018", up, "1.6217120776262401");
    // Witnesses: zeros after the 18th digit leave the tie, which HALF_EVEN breaks towards the even 17th digit.
    assertDouble("1.6217120776262400500", even, "1.62171207762624");
    assertDouble("1.62171207762624005", even, "1.62171207762624");
    assertDouble("1.62171207762624005" + ZEROS, even, "1.62171207762624");
  }
}
