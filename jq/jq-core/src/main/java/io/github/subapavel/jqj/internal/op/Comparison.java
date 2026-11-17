/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): binop_equal, binop_notequal, order_cmp, binop_less,
 * binop_greater, binop_lesseq and binop_greatereq in src/builtin.c.
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
package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.Equality;
import io.github.subapavel.jqj.internal.value.Order;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq 1.8.2's comparison operators (#66, ADR-0037), the family's operations under jq's names. A binary operator's
 * arguments are its left and right operands, and its result a boolean: {@code ==} and {@code !=} by {@link Equality},
 * the four orders by {@link Order}, whose too-deep errors they raise. None builds a value.
 */
public final class Comparison {
  /** {@code l == r}. */
  public static final Operation EQUAL = new Operation("_equal", (Fn2) (state, in, l, r) -> bool(equal(state, l, r)));

  /** {@code l != r}. */
  public static final Operation NOT_EQUAL = new Operation("_notequal", (Fn2) (state, in, l, r) -> bool(!equal(state, l, r)));

  /** {@code l < r}. */
  public static final Operation LESS = new Operation("_less", (Fn2) (state, in, l, r) -> bool(order(state, l, r) < 0));

  /** {@code l > r}. */
  public static final Operation GREATER = new Operation("_greater", (Fn2) (state, in, l, r) -> bool(order(state, l, r) > 0));

  /** {@code l <= r}. */
  public static final Operation LESS_EQUAL = new Operation("_lesseq", (Fn2) (state, in, l, r) -> bool(order(state, l, r) <= 0));

  /** {@code l >= r}. */
  public static final Operation GREATER_EQUAL = new Operation("_greatereq", (Fn2) (state, in, l, r) -> bool(order(state, l, r) >= 0));

  private Comparison() {}

  private static boolean equal(RunState state, Object l, Object r) {
    return Equality.equal(state, (JqValue) l, (JqValue) r);
  }

  private static int order(RunState state, Object l, Object r) {
    return Order.compare(state, (JqValue) l, (JqValue) r);
  }

  private static JqBool bool(boolean b) {
    return b ? JqBool.TRUE : JqBool.FALSE;
  }
}
