package io.github.subapavel.jqj.internal.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A computed number's {@code JqNumber} contract (ADR-0012, ADR-0028), which no #65 operator reads yet: which doubles a
 * long holds, the long and decimal values, and the double order of {@link Numbers#compare} that computed numbers take.
 */
class DoubleNumberTest {
  @Test
  void aLongHoldsTheIntegersOfItsRangeOnly() {
    assertThat(new DoubleNumber(1.0).fitsLong()).as("1").isTrue();
    assertThat(new DoubleNumber(-0x1p63).fitsLong()).as("-2^63").isTrue();
    assertThat(new DoubleNumber(0x1p63).fitsLong()).as("2^63, which the cast saturates to 2^63 - 1").isFalse();
    assertThat(new DoubleNumber(-0.0).fitsLong()).as("-0").isFalse();
    assertThat(new DoubleNumber(1.5).fitsLong()).as("1.5").isFalse();
    assertThat(new DoubleNumber(Double.NaN).fitsLong()).as("NaN").isFalse();
    assertThat(new DoubleNumber(Double.POSITIVE_INFINITY).fitsLong()).as("infinity").isFalse();
  }

  @Test
  void theLongValueIsTheIntegerOrAnArithmeticException() {
    assertThat(new DoubleNumber(-0x1p63).longValue()).as("-2^63").isEqualTo(Long.MIN_VALUE);
    assertThatExceptionOfType(ArithmeticException.class).as("1.5").isThrownBy(() -> new DoubleNumber(1.5).longValue());
  }

  @Test
  void theDecimalValueIsThePrintedDigits() {
    assertThat(new DoubleNumber(0.1).decimalValue()).as("0.1").isEqualTo(new BigDecimal("0.1"));
    assertThat(new DoubleNumber(1e17).decimalValue()).as("1e+17").isEqualTo(new BigDecimal("1e+17"));
    assertThatExceptionOfType(ArithmeticException.class).as("NaN").isThrownBy(() -> new DoubleNumber(Double.NaN).decimalValue());
    assertThatExceptionOfType(ArithmeticException.class)
        .as("-infinity")
        .isThrownBy(() -> new DoubleNumber(Double.NEGATIVE_INFINITY).decimalValue());
  }

  @Test
  void computedNumbersCompareAsDoubles() {
    assertThat(Numbers.compare(new DoubleNumber(1), new DoubleNumber(2))).as("1 against 2").isEqualTo(-1);
    assertThat(Numbers.compare(new DoubleNumber(2), new DoubleNumber(1))).as("2 against 1").isEqualTo(1);
    assertThat(Numbers.compare(new DoubleNumber(2), new DoubleNumber(2))).as("2 against 2").isZero();
  }
}
