/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): parse_slice and the slice of jv_get in src/jv_aux.c.
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

import io.github.subapavel.jqj.internal.json.ErrorDump;
import io.github.subapavel.jqj.internal.json.Printer;
import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn2;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;

/**
 * jq 1.8.2's {@code .[a:b]}, which is {@code jv_get} with the key {@code {"start": a, "end": b}} (jq-ref, recorded:
 * diff/slice.rec): on null, null; on an array its elements {@code [a, b)} as a {@link BuiltArray}, on a string its code
 * points {@code [a, b)}, each bound clamped as jq's {@code parse_slice} does; on any other kind jq's error, which shows
 * the key. A bound that is neither null nor a number is jq's error on an array or a string. The quiet variant
 * {@code .[a:b]?} drops either error (ADR-0034). A missing bound is a Const null.
 */
public final class Slice implements Fn2 {
  /** {@code .[a:b]}. */
  public static final Operation PLAIN = new Operation(".[a:b]", new Slice(false));

  /** {@code .[a:b]?}. */
  public static final Operation QUIET = new Operation(".[a:b]?", new Slice(true));

  /** What a bound's dump keeps at most: one byte more than {@link ErrorDump} shows whole. */
  private static final int HEAD = 30;

  /** Whether this is {@code .[a:b]?}, which drops its own error. */
  private final boolean quiet;

  private Slice(boolean quiet) {
    this.quiet = quiet;
  }

  @Override
  public Object apply(RunState state, Object in, Object a, Object b) {
    JqValue t = (JqValue) in;
    JqValue from = (JqValue) a;
    JqValue to = (JqValue) b;
    return switch (t) {
      case JqNull nul -> nul;
      case JqArray array -> sliced(of(state, array, from, to), quiet);
      case JqString s -> sliced(of(state, s, from, to), quiet);
      // One pattern per case: javac checks a case of several patterns again, a branch that is never taken.
      case JqBool _ -> cannotSlice(state, t, from, to);
      case JqNumber _ -> cannotSlice(state, t, from, to);
      case JqObject _ -> cannotSlice(state, t, from, to);
    };
  }

  /** jq's error for {@code t}, which has no slices, or no result for {@code .[a:b]?}. */
  private Object cannotSlice(RunState state, JqValue t, JqValue from, JqValue to) {
    if (quiet) {
      return CallForm.NONE;
    }
    throw NavigationErrors.cannotIndex(t, "object", shown(state, from, to));
  }

  /**
   * {@code slice}, or for Java null, a bound neither null nor a number, jq's error, which {@code quiet} drops (no
   * result).
   */
  static Object sliced(JqValue slice, boolean quiet) {
    if (slice != null) {
      return slice;
    }
    if (quiet) {
      return CallForm.NONE;
    }
    throw NavigationErrors.sliceIndices();
  }

  /** What jq's error shows of the key {@code {"start": from, "end": to}}, the bounds' proofs charged to {@code state}. */
  private static String shown(RunState state, JqValue from, JqValue to) {
    ByteArrayOutputStream key = new ByteArrayOutputStream();
    key.writeBytes(new byte[] {'{', '"', 's', 't', 'a', 'r', 't', '"', ':'});
    key.writeBytes(Printer.head(state, from, HEAD));
    key.writeBytes(new byte[] {',', '"', 'e', 'n', 'd', '"', ':'});
    key.writeBytes(Printer.head(state, to, HEAD));
    key.write('}');
    return ErrorDump.of(key.toByteArray(), '}');
  }

  /**
   * The elements {@code [from, to)} of {@code array}, or Java null when a bound is neither null nor a number; a Java
   * null bound, a key {@link Index} found missing, is neither.
   */
  static JqValue of(RunState state, JqArray array, JqValue from, JqValue to) {
    if (!isBound(from) || !isBound(to)) {
      return null;
    }
    return switch (array) {
      case ArrayView view -> {
        long range = bounds(view.length(state), from, to);
        yield view.slice(state, (int) (range >>> 32), (int) range);
      }
      case BuiltArray built -> {
        long range = bounds(built.length(), from, to);
        yield built.slice(state, (int) (range >>> 32), (int) range);
      }
    };
  }

