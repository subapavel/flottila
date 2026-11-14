/*
 * Translated from jq 1.8.2 (https://github.com/jqlang/jq): f_sort, f_sort_by_impl, f_group_by_impl, f_unique,
 * f_unique_by_impl, minmax_by, f_min, f_max, f_min_by_impl and f_max_by_impl in src/builtin.c, and sort_items, jv_sort,
 * jv_group and jv_unique in src/jv_aux.c.
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
import io.github.subapavel.jqj.internal.value.Equality;
import io.github.subapavel.jqj.internal.value.Members;
import io.github.subapavel.jqj.internal.value.Order;
import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.Fn1;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.value.JqArray;
import io.github.subapavel.jqj.value.JqNull;
import io.github.subapavel.jqj.value.JqValue;
import java.util.Arrays;
import java.util.Comparator;

/**
 * jq 1.8.2's natives that order an array (#73; jq-ref, recorded: diff/sort.rec): {@code sort}, {@code unique}, {@code
 * min} and {@code max}, and the hidden {@code _sort_by_impl}, {@code _group_by_impl}, {@code _unique_by_impl}, {@code
 * _min_by_impl} and {@code _max_by_impl}, whose argument is the array of keys that builtin.jq's {@code sort_by(f)} and
 * its kin pass as {@code map([f])}. A form without {@code _by} is its own keys. All order the keys by {@link
 * Order#compare}, jq's value order, and raise its "Comparison too deep" (ADR-0029).
 *
 * <p>The sorting forms put each member with its key and sort them with a stable merge sort of jqj's own (ADR-0012):
 * runs of 1, 2, 4, … members merged pairwise, the left run's member first unless the right one's key comes strictly
 * before it. jq sorts with libc's {@code qsort} and breaks a tie by the input's order, so the two agree wherever the
 * value order is one: equal keys keep the input's order. A pair of NaNs, which jq's compare puts below each other,
 * keeps the input's order too, as jq-ref's does, since the left one always comes first. Inside a cycle of literal and
 * computed numbers the order is jqj's own (DEVIATIONS.md "Order inside a comparison cycle"). {@code _group_by_impl}
 * then starts a group at each key that jq's {@code jv_equal} ({@link Equality}) finds unequal to the first key of the
 * group before it, and {@code _unique_by_impl} keeps the first member of each such group, both raising its "Equality
 * check too deep". {@code min} keeps the first least key and {@code max} the last greatest, jq's left-to-right scan.
 *
 * <p>Each ticks once per member it reads, on top of a view's walk, and adds the ticks of each compare and equality check
 * it makes, never one of its own per compare (ADR-0037); the arrays it builds are charged (ADR-0035), its scratch of
 * members and keys is not.
 */
public final class Sort {
  /** {@code sort}. */
  public static final Operation SORT = new Operation("sort", (Fn0) (state, in) -> sort(state, array(state, in, "sorted"), false));

  /** {@code _sort_by_impl(keys)}. */
  public static final Operation SORT_BY = new Operation("_sort_by_impl", (Fn1) (state, in, keys) -> sortBy(state, in, keys, false));

  /** {@code _group_by_impl(keys)}. */
  public static final Operation GROUP_BY = new Operation("_group_by_impl", (Fn1) Sort::groupBy);

  /** {@code unique}. */
  public static final Operation UNIQUE = new Operation("unique", (Fn0) (state, in) -> sort(state, array(state, in, "sorted"), true));

  /** {@code _unique_by_impl(keys)}. */
  public static final Operation UNIQUE_BY = new Operation("_unique_by_impl", (Fn1) (state, in, keys) -> sortBy(state, in, keys, true));

  /** {@code min}. */
  public static final Operation MIN = new Operation("min", (Fn0) (state, in) -> extreme(state, (JqValue) in, (JqValue) in, true));

  /** {@code max}. */
  public static final Operation MAX = new Operation("max", (Fn0) (state, in) -> extreme(state, (JqValue) in, (JqValue) in, false));

  /** {@code _min_by_impl(keys)}. */
  public static final Operation MIN_BY = new Operation("_min_by_impl", (Fn1) (state, in, keys) -> extreme(state, (JqValue) in, (JqValue) keys, true));

  /** {@code _max_by_impl(keys)}. */
  public static final Operation MAX_BY = new Operation("_max_by_impl", (Fn1) (state, in, keys) -> extreme(state, (JqValue) in, (JqValue) keys, false));

  private Sort() {}

  /** A member of the input and its key. */
  private record Entry(JqValue value, JqValue key) {}

  /** jq's {@code f_sort} and {@code f_unique}: the input must be an array. */
  private static JqArray array(RunState state, Object in, String what) {
    if (in instanceof JqArray array) {
      return array;
    }
    throw Arithmetic.cannot(state, (JqValue) in, "cannot be " + what + ", as it is not an array");
  }

  /** {@code sort} or {@code unique} of {@code array}, each member its own key. */
  private static BuiltArray sort(RunState state, JqArray array, boolean unique) {
    int n = length(state, array);
    return sorted(state, entries(state, array, array, n), unique);
  }

  /**
   * jq's {@code f_sort_by_impl} and {@code f_unique_by_impl}: the input and the keys must be arrays of one length,
   * otherwise jq's error names both.
   */
  private static BuiltArray sortBy(RunState state, Object in, Object keys, boolean unique) {
    return sorted(state, keyed(state, (JqValue) in, (JqValue) keys), unique);
  }

