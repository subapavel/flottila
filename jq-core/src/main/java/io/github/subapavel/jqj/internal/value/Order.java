/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): jvp_cmp, string_cmp and jv_keys in src/jv_aux.c.
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
package io.github.subapavel.jqj.internal.value;

import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqBool;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqNumber;
import io.github.subapavel.jqj.value.JqObject;
import io.github.subapavel.jqj.value.JqString;
import io.github.subapavel.jqj.value.JqValue;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * jq 1.8.2's {@code jv_cmp}, the value order every orderer uses (ADR-0037, ADR-0029; GLOSSARY "Value order",
 * "Comparison depth"). Each pair of values lies at a depth, the outermost at 0, and a pair deeper than
 * {@link #MAX_DEPTH} raises jq's catchable "Comparison too deep" before anything else is compared. Then the kinds
 * decide, null below false below true below numbers below strings below arrays below objects; within a kind numbers
 * compare by {@link Numbers}, a NaN side read as null at the same depth; strings by their UTF-8 bytes, unsigned, then
 * by length; arrays element by element, each pair one level deeper, then by length; objects by their key lists, sorted
 * by the same string order, as an array one level deeper whose keys lie two levels deeper, and then by their values in
 * that sorted order, one level deeper. There is no identity check. The walk keeps its open containers on a stack of its
 * own, never in Java frames. It charges one tick per member pair it compares, one per key it collects from each
 * object, and one per started KiB of the shorter of two strings it compares, a key pair included, and the number
 * compare's own; the member walks charge theirs, a view's key proof included (ADR-0044); the sort of a key list
 * charges nothing, and nothing is charged to
 * bytes built (ADR-0037, design approval O1).
 */
public final class Order {
  /** jq's {@code MAX_CMP_DEPTH}. */
  static final int MAX_DEPTH = 10_000;

  private static final String TOO_DEEP = "Comparison too deep";

  /** jq's {@code string_cmp}, uncharged: the sort of a key list. Two keys of one object's walk are never equal. */
  private static final Comparator<Key> BY_BYTES = (p, q) -> bytes(p.utf8(), q.utf8());

  private Order() {}

  /**
   * The order of {@code a} and {@code b}: negative when {@code a} comes first, 0 when neither does, positive otherwise.
   *
   * @throws FilterException "Comparison too deep", jq's error
   */
  public static int compare(RunState state, JqValue a, JqValue b) {
    JqValue x = a;
    JqValue y = b;
    int depth = 0;
    Walk open = null;
    for (;;) {
      if (depth > MAX_DEPTH) {
        throw tooDeep();
      }
      int r = Integer.compare(kind(x), kind(y));
      if (r == 0) {
        switch (x) {
          case JqNull _ -> {
            // There is one null.
          }
          case JqBool _ -> {
            // The kind is the value: true and false are two kinds.
          }
          case JqNumber n -> r = numbers(state, n, (JqNumber) y);
          case JqString s -> r = strings(state, s.utf8(), ((JqString) y).utf8());
          case JqArray p -> open = new Elements((Members) p, (Members) y, depth + 1, open);
          case JqObject p -> {
            Members pm = KeyProof.members(state, p);
            List<Key> left = keys(state, pm);
            Members ym = KeyProof.members(state, (JqObject) y);
            List<Key> right = keys(state, ym);
            r = keyLists(state, left, right, depth + 1);
            if (r == 0) {
              open = new Values(pm, left, ym, right, depth + 1, open);
            }
          }
        }
      }
      if (r != 0) {
        return r;
      }
      // The pair is equal: the next pair of the innermost open container, or that container's own order once it has
      // none left, which decides when it is not 0.
      for (;;) {
        if (open == null) {
          return 0;
        }
        if (open.next(state)) {
          state.tick();
          x = open.x;
          y = open.y;
          depth = open.depth;
          break;
        }
        r = open.rest();
        open = open.outer;
        if (r != 0) {
          return r;
        }
      }
    }
  }

  private static FilterException tooDeep() {
    return new FilterException(BuiltString.of(TOO_DEEP));
  }

  /** jq's kind numbers, in the value order: true and false are two kinds. */
  private static int kind(JqValue v) {
    return switch (v) {
      case JqNull _ -> 1;
      case JqBool b -> b.value() ? 3 : 2;
      case JqNumber _ -> 4;
      case JqString _ -> 5;
      case JqArray _ -> 6;
      case JqObject _ -> 7;
    };
  }

  /**
   * Two numbers: a NaN side is read as null, so it lies below the other number, a NaN included; any other pair by
   * {@link Numbers#compare(RunState, JqNumber, JqNumber)}, which gives 1, as jq's null on the right does, when only the
   * right side is NaN: that side is no literal, so the pair compares as doubles, uncharged. A literal is never NaN.
   */
  private static int numbers(RunState state, JqNumber n, JqNumber m) {
    if (n instanceof DoubleNumber d && Double.isNaN(d.doubleValue())) {
      return -1;
    }
    return Numbers.compare(state, n, m);
  }

  /** Two strings by {@link #bytes}: one tick per started KiB of the shorter, whose every byte may be compared. */
  private static int strings(RunState state, MemorySegment a, MemorySegment b) {
    state.tick((int) ((Math.min(a.byteSize(), b.byteSize()) + 1023) >>> 10));
    return bytes(a, b);
  }

  /**
   * jq's {@code string_cmp}: the first byte that differs, unsigned, within the shorter length, then the lengths. It
   * charges nothing; {@code keys} charges its own sort (Keys).
   */
  public static int bytes(MemorySegment a, MemorySegment b) {
    long n = Math.min(a.byteSize(), b.byteSize());
    long at = MemorySegment.mismatch(a, 0, n, b, 0, n);
    if (at == -1) {
      return Long.compare(a.byteSize(), b.byteSize());
    }
    return Integer.compare(Byte.toUnsignedInt(a.get(ValueLayout.JAVA_BYTE, at)), Byte.toUnsignedInt(b.get(ValueLayout.JAVA_BYTE, at)));
  }

  /** One key of an object: its UTF-8 bytes, read once, and its cursor in the object's walk. */
  private record Key(MemorySegment utf8, int cursor) {}

  /**
   * jq's {@code jv_keys}: the keys of {@code object}, one tick per key collected, sorted by {@link #BY_BYTES} at no
   * charge. The list is scratch, never charged to bytes built.
   */
  private static List<Key> keys(RunState state, Members object) {
    Keyed keyed = (Keyed) object;
    List<Key> keys = new ArrayList<>();
    for (int c = object.first(); c != -1; c = object.next(state, c)) {
      state.tick();
      keys.add(new Key(keyed.key(c).utf8(), c));
    }
    keys.sort(BY_BYTES);
    return keys;
  }

  /**
   * Two sorted key lists, compared as jq compares them, two arrays at {@code depth}: the depth checked first, then key
   * by key, each pair of strings at {@code depth + 1}, then the lengths. A key pair is a string pair, not a member
   * pair: it charges its KiB only.
   */
  private static int keyLists(RunState state, List<Key> left, List<Key> right, int depth) {
    if (depth > MAX_DEPTH) {
      throw tooDeep();
    }
    int n = Math.min(left.size(), right.size());
    if (n > 0 && depth + 1 > MAX_DEPTH) {
      throw tooDeep();
    }
    for (int i = 0; i < n; i++) {
      int r = strings(state, left.get(i).utf8(), right.get(i).utf8());
      if (r != 0) {
        return r;
      }
    }
    return Integer.compare(left.size(), right.size());
  }

  /** One open pair of containers: the pairs of members it gives, one at a time, each at {@link #depth}. */
  private abstract static class Walk {
    final int depth;

    /** The walk around this one, or null for the outermost. */
    final Walk outer;

    JqValue x;
    JqValue y;

    Walk(int depth, Walk outer) {
      this.depth = depth;
      this.outer = outer;
    }

    /** Whether {@link #x} and {@link #y} hold the next pair; false when none is left. */
    abstract boolean next(RunState state);

    /** The containers' order once every pair was equal. */
    abstract int rest();
  }

  /** Two arrays, element by element; once either has none left, the one with elements left comes after. */
  private static final class Elements extends Walk {
    private final Members a;
    private final Members b;
    private int ca;
    private int cb;

    Elements(Members a, Members b, int depth, Walk outer) {
      super(depth, outer);
      this.a = a;
      this.b = b;
      this.ca = a.first();
      this.cb = b.first();
    }

    @Override
    boolean next(RunState state) {
      if (ca == -1 || cb == -1) {
        return false;
      }
      x = a.member(state, ca);
      y = b.member(state, cb);
      ca = a.next(state, ca);
      cb = b.next(state, cb);
      return true;
    }

    @Override
    int rest() {
      // jq's b_done - a_done.
      return Boolean.compare(ca != -1, cb != -1);
    }
  }

  /** Two objects of one key list: their values in the sorted key order, each key's at its own cursor in each walk. */
  private static final class Values extends Walk {
    private final Members a;
    private final List<Key> left;
    private final Members b;
    private final List<Key> right;
    private int i;

    Values(Members a, List<Key> left, Members b, List<Key> right, int depth, Walk outer) {
      super(depth, outer);
      this.a = a;
      this.left = left;
      this.b = b;
      this.right = right;
    }

    @Override
    boolean next(RunState state) {
      if (i == left.size()) {
        return false;
      }
      x = a.member(state, left.get(i).cursor());
      y = b.member(state, right.get(i).cursor());
      i++;
      return true;
    }

    @Override
    int rest() {
      return 0;
    }
  }
}
