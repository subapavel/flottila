package io.github.subapavel.jqj.value;

import io.github.subapavel.jqj.internal.value.DoubleNumber;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.internal.value.NumberView;
import java.math.BigDecimal;

/** A jq number (ADR-0016, ADR-0012, ADR-0003): a document's, a literal written in the filter, or a computed one. */
public sealed interface JqNumber extends JqValue permits NumberView, LiteralNumber, DoubleNumber {
  /** The number as a double, by ADR-0012's 17-digit rule. */
  double doubleValue();

  /** Whether the number is an integer a long holds; false for {@code -0}. */
  boolean fitsLong();

  /**
   * The number as a long.
   *
   * @throws ArithmeticException unless {@link #fitsLong()}
   */
  long longValue();

  /**
   * The number as a decimal: for a literal the value of its canonical form, for a computed one its printed digits
   * (ADR-0028).
   *
   * <p>A literal keeps its scale ({@code 1.10} has scale 2), and {@code -0} gives a zero of the same scale, since
   * {@code BigDecimal} has no -0. Within jq's exponent limits the value is the literal's exact value. Past them it is
   * the value jq prints: {@code 5e-1147483647} gives {@code 1E-1147483646}, {@code 1e-2000000000} gives
   * {@code 0E-1147483646}. At the upper limit the value stays finite: {@code 1e999999999} prints
   * {@code 1E+999999999} and gives that value, while {@link #doubleValue()} is infinite. Above it
   * ({@code 1E1000000000}, {@code 10e999999999}) the literal is an Infinity literal: it prints
   * {@code 1.7976931348623157e+308} with its sign, {@link #doubleValue()} is that infinity, and this method throws.
   *
   * @throws ArithmeticException for NaN, the infinities and an Infinity literal
   */
  BigDecimal decimalValue();
}
