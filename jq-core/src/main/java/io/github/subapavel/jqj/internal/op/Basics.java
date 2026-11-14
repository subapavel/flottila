/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_length, f_type, f_tostring, f_dump (tojson) and
 * f_tonumber in src/builtin.c.
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

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltObject;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.DoubleNumber;
import io.github.subapavel.jqj.internal.value.LiteralNumber;
import io.github.subapavel.jqj.internal.value.Numbers;
import io.github.subapavel.jqj.internal.value.KeyProof;
import io.github.subapavel.jqj.internal.value.ObjectView;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;

/**
 * jq 1.8.2's natives of a value's basics (#72; jq-ref, recorded: diff/natives.rec length-*, type-*, tostring-*,
 * tonumber-*), each an operation on its input: {@code length}, {@code type}, {@code tostring}, {@code tojson} and {@code
 * tonumber}. Each loop whose length depends on the data ticks (ADR-0008): {@code length} once per started KiB of a
 * string and as a view's walk charges (ADR-0034), {@code tonumber} once per started KiB of its string, and {@code
 * tostring} and {@code tojson} as {@link TextFormat} does; the strings they build are charged (ADR-0035).
 */
public final class Basics {
  /** {@code length}. */
  public static final Operation LENGTH = new Operation("length", (Fn0) Basics::length);

  /** {@code type}. */
  public static final Operation TYPE = new Operation("type", (Fn0) (state, in) -> type((JqValue) in));

  /** {@code tostring}: {@code @text}'s operation (TextFormat). */
  public static final Operation TOSTRING = new Operation("tostring", new TextFormat());

  /** {@code tojson}. */
  public static final Operation TOJSON = new Operation("tojson", (Fn0) (state, in) -> TextFormat.dump(state, (JqValue) in));

  /** {@code tonumber}. */
  public static final Operation TONUMBER = new Operation("tonumber", (Fn0) Basics::tonumber);

  /** {@code null | length}: jq's {@code jv_number(0)}. */
  private static final DoubleNumber ZERO = new DoubleNumber(0);

  /** jq's kind names, {@code jv_kind_name}: one built string each, shared, as a constant's is. */
  private static final BuiltString NULL = BuiltString.of("null");

  private static final BuiltString BOOLEAN = BuiltString.of("boolean");
  private static final BuiltString NUMBER = BuiltString.of("number");
  private static final BuiltString STRING = BuiltString.of("string");
  private static final BuiltString ARRAY = BuiltString.of("array");
  private static final BuiltString OBJECT = BuiltString.of("object");

  private Basics() {}

  /**
   * {@code length}: null 0; a number its absolute value ({@link Numbers#abs}); a string its code points; an array its
   * elements, a view's counted by its walk; an object its distinct keys, a view's the size its key proof finds, which
   * charges each key entry (ADR-0044); a boolean jq's "has no length".
   */
  private static Object length(RunState state, Object in) {
    JqValue v = (JqValue) in;
    return switch (v) {
      case JqNull _ -> ZERO;
      case JqBool _ -> throw Arithmetic.cannot(state, v, "has no length");
      case JqNumber n -> Numbers.abs(n);
      case JqString s -> new DoubleNumber(codePoints(state, s.utf8()));
      case ArrayView a -> new DoubleNumber(a.length(state));
      case BuiltArray a -> new DoubleNumber(a.length());
      case ObjectView o -> new DoubleNumber(KeyProof.walk(state, o, false).size());
      case BuiltObject o -> new DoubleNumber(o.size());
    };
  }

  /** The code points of the UTF-8 bytes {@code s}, jq's strings, well formed: the bytes that start one. */
  private static long codePoints(RunState state, MemorySegment s) {
    long n = s.byteSize();
    state.tick(Built.kibibytes(n));
    long count = 0;
    for (long i = 0; i < n; i++) {
      if ((s.get(JAVA_BYTE, i) & 0xC0) != 0x80) {
        count++;
      }
    }
    return count;
  }

  /** {@code type}: jq's name of the kind; it ticks and charges nothing. */
  private static BuiltString type(JqValue v) {
    return switch (v) {
      case JqNull _ -> NULL;
      case JqBool _ -> BOOLEAN;
      case JqNumber _ -> NUMBER;
      case JqString _ -> STRING;
      case JqArray _ -> ARRAY;
      case JqObject _ -> OBJECT;
    };
  }

  /**
   * {@code tonumber}: a number itself; a string the number {@link LiteralNumber#ofText} reads from all of it, after one
   * tick per started KiB of its bytes; any other value, or a string that is no number, jq's "cannot be parsed as a
   * number".
   */
  private static Object tonumber(RunState state, Object in) {
    JqValue v = (JqValue) in;
    if (v instanceof JqNumber) {
      return v;
    }
    if (v instanceof JqString s) {
      MemorySegment utf8 = s.utf8();
      state.tick(Built.kibibytes(utf8.byteSize()));
      JqNumber n = LiteralNumber.ofText(utf8.toArray(JAVA_BYTE));
      if (n != null) {
        return n;
      }
    }
    throw Arithmetic.cannot(state, v, "cannot be parsed as a number");
  }
}
