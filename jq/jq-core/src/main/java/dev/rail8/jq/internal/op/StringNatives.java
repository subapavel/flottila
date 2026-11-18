/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_startswith, f_endswith, f_utf8bytelength, string_trim,
 * f_string_explode, f_string_implode, f_string_split and f_string_indexes in src/builtin.c, jv_string_explode and
 * jv_string_indexes in src/jv.c, and jvp_codepoint_is_whitespace in src/jv_unicode.c.
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

import dev.rail8.jq.internal.value.BuiltString;
import dev.rail8.jq.internal.value.DoubleNumber;
import dev.rail8.jq.internal.value.Members;
import dev.rail8.jq.internal.value.Numbers;
import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.Fn1;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqBool;
import dev.rail8.jq.value.JqNumber;
import dev.rail8.jq.value.JqString;
import dev.rail8.jq.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * jq 1.8.2's string natives written in C: {@code startswith}, {@code endswith}, {@code utf8bytelength}, {@code trim},
 * {@code ltrim}, {@code rtrim}, {@code explode}, {@code implode}, {@code split/1} and {@code _strindices}. They work
 * on the strings' UTF-8 bytes, which are well formed. The byte loops tick once per started KiB of their input string;
 * {@code explode} and {@code implode} once per code point; {@code split} and {@code _strindices} at the rate of
 * {@link Strings#split}, {@code _strindices} once more per index. What they build is charged before it is allocated.
 */
public final class StringNatives {
  /** {@code startswith(s)}. */
  public static final Operation STARTSWITH =
      new Operation("startswith", (Fn1) (state, in, s) -> startswith(state, (JqValue) in, (JqValue) s));

  /** {@code endswith(s)}. */
  public static final Operation ENDSWITH =
      new Operation("endswith", (Fn1) (state, in, s) -> endswith(state, (JqValue) in, (JqValue) s));

  /** {@code utf8bytelength}. */
  public static final Operation UTF8BYTELENGTH =
      new Operation("utf8bytelength", (Fn0) (state, in) -> utf8bytelength(state, (JqValue) in));

  /** {@code trim}. */
  public static final Operation TRIM = new Operation("trim", (Fn0) (state, in) -> trim(state, (JqValue) in, true, true));

  /** {@code ltrim}. */
  public static final Operation LTRIM = new Operation("ltrim", (Fn0) (state, in) -> trim(state, (JqValue) in, true, false));

  /** {@code rtrim}. */
  public static final Operation RTRIM = new Operation("rtrim", (Fn0) (state, in) -> trim(state, (JqValue) in, false, true));

  /** {@code explode}. */
  public static final Operation EXPLODE = new Operation("explode", (Fn0) (state, in) -> explode(state, (JqValue) in));

  /** {@code implode}. */
  public static final Operation IMPLODE = new Operation("implode", (Fn0) (state, in) -> implode(state, (JqValue) in));

  /** {@code split(s)}, one argument: the separator. */
  public static final Operation SPLIT = new Operation("split", (Fn1) (state, in, s) -> split(state, (JqValue) in, (JqValue) s));

  /** {@code _strindices(s)}. */
  public static final Operation STRINDICES =
      new Operation("_strindices", (Fn1) (state, in, s) -> strindices(state, (JqValue) in, (JqValue) s));

  /** U+FFFD, what {@code implode} writes for a number that is no Unicode scalar value. */
  private static final int REPLACEMENT = 0xFFFD;

  private StringNatives() {}

  /**
   * {@code a | startswith(b)}: whether the bytes of {@code b} begin {@code a}; two strings or jq's error, which names
   * neither value.
   */
  private static JqBool startswith(RunState state, JqValue a, JqValue b) {
    if (!(a instanceof JqString s) || !(b instanceof JqString t)) {
      throw Arithmetic.error("startswith() requires string inputs");
    }
    MemorySegment x = s.utf8();
    MemorySegment y = t.utf8();
    state.tick(Built.kibibytes(x.byteSize()));
    long m = y.byteSize();
    return bool(m <= x.byteSize() && MemorySegment.mismatch(x, 0, m, y, 0, m) == -1);
  }

  /**
   * {@code a | endswith(b)}: whether the bytes of {@code b} end {@code a}; two strings or jq's error, which names
   * neither value.
   */
  private static JqBool endswith(RunState state, JqValue a, JqValue b) {
    if (!(a instanceof JqString s) || !(b instanceof JqString t)) {
      throw Arithmetic.error("endswith() requires string inputs");
    }
    MemorySegment x = s.utf8();
    MemorySegment y = t.utf8();
    state.tick(Built.kibibytes(x.byteSize()));
    long n = x.byteSize();
    long m = y.byteSize();
    return bool(m <= n && MemorySegment.mismatch(x, n - m, n, y, 0, m) == -1);
  }

  /** {@code utf8bytelength}: a string's byte count; any other value jq's "only strings have UTF-8 byte length". */
  private static DoubleNumber utf8bytelength(RunState state, JqValue v) {
    if (!(v instanceof JqString s)) {
      throw Arithmetic.cannot(state, v, "only strings have UTF-8 byte length");
    }
    long n = s.utf8().byteSize();
    state.tick(Built.kibibytes(n));
    return new DoubleNumber(n);
  }

  /**
   * jq's {@code string_trim}: the string without the whitespace code points ({@link #whitespace}) at its start when
   * {@code left}, at its end when {@code right}. A string with nothing to trim is the input itself, as jq returns it;
   * any other value is jq's "trim input must be a string".
   */
  private static JqString trim(RunState state, JqValue v, boolean left, boolean right) {
    if (!(v instanceof JqString s)) {
      throw Arithmetic.error("trim input must be a string");
    }
    MemorySegment str = s.utf8();
    long n = str.byteSize();
    state.tick(Built.kibibytes(n));
    long start = 0;
    long end = n;
    if (left) {
      while (start < end) {
        int length = Utf8.length(str.get(JAVA_BYTE, start));
        if (!whitespace(Utf8.decode(str, start, length))) {
          break;
        }
        start += length;
      }
    }
    if (right) {
      while (end > start) {
        long at = end - 1;
        while ((str.get(JAVA_BYTE, at) & 0xC0) == 0x80) {
          at--;
        }
        if (!whitespace(Utf8.decode(str, at, (int) (end - at)))) {
          break;
        }
        end = at;
      }
    }
    if (start == 0 && end == n) {
      return s;
    }
    byte[] out = Built.string(state, end - start);
    MemorySegment.copy(str, JAVA_BYTE, start, out, 0, out.length);
    return BuiltString.of(out);
  }

  /** jq's {@code jvp_codepoint_is_whitespace}: Unicode's White_Space code points. */
  private static boolean whitespace(int c) {
    return (c >= 0x0009 && c <= 0x000D)
        || c == 0x0020
        || c == 0x0085
        || c == 0x00A0
        || c == 0x1680
        || (c >= 0x2000 && c <= 0x200A)
        || c == 0x2028
        || c == 0x2029
        || c == 0x202F
        || c == 0x205F
        || c == 0x3000;
  }

  /**
   * {@code explode}: the code points of a string as an array of numbers, one tick and one element charge each; any
   * other value is jq's "explode input must be a string".
   */
  private static JqArray explode(RunState state, JqValue v) {
    if (!(v instanceof JqString s)) {
      throw Arithmetic.error("explode input must be a string");
    }
    MemorySegment str = s.utf8();
    long n = str.byteSize();
    Built.Elements points = new Built.Elements(state);
    for (long p = 0; p < n; ) {
      int length = Utf8.length(str.get(JAVA_BYTE, p));
      state.tick();
      points.add(new DoubleNumber(Utf8.decode(str, p, length)));
      p += length;
    }
    return points.array();
  }

  /**
   * {@code implode}: the string of an array's code points, one tick each. A number is cut toward zero; one below zero,
   * above U+10FFFF or a surrogate is U+FFFD. An element that is no number, or NaN, is jq's "can't be imploded, unicode
   * codepoint needs to be numeric"; an input that is no array jq's "implode input must be an array".
   */
  private static BuiltString implode(RunState state, JqValue v) {
    if (!(v instanceof JqArray array)) {
      throw Arithmetic.error("implode input must be an array");
    }
    Members members = (Members) array;
    int[] points = new int[8];
    int count = 0;
    long bytes = 0;
    for (int c = members.first(); c != -1; c = members.next(state, c)) {
      state.tick();
      JqValue element = members.member(state, c);
      double d = element instanceof JqNumber number ? Numbers.doubleValue(state, number) : Double.NaN;
      if (Double.isNaN(d)) {
        throw Arithmetic.cannot(state, element, "can't be imploded, unicode codepoint needs to be numeric");
      }
      int point = (int) d;
      if (point < 0 || point > 0x10FFFF || (point >= 0xD800 && point <= 0xDFFF)) {
        point = REPLACEMENT;
      }
      if (count == points.length) {
        points = Arrays.copyOf(points, count << 1);
      }
      points[count++] = point;
      bytes += Utf8.encodedLength(point);
    }
    byte[] out = Built.string(state, bytes);
    int at = 0;
    for (int i = 0; i < count; i++) {
      at = Utf8.encode(points[i], out, at);
    }
    return BuiltString.of(out);
  }

  /**
   * {@code split(s)} with one argument: {@link Strings#split}, as the {@code /} operator splits; two strings or jq's
   * error, which names neither value.
   */
  private static JqArray split(RunState state, JqValue a, JqValue b) {
    if (!(a instanceof JqString s) || !(b instanceof JqString separator)) {
      throw Arithmetic.error("split input and separator must be strings");
    }
    return Strings.split(state, s, separator);
  }

  /**
   * jq's {@code jv_string_indexes}: the code point index of every place where the bytes of {@code b} occur in
   * {@code a}, each search one byte past the last place found, so places may overlap; an empty {@code b} occurs
   * nowhere. An input that is no string is jq's "cannot be searched, as it is not a string", then an argument that is
   * none its "is not a string".
   */
  private static JqArray strindices(RunState state, JqValue a, JqValue b) {
    if (!(a instanceof JqString s)) {
      throw Arithmetic.cannot(state, a, "cannot be searched, as it is not a string");
    }
    if (!(b instanceof JqString t)) {
      throw Arithmetic.cannot(state, b, "is not a string");
    }
    MemorySegment str = s.utf8();
    MemorySegment sub = t.utf8();
    long n = str.byteSize();
    long m = sub.byteSize();
    state.tick((int) Math.min((long) Built.kibibytes(n) * Math.max(1, Built.kibibytes(m)), Integer.MAX_VALUE));
    Built.Elements indices = new Built.Elements(state);
    if (m == 0) {
      return indices.array();
    }
    long counted = 0;
    long index = 0;
    for (long p = 0; p + m <= n; p++) {
      if (MemorySegment.mismatch(str, p, p + m, sub, 0, m) != -1) {
        continue;
      }
      for (; counted < p; counted++) {
        if ((str.get(JAVA_BYTE, counted) & 0xC0) != 0x80) {
          index++;
        }
      }
      state.tick();
      indices.add(new DoubleNumber(index));
    }
    return indices.array();
  }

  private static JqBool bool(boolean b) {
    return b ? JqBool.TRUE : JqBool.FALSE;
  }

  /** Well-formed UTF-8, as jq's strings hold it. */
  private static final class Utf8 {
    private Utf8() {}

    /** The length of the sequence that {@code lead} starts: its leading one bits, or 1 for none. */
    static int length(byte lead) {
      int ones = Integer.numberOfLeadingZeros(~(lead << 24));
      return ones + ((ones - 1) >>> 31);
    }

    /** The code point of the {@code length} bytes at {@code at}. */
    static int decode(MemorySegment s, long at, int length) {
      int lead = s.get(JAVA_BYTE, at) & 0xFF;
      int c = length == 1 ? lead : lead & (0x7F >>> length);
      for (int i = 1; i < length; i++) {
        c = (c << 6) | (s.get(JAVA_BYTE, at + i) & 0x3F);
      }
      return c;
    }

    /** The bytes {@code c} takes. */
    static int encodedLength(int c) {
      return c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
    }

    /** Writes {@code c} at {@code at} of {@code out}; the index after it. */
    static int encode(int c, byte[] out, int at) {
      if (c < 0x80) {
        out[at] = (byte) c;
        return at + 1;
      }
      if (c < 0x800) {
        out[at] = (byte) (0xC0 | (c >>> 6));
        out[at + 1] = (byte) (0x80 | (c & 0x3F));
        return at + 2;
      }
      if (c < 0x10000) {
        out[at] = (byte) (0xE0 | (c >>> 12));
        out[at + 1] = (byte) (0x80 | ((c >>> 6) & 0x3F));
        out[at + 2] = (byte) (0x80 | (c & 0x3F));
        return at + 3;
      }
      out[at] = (byte) (0xF0 | (c >>> 18));
      out[at + 1] = (byte) (0x80 | ((c >>> 12) & 0x3F));
      out[at + 2] = (byte) (0x80 | ((c >>> 6) & 0x3F));
      out[at + 3] = (byte) (0x80 | (c & 0x3F));
      return at + 4;
    }
  }
}
