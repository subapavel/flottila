/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_nan, f_infinite, f_isnan, f_isinfinite, f_isnormal and
 * f_have_decnum (have_decnum and have_literal_numbers) in src/builtin.c.
 *
 * jq is copyright (C) 2012 Stephen Dolan
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.rail8.jq.internal.op;

import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNumber;
import java.util.function.DoublePredicate;

/**
 * jq 1.8.2's natives of numbers: {@code nan} and {@code infinite}, computed numbers whatever the input; {@code isnan},
 * {@code isinfinite} and {@code isnormal}, the C tests of a number's double, false for any other kind; and {@code
 * have_decnum} and {@code have_literal_numbers}, true, as the reference jq's build has decNumber and this engine keeps
 * literal numbers as it does. None ticks or charges, except a test's read of a non-short view's double ({@link
 * Numbers#doubleValue}).
 */
public final class NumberTests {
  /** {@code nan}. */
  public static final Operation NAN = constant("nan", new DoubleNumber(Double.NaN));

  /** {@code infinite}. */
  public static final Operation INFINITE = constant("infinite", new DoubleNumber(Double.POSITIVE_INFINITY));

  /** {@code isnan}. */
  public static final Operation ISNAN = test("isnan", Double::isNaN);

  /** {@code isinfinite}. */
  public static final Operation ISINFINITE = test("isinfinite", Double::isInfinite);

  /** {@code isnormal}: C's {@code isnormal}, neither zero, subnormal, infinite nor NaN. */
  public static final Operation ISNORMAL = test("isnormal", d -> Math.abs(d) >= Double.MIN_NORMAL && !Double.isInfinite(d));

  /** {@code have_decnum}. */
  public static final Operation HAVE_DECNUM = constant("have_decnum", JqBool.TRUE);

  /** {@code have_literal_numbers}. */
  public static final Operation HAVE_LITERAL_NUMBERS = constant("have_literal_numbers", JqBool.TRUE);

  private NumberTests() {}

  /** The operation {@code name} whose one result is {@code value}, shared by every run as a constant is. */
  private static Operation constant(String name, Object value) {
    return new Operation(name, (Fn0) (state, in) -> value);
  }

  /** The operation {@code name}: whether its input is a number whose double passes {@code test}. */
  private static Operation test(String name, DoublePredicate test) {
    return new Operation(name, (Fn0) (state, in) -> in instanceof JqNumber n && test.test(Numbers.doubleValue(state, n)) ? JqBool.TRUE : JqBool.FALSE);
  }
}
