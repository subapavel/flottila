/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): jvp_contains, jvp_array_contains and jvp_object_contains in
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
package dev.rail8.jq.internal.value;

import dev.rail8.jq.query.run.FilterException;
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
 * jq 1.8.2's {@code jv_contains}: whether {@code a} contains {@code b}. Each pair of values lies at a depth, the
 * outermost at 0, and a pair deeper than {@link #MAX_DEPTH} raises jq's catchable "Containment check too deep" before
 * anything else is compared. Then two kinds differ (true and false are two kinds) and the pair is not contained; two
 * strings are when the bytes of {@code b} occur in those of {@code a}, which an empty {@code b} always does; two arrays
 * when each element of {@code b}, in order, is contained by some element of {@code a}, tried in order up to the first
 * that contains it, each pair one level deeper; two objects when each key of {@code b}, in its key order, is a key of
 * {@code a} whose value contains {@code b}'s, one level deeper; any other pair when {@link Equality} finds it equal.
 *
 * <p>The walk keeps its open containers on a stack of its own, never in Java frames. It charges one tick per member
 * pair it compares, Equality's ticks for each pair of scalars, and for two strings one per started KiB of {@code a}
 * times the started KiB of {@code b}, at least one, the rate of the split operator's search; the member walks charge
 * theirs, a view's key proofs included: {@code b}'s walk, and a read {@code a}'s {@link KeyIndex}, which looks each key
 * of {@code b} up once. A built {@code a} charges its lookups.
 */
public final class Containment {
  /** jq's {@code MAX_CONTAINS_DEPTH}. */
  static final int MAX_DEPTH = 10_000;

  private static final String TOO_DEEP = "Containment check too deep";

  private Containment() {}

  /**
   * Whether {@code a} contains {@code b}, as jq's {@code jv_contains} tells; the caller has checked that their kinds are
   * one.
   *
   * @throws FilterException "Containment check too deep", jq's error
   */
  public static boolean contains(RunState state, JqValue a, JqValue b) {
    JqValue x = a;
    JqValue y = b;
    int depth = 0;
    Walk open = null;
    for (;;) {
      if (depth > MAX_DEPTH) {
        throw new FilterException(BuiltString.of(TOO_DEEP));
      }
      Walk inner = null;
      boolean contained =
          switch (x) {
            case JqNull _ -> y instanceof JqNull;
            case JqBool _ -> x == y;
            case JqNumber _ -> y instanceof JqNumber && Equality.equal(state, x, y);
            case JqString s -> y instanceof JqString t && occurs(state, s, t);
            case JqArray p -> {
              if (!(y instanceof JqArray q)) {
                yield false;
              }
              inner = new Elements((Members) p, (Members) q, depth + 1);
              yield true;
            }
            case JqObject p -> {
              if (!(y instanceof JqObject q)) {
                yield false;
              }
              Members keys = KeyProof.members(state, q);
              inner =
                  switch (p) {
                    case ObjectView view -> new Indexed(keys, KeyProof.index(state, view, keys), depth + 1);
                    case BuiltObject built -> new Values(keys, built, depth + 1);
                  };
              yield true;
            }
          };
      int result = contained ? Walk.TRUE : Walk.FALSE;
      if (inner != null) {
        inner.outer = open;
        open = inner;
        result = Walk.START;
      }
      for (;;) {
        if (open == null) {
          return result == Walk.TRUE;
        }
        result = open.next(state, result);
        if (result == Walk.PAIR) {
          state.tick();
          x = open.x;
          y = open.y;
          depth = open.depth;
          break;
        }
        open = open.outer;
      }
    }
  }

  /**
   * Whether the bytes of {@code t} occur in those of {@code s}: one tick per started KiB of {@code s} times the started
   * KiB of {@code t}, at least one.
   */
  private static boolean occurs(RunState state, JqString s, JqString t) {
    MemorySegment str = s.utf8();
    MemorySegment sub = t.utf8();
    long n = str.byteSize();
    long m = sub.byteSize();
    state.tick((int) Math.min(((n + 1023) >>> 10) * Math.max(1, (m + 1023) >>> 10), Integer.MAX_VALUE));
    for (long p = 0; p + m <= n; p++) {
      if (MemorySegment.mismatch(str, p, p + m, sub, 0, m) == -1) {
        return true;
      }
    }
    return false;
  }

  /**
   * One open pair of containers: the pairs of members it gives, one at a time, each at {@link #depth}, and in the end
   * whether {@code a}'s container contains {@code b}'s.
   */
  private abstract static class Walk {
    /** For {@link #next}: no pair was given yet. */
    static final int START = 0;

    /** For and from {@link #next}: the last pair, or the containers, are contained. */
    static final int TRUE = 1;

    /** For and from {@link #next}: the last pair, or the containers, are not contained. */
    static final int FALSE = 2;

    /** From {@link #next}: {@link #x} and {@link #y} are the next pair. */
    static final int PAIR = 3;

    final int depth;

    /** The walk around this one, or null for the outermost. */
    Walk outer;

    /** The next pair's member of {@code a}'s container. */
    JqValue x;

    /** The next pair's member of {@code b}'s container. */
    JqValue y;

    Walk(int depth) {
      this.depth = depth;
    }

    /** After {@code last}, {@link #START} or the last pair's result: {@link #PAIR}, or the containers' result. */
    abstract int next(RunState state, int last);
  }

  /** jq's {@code jvp_array_contains}: each element of {@code b}, against each element of {@code a} until one contains it. */
  private static final class Elements extends Walk {
    private final Members a;
    private final Members b;
    private int ca;
    private int cb;

    Elements(Members a, Members b, int depth) {
      super(depth);
      this.a = a;
      this.b = b;
    }

    @Override
    int next(RunState state, int last) {
      if (last == FALSE) {
        ca = a.next(state, ca);
      } else {
        cb = last == START ? b.first() : b.next(state, cb);
        if (cb == -1) {
          return TRUE;
        }
        y = b.member(state, cb);
        ca = a.first();
      }
      if (ca == -1) {
        return FALSE;
      }
      x = a.member(state, ca);
      return PAIR;
    }
  }

  /**
   * jq's {@code jvp_object_contains} in a built {@code a}: each key of {@code b} in its key order with its value, and
   * {@code a}'s value of the same key; a key {@code a} lacks, or a value that does not contain {@code b}'s, makes the
   * objects not contained.
   */
  private static final class Values extends Walk {
    private final Members keys;
    private final BuiltObject a;
    private int cursor;

    Values(Members keys, BuiltObject a, int depth) {
      super(depth);
      this.keys = keys;
      this.a = a;
    }

    @Override
    int next(RunState state, int last) {
      if (last == FALSE) {
        return FALSE;
      }
      cursor = last == START ? keys.first() : keys.next(state, cursor);
      if (cursor == -1) {
        return TRUE;
      }
      JqValue value = a.get(state, ((Keyed) keys).key(cursor).value());
      if (value == null) {
        return FALSE;
      }
      x = value;
      y = keys.member(state, cursor);
      return PAIR;
    }
  }

  /**
   * jq's {@code jvp_object_contains} in a read {@code a}, through its {@link KeyIndex}: each key of {@code b} in its key
   * order with its value, and {@code a}'s last value of the same key, which the index found; a key {@code a} lacks, or a
   * value that does not contain {@code b}'s, makes the objects not contained.
   */
  private static final class Indexed extends Walk {
    private final Members keys;
    private final KeyIndex a;
    private int cursor;
    private int matched;

    Indexed(Members keys, KeyIndex a, int depth) {
      super(depth);
      this.keys = keys;
      this.a = a;
    }

    @Override
    int next(RunState state, int last) {
      if (last == FALSE) {
        return FALSE;
      }
      if (last == START) {
        cursor = keys.first();
      } else {
        cursor = keys.next(state, cursor);
        matched++;
      }
      if (cursor == -1) {
        return TRUE;
      }
      JqValue value = a.value(matched);
      if (value == null) {
        return FALSE;
      }
      x = value;
      y = keys.member(state, cursor);
      return PAIR;
    }
  }
}
