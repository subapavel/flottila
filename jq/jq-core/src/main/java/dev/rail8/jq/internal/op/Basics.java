/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_length, f_type, f_tostring, f_dump (tojson),
 * f_json_parse (fromjson), f_tonumber and f_toboolean in src/builtin.c, and jv_parse_sized_custom_flags in
 * src/jv_parse.c.
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

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.nio.charset.StandardCharsets.UTF_8;

import dev.rail8.jq.JqSyntaxException;
import dev.rail8.jq.internal.Stub;
import dev.rail8.jq.internal.json.JsonReader;
import dev.rail8.jq.internal.json.TapeLimits;
import dev.rail8.jq.internal.value.ArrayView;
import dev.rail8.jq.internal.value.BuiltArray;
import dev.rail8.jq.internal.value.BuiltObject;
import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.Detach;
import dev.rail8.jq.internal.value.Document;
import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.internal.value.LiteralNumber;
import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.internal.value.KeyProof;
import dev.rail8.jq.internal.value.ObjectView;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.FilterException;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNull;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqObject;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.lang.foreign.MemorySegment;

/**
 * jq 1.8.2's natives of a value's basics, each an operation on its input: {@code length}, {@code type}, {@code
 * tostring}, {@code tojson}, {@code fromjson}, {@code tonumber} and {@code toboolean}. Each loop whose length depends
 * on the data ticks: {@code length} once per started KiB of a string or of a non-short literal number's token and as a
 * view's walk charges, {@code tonumber} and {@code fromjson} once per started KiB of their string, and {@code tostring}
 * and {@code tojson} as {@link TextFormat} does; the values they build are charged to bytes built.
 */
public final class Basics {
  /** {@code length}. */
  public static final Operation LENGTH = new Operation("length", (Fn0) Basics::length, Declared.FRESH);

  /** {@code type}. */
  public static final Operation TYPE = new Operation("type", (Fn0) (state, in) -> type((JqValue) in), Declared.FRESH);

  /** {@code tostring}: {@code @text}'s operation (TextFormat). */
  public static final Operation TOSTRING = new Operation("tostring", new TextFormat(), Declared.KEEPS);

  /** {@code tojson}. */
  public static final Operation TOJSON = new Operation("tojson", (Fn0) (state, in) -> TextFormat.dump(state, (JqValue) in), Declared.FRESH);

  /** {@code fromjson}. */
  public static final Operation FROMJSON = new Operation("fromjson", (Fn0) Basics::fromjson, Declared.FRESH);

  /** {@code tonumber}. */
  public static final Operation TONUMBER = new Operation("tonumber", (Fn0) Basics::tonumber, Declared.FRESH);

  /** {@code toboolean}. */
  public static final Operation TOBOOLEAN = new Operation("toboolean", (Fn0) Basics::toboolean, Declared.FRESH);

  /** {@code null | length}: jq's {@code jv_number(0)}. */
  private static final DoubleNumber ZERO = new DoubleNumber(0);

  /** jq's kind names, {@code jv_kind_name}: one built string each, shared, as a constant's is. */
  private static final BuiltString NULL = BuiltString.of("null");

  private static final BuiltString BOOLEAN = BuiltString.of("boolean");
  private static final BuiltString NUMBER = BuiltString.of("number");
  private static final BuiltString STRING = BuiltString.of("string");
  private static final BuiltString ARRAY = BuiltString.of("array");
  private static final BuiltString OBJECT = BuiltString.of("object");

  /** The texts {@code toboolean} reads. */
  private static final MemorySegment TRUE_TEXT = MemorySegment.ofArray("true".getBytes(UTF_8));

  private static final MemorySegment FALSE_TEXT = MemorySegment.ofArray("false".getBytes(UTF_8));

  private Basics() {}

  /**
   * {@code length}: null 0; a number its absolute value ({@link Numbers#abs}); a string its code points; an array its
   * elements, a view's counted by its walk; an object its distinct keys, a view's the size its key proof finds, which
   * charges each key entry; a boolean jq's "has no length".
   */
  private static Object length(RunState state, Object in) {
    JqValue v = (JqValue) in;
    return switch (v) {
      case JqNull _ -> ZERO;
      case JqBool _ -> throw Arithmetic.cannot(state, v, "has no length");
      case JqNumber n -> Numbers.abs(state, n);
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

  /**
   * {@code toboolean}: a boolean itself, the string {@code "true"} true and {@code "false"} false; any other value jq's
   * "cannot be parsed as a boolean". It reads at most six bytes and ticks nothing.
   */
  private static Object toboolean(RunState state, Object in) {
    JqValue v = (JqValue) in;
    if (v instanceof JqBool) {
      return v;
    }
    if (v instanceof JqString s) {
      MemorySegment utf8 = s.utf8();
      if (spells(utf8, TRUE_TEXT)) {
        return JqBool.TRUE;
      }
      if (spells(utf8, FALSE_TEXT)) {
        return JqBool.FALSE;
      }
    }
    throw Arithmetic.cannot(state, v, "cannot be parsed as a boolean");
  }

  /** Whether the bytes of {@code s} are those of {@code word}. */
  private static boolean spells(MemorySegment s, MemorySegment word) {
    return s.byteSize() == word.byteSize() && s.mismatch(word) == -1;
  }

  /**
   * {@code fromjson}, jq's {@code jv_parse_sized}: the one value of a string's JSON text, a BOM at its start skipped, as
   * the root of a document of its own, after one tick per started KiB of the text. It charges what {@link Detach#charge}
   * says the document's built copy would. A text of whitespace alone is jq's "Expected JSON value", a second value after
   * the first its "Unexpected extra JSON values", and a limit the reader's text; each adds " (while parsing '…')" with
   * the text up to its first NUL. A malformed value, a lenient token among them, ends the run with the reader's Stub.
   * Any other input is jq's "only strings can be parsed".
   */
  private static Object fromjson(RunState state, Object in) {
    JqValue v = (JqValue) in;
    if (!(v instanceof JqString s)) {
      throw Arithmetic.cannot(state, v, "only strings can be parsed");
    }
    MemorySegment text = s.utf8();
    long n = text.byteSize();
    state.tick(Built.kibibytes(n));
    JsonReader reader = new JsonReader(TapeLimits.ADR_0002);
    try {
      long after = reader.readFirst(text, 0);
      if (after < 0) {
        throw parsing("Expected JSON value", text);
      }
      Document doc = reader.last();
      long rest = after;
      while (rest < n && whitespace(text.get(JAVA_BYTE, rest))) {
        rest++;
      }
      if (rest == n) {
        state.chargeBuilt(Detach.charge(doc));
        return doc.root();
      }
      reader.readFirst(text, rest);
      throw parsing("Unexpected extra JSON values", text);
    } catch (JqSyntaxException e) {
      if (e.getMessage().startsWith(Stub.PREFIX)) {
        throw e;
      }
      throw parsing(e.getMessage(), text);
    }
  }

  /** Whether jq's parser skips {@code b} as whitespace: SP, TAB, LF or CR. */
  private static boolean whitespace(byte b) {
    return b == ' ' || b == '\t' || b == '\n' || b == '\r';
  }

  /** jq's {@code "%s (while parsing '%.*s')"}: {@code message}, then the text up to its first NUL. */
  private static FilterException parsing(String message, MemorySegment text) {
    long nul = 0;
    while (nul < text.byteSize() && text.get(JAVA_BYTE, nul) != 0) {
      nul++;
    }
    return Arithmetic.error(message + " (while parsing '" + new String(text.asSlice(0, nul).toArray(JAVA_BYTE), UTF_8) + "')");
  }
}
