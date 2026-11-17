/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): jv_string_concat, jv_string_repeat and jv_string_split in
 * src/jv.c, and the repeat count of binop_multiply in src/builtin.c.
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

import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * The string operators of jq 1.8.2 (jq-ref, recorded: diff/arith-containers.rec, arith-repeat.rec, arith-divmod.rec):
 * {@code +} concatenates, {@code *} with a number repeats, {@code /} splits. Each works on the strings' UTF-8 bytes,
 * jq's string values (a view's are decoded), and charges what it builds before it allocates (ADR-0035 amendment).
 */
final class Strings {
  /**
   * jq's bound on a repeat's result, {@code INT_MAX - sizeof(jvp_string) / 2} bytes, past which it is "Repeat string
   * result too long" (jq-ref: 2147483638 bytes are built, arith-repeat/too-long-one-byte).
   */
  static final long REPEAT_BOUND = Integer.MAX_VALUE - 8;

  /** The pieces a split has room for before it grows its array. */
  private static final int PIECES = 8;

  private Strings() {}

  /** {@code a + b}: one tick per started KiB of the result (ADR-0034 amendment). */
  static BuiltString concat(RunState state, JqString a, JqString b) {
    MemorySegment x = a.utf8();
    MemorySegment y = b.utf8();
    long length = x.byteSize() + y.byteSize();
    state.tick(Built.kibibytes(length));
    byte[] out = Built.string(state, length);
    MemorySegment.copy(x, JAVA_BYTE, 0, out, 0, (int) x.byteSize());
    MemorySegment.copy(y, JAVA_BYTE, 0, out, (int) x.byteSize(), (int) y.byteSize());
    return BuiltString.of(out);
  }

  /**
   * {@code s * count} or {@code count * s}: null for a negative or NaN count, otherwise {@code s} as many times as the
   * count's integer part, at most {@code INT_MAX}; a result of {@link #REPEAT_BOUND} bytes or more is jq's error,
   * raised before anything is charged. It is a concatenation of the copies, so it ticks as one.
   */
  static JqValue repeat(RunState state, JqString s, double count) {
    if (!(count >= 0)) {
      return JqNull.NULL;
    }
    MemorySegment x = s.utf8();
    // The cast cuts toward zero and saturates at INT_MAX, as jq's count does.
    long length = x.byteSize() * (int) count;
    if (length >= REPEAT_BOUND) {
      throw Arithmetic.error("Repeat string result too long");
    }
    state.tick(Built.kibibytes(length));
    byte[] out = Built.string(state, length);
    int copied = (int) Math.min(x.byteSize(), length);
    MemorySegment.copy(x, JAVA_BYTE, 0, out, 0, copied);
    // Each copy doubles what is there, as jq's loop does.
    for (int at = copied; at < length; ) {
      int grow = Math.min(at, out.length - at);
      System.arraycopy(out, 0, out, at, grow);
      at += grow;
    }
    return BuiltString.of(out);
  }

  /**
   * {@code s / separator}, jq's {@code jv_string_split}: an empty separator gives each character of {@code s}; any
   * other gives the pieces between its occurrences, found left to right without overlap, and one empty piece more
   * when {@code s} ends with it; an empty {@code s} gives no piece. The array is charged as a container, each piece as
   * an element and a string before it is built (ADR-0035 amendment). The search ticks once per started KiB of
   * {@code s} times the started KiB of the separator, which counts as one at least, as an empty one does; each piece
   * ticks once more (ADR-0034 amendment).
   */
  static BuiltArray split(RunState state, JqString s, JqString separator) {
    MemorySegment str = s.utf8();
    MemorySegment sep = separator.utf8();
    long n = str.byteSize();
    long m = sep.byteSize();
    // The product can pass an int (64 MiB searched for a 33 MiB separator); the charge saturates at Integer.MAX_VALUE.
    state.tick((int) Math.min((long) Built.kibibytes(n) * Math.max(1, Built.kibibytes(m)), Integer.MAX_VALUE));
    state.chargeBuilt(Charges.CONTAINER);
    Pieces pieces = new Pieces();
    if (m == 0) {
      for (long p = 0; p < n; ) {
        long next = p + length(str.get(JAVA_BYTE, p));
        pieces.add(state, str, p, next);
        p = next;
      }
    } else {
      for (long p = 0; p < n; ) {
        long at = indexOf(str, sep, p);
        pieces.add(state, str, p, at);
        if (at + m == n) {
          pieces.add(state, str, n, n);
        }
        p = at + m;
      }
    }
    return new BuiltArray(pieces.elements, pieces.count);
  }

  /**
   * The length of the UTF-8 sequence that {@code lead} starts: its leading one bits, or 1 for none. The bytes are jq's
   * string, well-formed UTF-8, so {@code lead} is never a continuation byte.
   */
  private static int length(byte lead) {
    int ones = Integer.numberOfLeadingZeros(~(lead << 24));
    return ones + ((ones - 1) >>> 31);
  }

  /** The first index from {@code from} at which {@code sep}, not empty, occurs in {@code str}, or its length. */
  private static long indexOf(MemorySegment str, MemorySegment sep, long from) {
    long n = str.byteSize();
    long m = sep.byteSize();
    for (long i = from; i + m <= n; i++) {
      if (MemorySegment.mismatch(str, i, i + m, sep, 0, m) == -1) {
        return i;
      }
    }
    return n;
  }

  /** A split's pieces so far. */
  private static final class Pieces {
    Object[] elements = new Object[PIECES];
    int count;

    /** The piece {@code str[from, to)}: a tick, then its element and string charged before it is built. */
    void add(RunState state, MemorySegment str, long from, long to) {
      state.tick();
      state.chargeBuilt(Charges.ELEMENT + Charges.STRING + (to - from));
      byte[] piece = new byte[(int) (to - from)];
      MemorySegment.copy(str, JAVA_BYTE, from, piece, 0, piece.length);
      if (count == elements.length) {
        elements = Arrays.copyOf(elements, count << 1);
      }
      elements[count++] = BuiltString.of(piece);
    }
  }
}
