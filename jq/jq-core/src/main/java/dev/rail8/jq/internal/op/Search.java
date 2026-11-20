/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_contains and f_bsearch in src/builtin.c.
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

import dev.rail8.jq.internal.value.ArrayView;
import dev.rail8.jq.internal.value.BuiltArray;
import dev.rail8.jq.internal.value.Containment;
import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.internal.value.Order;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;

/** jq 1.8.2's natives that search a value: {@code contains/1} and {@code bsearch/1}. */
public final class Search {
  /** {@code contains(b)}. */
  public static final Operation CONTAINS =
      new Operation("contains", (Fn1) (state, in, b) -> contains(state, (JqValue) in, (JqValue) b), Declared.FRESH);

  /** {@code bsearch(target)}. */
  public static final Operation BSEARCH =
      new Operation("bsearch", (Fn1) (state, in, target) -> bsearch(state, (JqValue) in, (JqValue) target), Declared.FRESH);

  private Search() {}

  /**
   * {@code a | contains(b)}: {@link Containment#contains} when the two have one kind, true and false being two;
   * otherwise jq's "cannot have their containment checked", which names both.
   */
  private static JqBool contains(RunState state, JqValue a, JqValue b) {
    if (kind(a) != kind(b)) {
      throw Arithmetic.cannot(state, a, b, "cannot have their containment checked");
    }
    return Containment.contains(state, a, b) ? JqBool.TRUE : JqBool.FALSE;
  }

  /** jq's kind of {@code v}, where true and false differ. */
  private static int kind(JqValue v) {
    return switch (v) {
      case JqNull _ -> 0;
      case JqBool b -> b.value() ? 2 : 1;
      case JqNumber _ -> 3;
      case JqString _ -> 4;
      case JqArray _ -> 5;
      case JqObject _ -> 6;
    };
  }

  /**
   * {@code a | bsearch(target)}, jq's binary search of an array it takes to be sorted by {@link Order#compare}: the
   * index of an element equal to {@code target}, or -1 - the place where {@code target} would keep the array sorted.
   * Each probe of the element in the middle of the range left ticks once, on top of the compare's ticks and a view's
   * read; the compare raises its "Comparison too deep". An input that is no array is jq's "cannot be searched from".
   */
  private static DoubleNumber bsearch(RunState state, JqValue a, JqValue target) {
    if (!(a instanceof JqArray array)) {
      throw Arithmetic.cannot(state, a, "cannot be searched from");
    }
    int start = 0;
    int end =
        switch (array) {
          case ArrayView view -> view.length(state);
          case BuiltArray built -> built.length();
        };
    while (start < end) {
      int mid = start + (end - start) / 2;
      state.tick();
      JqValue element =
          switch (array) {
            case ArrayView view -> view.get(state, mid);
            case BuiltArray built -> built.get(mid);
          };
      int result = Order.compare(state, target, element);
      if (result == 0) {
        return new DoubleNumber(mid);
      }
      if (result < 0) {
        end = mid;
      } else {
        start = mid + 1;
      }
    }
    return new DoubleNumber(-1 - start);
  }
}
