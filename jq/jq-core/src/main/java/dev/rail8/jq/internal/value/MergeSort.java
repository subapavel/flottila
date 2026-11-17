package dev.rail8.jq.internal.value;

import java.util.Arrays;
import java.util.Comparator;

/**
 * jqj's own stable merge sort (ADR-0012), whose compares are the ones a sort ticks for (F-48, F-53): each caller's
 * {@code order} charges its compares, the sort itself charges nothing.
 */
public final class MergeSort {
  private MergeSort() {}

  /**
   * The first {@code n} of {@code items} in {@code order}, stable: a bottom-up merge sort, whose every merge takes the
   * left run's item unless {@code order} of the left item and the right one is positive. Its compares are the ones a
   * sort ticks for (F-48): in {@code sort} and its kin (Sort), in {@code keys} (Keys), and in the sort of an object's
   * key list by {@link Order#compare} (F-53). The result is {@code items} or a scratch array of {@code n}, neither
   * charged.
   */
  public static <T> T[] sort(T[] items, int n, Comparator<? super T> order) {
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
}
