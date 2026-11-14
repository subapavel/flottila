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
package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.DoubleNumber;
import io.github.subapavel.jqj.internal.value.ObjectMerge;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;

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
      case Operands(JqNumber a, JqNumber b) -> new DoubleNumber(a.doubleValue() * b.doubleValue());
      case Operands(JqString a, JqNumber b) -> Strings.repeat(state, a, b.doubleValue());
      case Operands(JqNumber a, JqString b) -> Strings.repeat(state, b, a.doubleValue());
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