  /** jq's {@code f_group_by_impl}: as {@link #sortBy}, then the groups of equal keys, each an array. */
  private static BuiltArray groupBy(RunState state, Object in, Object keys) {
    Entry[] entries = mergeSort(state, keyed(state, (JqValue) in, (JqValue) keys));
    Built.Elements groups = new Built.Elements(state);
    int start = 0;
    while (start < entries.length) {
      Built.Elements group = new Built.Elements(state);
      group.add(entries[start].value());
      int next = start + 1;
      while (next < entries.length && Equality.equal(state, entries[start].key(), entries[next].key())) {
        group.add(entries[next].value());
        next++;
      }
      groups.add(group.array());
      start = next;
    }
    return groups.array();
  }

  /** The members of {@code in} with their {@code keys}, after jq's checks of {@code _sort_by_impl} and its kin. */
  private static Entry[] keyed(RunState state, JqValue in, JqValue keys) {
    if (in instanceof JqArray values && keys instanceof JqArray keyArray) {
      int n = length(state, values);
      if (n == length(state, keyArray)) {
        return entries(state, values, keyArray, n);
      }
    }
    throw Arithmetic.cannot(state, in, keys, "cannot be sorted, as they are not both arrays");
  }

  /** The sorted members, or with {@code unique} the first of each run of equal keys. */
  private static BuiltArray sorted(RunState state, Entry[] unsorted, boolean unique) {
    Entry[] entries = mergeSort(state, unsorted);
    if (unique) {
      Built.Elements kept = new Built.Elements(state);
      JqValue key = null;
      for (Entry e : entries) {
        if (key == null || !Equality.equal(state, key, e.key())) {
          key = e.key();
          kept.add(e.value());
        }
      }
      return kept.array();
    }
    Object[] values = Built.elements(state, entries.length);
    for (int i = 0; i < entries.length; i++) {
      values[i] = entries[i].value();
    }
    return new BuiltArray(values, entries.length);
  }

  /** The {@code n} members of {@code values} with the members of {@code keys} at the same places, one tick each. */
  private static Entry[] entries(RunState state, JqArray values, JqArray keys, int n) {
    Members v = (Members) values;
    Members k = (Members) keys;
    Entry[] entries = new Entry[n];
    int cv = v.first();
    int ck = k.first();
    for (int i = 0; i < n; i++) {
      state.tick();
      entries[i] = new Entry(v.member(state, cv), k.member(state, ck));
      cv = v.next(state, cv);
      ck = k.next(state, ck);
    }
    return entries;
  }

  /** {@code entries} in jq's value order of their keys, by {@link #mergeSort} with {@link Order#compare}. */
  private static Entry[] mergeSort(RunState state, Entry[] entries) {
    return mergeSort(entries, entries.length, (a, b) -> Order.compare(state, a.key(), b.key()));
  }

  /**
   * The first {@code n} of {@code items} in {@code order}, stable: a bottom-up merge sort, whose every merge takes the
   * left run's item unless {@code order} of the left item and the right one is positive. Its compares are the ones a
   * sort ticks for (F-48), here and in {@code keys} ({@link Built.Elements#sort}). The result is {@code items} or a
   * scratch array of {@code n}, neither charged.
   */
  static <T> T[] mergeSort(T[] items, int n, Comparator<? super T> order) {
    T[] from = items;
    T[] to = Arrays.copyOf(items, n);
    for (long width = 1; width < n; width *= 2) {
      for (long lo = 0; lo < n; lo += 2 * width) {
        merge(order, from, to, (int) lo, (int) Math.min(lo + width, n), (int) Math.min(lo + 2 * width, n));
      }
      T[] merged = to;
      to = from;
      from = merged;
    }
    return from;
  }

  /** Merges the sorted runs {@code from[lo, mid)} and {@code from[mid, hi)} into {@code to[lo, hi)}. */
  private static <T> void merge(Comparator<? super T> order, T[] from, T[] to, int lo, int mid, int hi) {
    int i = lo;
    int j = mid;
    int k = lo;
    while (i < mid && j < hi) {
      to[k++] = order.compare(from[i], from[j]) > 0 ? from[j++] : from[i++];
    }
    System.arraycopy(from, i, to, k, mid - i);
    System.arraycopy(from, j, to, k + mid - i, hi - j);
  }

  /**
   * jq's {@code minmax_by}: the member of {@code values} whose key in {@code keys} is the first least ({@code min}) or
   * the last greatest, null for no member; the values, then the keys, must be arrays, then of one length.
   */
  private static JqValue extreme(RunState state, JqValue values, JqValue keys, boolean min) {
    if (!(values instanceof JqArray array) || !(keys instanceof JqArray keyArray)) {
      throw Arithmetic.cannot(state, values, keys, "cannot be iterated over");
    }
    int n = length(state, array);
    if (n != length(state, keyArray)) {
      throw Arithmetic.cannot(state, values, keys, "have wrong length");
    }
    if (n == 0) {
      return JqNull.NULL;
    }
    Members v = (Members) array;
    Members k = (Members) keyArray;
    int cv = v.first();
    int ck = k.first();
    state.tick();
    JqValue best = v.member(state, cv);
    JqValue bestKey = k.member(state, ck);
    for (int i = 1; i < n; i++) {
      cv = v.next(state, cv);
      ck = k.next(state, ck);
      state.tick();
      JqValue key = k.member(state, ck);
      if ((Order.compare(state, key, bestKey) < 0) == min) {
        best = v.member(state, cv);
        bestKey = key;
      }
    }
    return best;
  }

  /** An array's length; a view's walk charges its elements (ADR-0034). */
  private static int length(RunState state, JqArray array) {
    return switch (array) {
      case ArrayView view -> view.length(state);
      case BuiltArray built -> built.length();
    };
  }
}
