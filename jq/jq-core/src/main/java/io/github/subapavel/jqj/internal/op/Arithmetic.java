/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): type_error and type_error2 in src/builtin.c.
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

import io.github.subapavel.jqj.internal.json.ErrorDump;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqValue;

/**
 * jq 1.8.2's arithmetic operators (#65, ADR-0012, ADR-0034 amendment), the family's operations under jq's names, and
 * their error texts. A binary operator's arguments are its left and right operands; {@code -t} takes its input.
 */
public final class Arithmetic {
  /** {@code l + r}. */
  public static final Operation PLUS = new Operation("_plus", new Plus());

  /** {@code l - r}. */
  public static final Operation MINUS = new Operation("_minus", new Minus());

  /** {@code l * r}. */
  public static final Operation MULTIPLY = new Operation("_multiply", new Multiply());

  /** {@code l / r}. */
  public static final Operation DIVIDE = new Operation("_divide", new Divide());

  /** {@code l % r}. */
  public static final Operation MODULO = new Operation("_mod", new Modulo());

  /** {@code -t}, on each result of {@code t}. */
  public static final Operation NEGATE = new Operation("_negate", new Negate());

  private Arithmetic() {}

  /** jq's {@code type_error}: {@code v}'s kind and dump, its proofs charged to {@code state}, then {@code what}. */
  static FilterException cannot(RunState state, JqValue v, String what) {
    return error(NavigationErrors.kind(v) + " (" + ErrorDump.of(state, v) + ") " + what);
  }

  /** jq's {@code type_error2}: each operand's kind and dump, their proofs charged to {@code state}, then {@code what}. */
  static FilterException cannot(RunState state, JqValue a, JqValue b, String what) {
    return error(
        NavigationErrors.kind(a) + " (" + ErrorDump.of(state, a) + ") and " + NavigationErrors.kind(b) + " (" + ErrorDump.of(state, b) + ") " + what);
  }

  /** jq's error with the text {@code text}. */
  static FilterException error(String text) {
    return new FilterException(BuiltString.of(text));
  }
}
