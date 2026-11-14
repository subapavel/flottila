/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_has, f_keys and f_keys_unsorted in src/builtin.c,
 * jv_has, jv_keys and jv_keys_unsorted in src/jv_aux.c, and to_entries/0 in src/builtin.jq.
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

import io.github.subapavel.jqj.internal.value.ArrayView;
import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.internal.value.BuiltObject;
import io.github.subapavel.jqj.internal.value.BuiltString;
import io.github.subapavel.jqj.internal.value.DoubleNumber;
import io.github.subapavel.jqj.internal.value.KeyProof;
import io.github.subapavel.jqj.internal.value.Keyed;
import io.github.subapavel.jqj.internal.value.Members;
import io.github.subapavel.jqj.internal.value.Order;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
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
 * jq 1.8.2's natives of keys (#72; jq-ref, recorded: diff/natives.rec has-*, keys-*): {@code has(k)}, {@code keys} and
 * {@code keys_unsorted}, and builtin.jq's {@code to_entries} as a native (#85, ADR-0044; distinct.rec to-entries-*).
 * They tick where their loop depends on the data (ADR-0008): {@code has} as its lookup does
 * (ADR-0034), {@code keys} and {@code keys_unsorted} once per key or index, on top of a view's walk, and {@code keys}
 * once more per compare of its sort for each started KiB of the shorter key, at least one; the arrays they build are
 * charged (ADR-0035).
 */
public final class Keys {
  /** {@code has(k)}, its argument the key. */
  public static final Operation HAS = new Operation("has", (Fn1) (state, in, key) -> has(state, (JqValue) in, (JqValue) key));

  /** {@code keys}. */
  public static final Operation KEYS = new Operation("keys", (Fn0) (state, in) -> keys(state, (JqValue) in, true));

  /** {@code keys_unsorted}. */
  public static final Operation KEYS_UNSORTED = new Operation("keys_unsorted", (Fn0) (state, in) -> keys(state, (JqValue) in, false));

  /** {@code to_entries}. */
  public static final Operation TO_ENTRIES = new Operation("to_entries", (Fn0) (state, in) -> entries(state, (JqValue) in));

  /** The shape of each object {@code to_entries} builds, as builtin.jq's {@code {key: $k, value: .[$k]}} has it. */
  private static final JqString[] ENTRY = {BuiltString.of("key"), BuiltString.of("value")};

  private Keys() {}

  /**
   * jq's {@code jv_has}: null has no key; an object and a string, whether the key is there; an array and a number,
   * whether the index cut toward zero is one of its elements, never counted from the end, and NaN never; any other
   * pair jq's error, which names the two kinds only.
   */
  private static JqBool has(RunState state, JqValue t, JqValue k) {
    if (t instanceof JqNull) {
      return JqBool.FALSE;
    }
    if (t instanceof Keyed object && k instanceof JqString name) {
      return bool(object.get(state, name.value()) != null);
    }
    if (t instanceof JqArray array && k instanceof JqNumber n) {
      return bool(element(state, array, n.doubleValue()));
    }
    throw new FilterException(
        BuiltString.of("Cannot check whether " + NavigationErrors.kind(t) + " has a " + NavigationErrors.kind(k) + " key"));
  }

  /**
   * Whether {@code array} has an element at {@code i}, jq's {@code jv_array_get} of {@code (int) i}: a NaN is none, the
   * cast cuts toward zero and saturates, and an index below zero is none. A view steps to it (ArrayView).
   */
  private static boolean element(RunState state, JqArray array, double i) {
    if (Double.isNaN(i)) {
      return false;
    }
    int at = (int) i;
    if (at < 0) {
      return false;
    }
    return switch (array) {
      case ArrayView view -> view.get(state, at) != null;
      case BuiltArray built -> at < built.length();
    };
  }

  private static JqBool bool(boolean b) {
    return b ? JqBool.TRUE : JqBool.FALSE;
  }

  /**
   * jq's {@code jv_keys} ({@code sorted}) or {@code jv_keys_unsorted}: an array's indices; an object's keys, each at
   * its first place (ADR-0002), sorted by jq's {@code string_cmp} when {@code sorted}; any other kind jq's "has no
   * keys".
   */
  private static BuiltArray keys(RunState state, JqValue v, boolean sorted) {
    return switch (v) {
      case JqArray array -> indices(state, array);
      case JqObject object -> names(state, KeyProof.members(state, object), sorted);
      case JqNull _ -> throw Arithmetic.cannot(state, v, "has no keys");
      case JqBool _ -> throw Arithmetic.cannot(state, v, "has no keys");
      case JqNumber _ -> throw Arithmetic.cannot(state, v, "has no keys");
      case JqString _ -> throw Arithmetic.cannot(state, v, "has no keys");
    };
  }

  /** {@code [0, n)} for an array of n elements: a view's walk to count them, then one tick per index. */
  private static BuiltArray indices(RunState state, JqArray array) {
    int n =
        switch (array) {
          case ArrayView view -> view.length(state);
          case BuiltArray built -> built.length();
        };
    state.tick(n);
    Object[] indices = Built.elements(state, n);
    for (int i = 0; i < n; i++) {
      indices[i] = new DoubleNumber(i);
    }
    return new BuiltArray(indices, n);
  }

  /** The keys of an object, in its members' walk after its proof, one tick each, then sorted if {@code sorted}. */
  private static BuiltArray names(RunState state, Members object, boolean sorted) {
    Keyed keyed = (Keyed) object;
    Built.Elements names = new Built.Elements(state);
    for (int c = object.first(); c != -1; c = object.next(state, c)) {
      state.tick();
      names.add(keyed.key(c));
    }
    if (sorted) {
      names.sort(
          (a, b) -> {
            MemorySegment x = ((JqString) a).utf8();
            MemorySegment y = ((JqString) b).utf8();
            state.tick(Math.max(1, Built.kibibytes(Math.min(x.byteSize(), y.byteSize()))));
            return Order.bytes(x, y);
          });
    }
    return names.array();
  }

  /**
   * builtin.jq's {@code to_entries}, {@code [keys_unsorted[] as $k | {key: $k, value: .[$k]}]}, in one walk (ADR-0044,
   * ADR-0042 as amended): an object's members after its proof, each key with its last value; an array's elements, each
   * with its index; any other kind {@code keys_unsorted}'s error. One tick per entry, on top of a view's proof; the array
   * and each entry are charged as the definition's collect and object construction charge them (ADR-0035).
   */
  private static BuiltArray entries(RunState state, JqValue v) {
    Members members =
        switch (v) {
          case JqArray array -> (Members) array;
          case JqObject object -> KeyProof.members(state, object);
          case JqNull _ -> throw Arithmetic.cannot(state, v, "has no keys");
          case JqBool _ -> throw Arithmetic.cannot(state, v, "has no keys");
          case JqNumber _ -> throw Arithmetic.cannot(state, v, "has no keys");
          case JqString _ -> throw Arithmetic.cannot(state, v, "has no keys");
        };
    Built.Elements entries = new Built.Elements(state);
    int i = 0;
    for (int c = members.first(); c != -1; c = members.next(state, c)) {
      state.tick();
      JqValue key = members instanceof Keyed keyed ? keyed.key(c) : new DoubleNumber(i++);
      JqValue value = members.member(state, c);
      state.chargeBuilt(Charges.CONTAINER + Charges.ENTRY * ENTRY.length);
      entries.add(new BuiltObject(ENTRY, new Object[] {key, value}));
    }
    return entries.array();
  }
}
