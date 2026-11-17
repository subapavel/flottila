/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): binop_multiply in src/builtin.c.
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
import dev.rail8.jq.internal.value.ObjectMerge;
import dev.rail8.jq.query.run.Fn2;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;

/**
 * jq 1.8.2's {@code l * r} (jq-ref, recorded: diff/arith-kinds.rec, arith-repeat.rec, arith-containers.rec): two
 * numbers give their double product, a string and a number on either side the string repeated, two objects their
 * object merge; any other pair is jq's error.
 */
final class Multiply implements Fn2 {
  private static final String ERROR = "cannot be multiplied";

  @Override
  public Object apply(RunState state, Object in, Object l, Object r) {
    return switch (new Operands(l, r)) {
      case Operands(JqNumber a, JqNumber b) -> new DoubleNumber(Numbers.doubleValue(state, a) * Numbers.doubleValue(state, b));
      case Operands(JqString a, JqNumber b) -> Strings.repeat(state, a, Numbers.doubleValue(state, b));
      case Operands(JqNumber a, JqString b) -> Strings.repeat(state, b, Numbers.doubleValue(state, a));
      case Operands(JqObject a, JqObject b) -> ObjectMerge.merge(state, a, b);
      case Operands(JqNull a, JqNull b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNull a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNull a, JqNumber b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNull a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNull a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNull a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqNull b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqNumber b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqBool a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNumber a, JqNull b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNumber a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNumber a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqNumber a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqNull b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqString a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqNull b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqNumber b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqArray a, JqObject b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqNull b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqBool b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqNumber b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqString b) -> throw Arithmetic.cannot(state, a, b, ERROR);
      case Operands(JqObject a, JqArray b) -> throw Arithmetic.cannot(state, a, b, ERROR);
    };
  }
}