  /** The code points {@code [from, to)} of {@code s}, or Java null when a bound is neither null nor a number. */
  static JqValue of(RunState state, JqString s, JqValue from, JqValue to) {
    if (!isBound(from) || !isBound(to)) {
      return null;
    }
    return string(state, s, from, to);
  }

  /** Whether {@code bound} is null or a number, which parse_slice takes; Java null, a missing key, is neither. */
  private static boolean isBound(JqValue bound) {
    // One pattern per case: javac checks a case of several patterns again, a branch that is never taken.
    return switch (bound) {
      case null -> false;
      case JqNull _ -> true;
      case JqNumber _ -> true;
      case JqBool _ -> false;
      case JqString _ -> false;
      case JqArray _ -> false;
      case JqObject _ -> false;
    };
  }

  /** The length of {@code array}: a view walks its tape and charges a tick per element (ADR-0034). */
  static int length(RunState state, JqArray array) {
    return switch (array) {
      case ArrayView view -> view.length(state);
      case BuiltArray built -> built.length();
    };
  }

  /**
   * The code points {@code [from, to)} of {@code s}, well-formed UTF-8 (ADR-0016), where a code point starts at each
   * byte that is no continuation byte. Counting them is a loop as long as the string, so it charges one tick per
   * started KiB, the writer's rate (TextFormat).
   */
  private static JqValue string(RunState state, JqString s, JqValue from, JqValue to) {
    MemorySegment utf8 = s.utf8();
    long bytes = utf8.byteSize();
    state.tick((int) ((bytes + 1023) >>> 10));
    int points = 0;
    for (long i = 0; i < bytes; i++) {
      if (starts(utf8, i)) {
        points++;
      }
    }
    long range = bounds(points, from, to);
    long first = offset(utf8, 0, (int) (range >>> 32));
    long end = offset(utf8, first, (int) range - (int) (range >>> 32));
    return BuiltString.of(utf8.asSlice(first, end - first).toArray(JAVA_BYTE));
  }

  /** Whether the byte at {@code i} starts a code point. */
  private static boolean starts(MemorySegment utf8, long i) {
    return (utf8.get(JAVA_BYTE, i) & 0xC0) != 0x80;
  }

  /** The byte offset {@code n} code points after the code point that starts at {@code from}. */
  private static long offset(MemorySegment utf8, long from, int n) {
    long at = from;
    long bytes = utf8.byteSize();
    for (int k = 0; k < n; k++) {
      do {
        at++;
      } while (at < bytes && !starts(utf8, at));
    }
    return at;
  }

  /**
   * jq's {@code parse_slice} over {@code length} elements: the start, then the end, each null or a number, as {@code
   * start << 32 | end} with {@code 0 <= start <= end <= length}. The start is 0 for null or NaN, counts from the end
   * when negative, and is clamped to {@code [0, length]}, then cut toward zero. The end is the length for null or NaN,
   * counts from the end when negative, is the start when still negative, is clamped to the length, and rounds up a
   * fraction below it; an end before the start is the start.
   */
  static long bounds(int length, JqValue from, JqValue to) {
    // A NaN start takes none of the three branches below, as every comparison with NaN is false, and the cast makes
    // it 0.
    double start = from instanceof JqNumber n ? n.doubleValue() : 0;
    if (start < 0) {
      start += length;
    }
    if (start < 0) {
      start = 0;
    }
    if (start > length) {
      start = length;
    }
    int s = (int) start;
    double end = to instanceof JqNumber n ? n.doubleValue() : length;
    if (Double.isNaN(end)) {
      end = length;
    }
    if (end < 0) {
      end += length;
    }
    if (end < 0) {
      end = s;
    }
    // The cast saturates, so an end past the int range is past the length.
    int e = (int) end;
    if (e > length) {
      e = length;
    }
    if (e < length && e < end) {
      e++;
    }
    if (e < s) {
      e = s;
    }
    return (long) s << 32 | e;
  }
}
