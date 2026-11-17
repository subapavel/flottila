/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): binop_plus in src/builtin.c, and jv_array_concat in
 * src/jv.c.
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

import dev.rail8.jq.internal.value.BuiltArray;
import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.internal.value.Members;
import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.internal.value.ObjectMerge;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;

/**
 * jq 1.8.2's {@code l + r} (jq-ref, recorded: diff/arith-kinds.rec, arith-containers.rec): a null operand gives the
 * other unchanged, the left one first; two numbers their double sum; two strings, two arrays or two objects the
 * concatenation, object {@code +} for objects; any other pair is jq's error.
 */
final class Plus implements Fn2 {
  private static final String ERROR = "cannot be added";

  @Override
  public Object apply(RunState state, Object in, Object l, Object r) {
    return switch (new Operands(l, r)) {
      case Operands(JqNull _, JqValue b) -> b;
      case Operands(JqValue a, JqNull _) -> a;
      case Operands(JqNumber a, JqNumber b) -> new DoubleNumber(Numbers.doubleValue(state, a) + Numbers.doubleValue(state, b));
      case Operands(JqString a, JqString b) -> Strings.concat(state, a, b);
      case Operands(JqArray a, JqArray b) -> concat(state, a, b);
      case Operands(JqObject a, JqObject b) -> ObjectMerge.plus(state, a, b);
      case Operands(JqBool a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqNumber b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNumber a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNumber a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNumber a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNumber a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqNumber b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqNumber b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqNumber b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
    };
  }

  /**
   * jq's {@code jv_array_concat}: the elements of {@code a}, then those of {@code b}, charged as a container and an
   * element each before it is allocated; a view's length walk charges its elements (ADR-0034), and the copy ticks once
   * per element it copies (ADR-0034 amendment for #65).
   */
  private static BuiltArray concat(RunState state, JqArray a, JqArray b) {
    int n = Slice.length(state, a);
    Object[] elements = Built.elements(state, (long) n + Slice.length(state, b));
    copy(state, (Members) a, elements, 0);
    copy(state, (Members) b, elements, n);
    return new BuiltArray(elements, elements.length);
  }

  /** {@code array}'s elements into {@code out} from {@code at}, one tick each. */
  private static void copy(RunState state, Members array, Object[] out, int at) {
    for (int c = array.first(); c != -1; c = array.next(state, c)) {
      state.tick();
      out[at++] = array.member(state, c);
    }
  }
}
