/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): the RANGE opcode in src/execute.c, and range/2 in
 * src/builtin.c (bind_bytecoded_builtins).
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

import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.DoubleNumber;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Many;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqNumber;

/**
 * jq 1.8.2's {@code range/2} on its two bounds, start and end (ADR-0041; jq-ref, recorded: diff/reduce.rec range2-*):
 * the start itself first, so a literal keeps its form ({@code [range(1.10;3)]} gives {@code [1.10,2.1]}), then each
 * value the double before it plus 1, rounded at each step, while the value's double is below the end's; a bound that is
 * no number raises "Range bounds must be numeric" before any result. Its steps are the caller's, one per value; it
 * charges nothing.
 *
 * <p>A document's number computes its double at each read (ADR-0012), O(its token) for each, so {@link #first} reads
 * the end's double once and puts it in the arguments' place as a {@link DoubleNumber}, which each later step reads.
 *
 * <p>The cursor of a later value is that value's double, its bits with every NaN as the one canonical NaN; the first
 * value's is {@link #START}, another NaN's bits, so no double's cursor is it or {@link Many#END}.
 */
public final class Range implements Many {
  /** {@code range(start; end)}. */
  public static final Operation OP = new Operation("range", new Range());

  /** The cursor of the first value, the start: a NaN's bits that {@link Double#doubleToLongBits} never gives. */
  private static final long START = -2;

  private Range() {}

  @Override
  public long first(RunState state, Object in, Object[] args) {
    if (!(args[0] instanceof JqNumber start) || !(args[1] instanceof JqNumber end)) {
      throw new FilterException(BuiltString.of("Range bounds must be numeric"));
    }
    double last = end.doubleValue();
    if (start.doubleValue() >= last) {
      return END;
    }
    args[1] = new DoubleNumber(last);
    return START;
  }

  /** {@link #first} gave a cursor only to two numbers, the end as its double, so only they come here. */
  @Override
  public long next(RunState state, Object in, Object[] args, long cursor) {
    double v = (cursor == START ? ((JqNumber) args[0]).doubleValue() : Double.longBitsToDouble(cursor)) + 1;
    return v >= ((DoubleNumber) args[1]).doubleValue() ? END : Double.doubleToLongBits(v);
  }

  @Override
  public Object value(RunState state, Object in, Object[] args, long cursor) {
    return cursor == START ? args[0] : new DoubleNumber(Double.longBitsToDouble(cursor));
  }
}
