package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.internal.value.BuiltArray;
import io.github.subapavel.jqj.query.run.Charges;
import io.github.subapavel.jqj.query.run.RunState;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The arrays an operator's result is built in, each charged before it is allocated (ADR-0035): a string's bytes as a
 * string, a built array's slots as a container and its elements. A length past what the JVM allocates ends the run
 * with the bytes-built termination instead, whatever the limit.
 */
final class Built {
  /** The longest array built: {@code jdk.internal.util.ArraysSupport.SOFT_MAX_ARRAY_LENGTH}, below the VMs' limits. */
  static final int MAX_LENGTH = Integer.MAX_VALUE - 8;

  private Built() {}

  /** The bytes of a new string of {@code length} bytes. */
  static byte[] string(RunState state, long length) {
    if (length > MAX_LENGTH) {
      throw state.overBuilt();
    }
    state.chargeBuilt(Charges.STRING + length);
    return new byte[(int) length];
  }

  /** The slots of a new array of {@code length} elements. */
  static Object[] elements(RunState state, long length) {
    if (length > MAX_LENGTH) {
      throw state.overBuilt();
    }
    state.chargeBuilt(Charges.CONTAINER + Charges.ELEMENT * length);
    return new Object[(int) length];
  }

  /** The ticks of a loop over {@code bytes} bytes: one per started KiB, the writer's rate (ADR-0008, ADR-0015). */
  static int kibibytes(long bytes) {
    return (int) ((bytes + 1023) >>> 10);
  }

  /**
   * The capacity after {@code length} elements filled an array that {@link Elements} grows: twice {@code length}, at
   * least {@link Elements#FIRST} and at most {@link #MAX_LENGTH}.
   *
   * @throws io.github.subapavel.jqj.query.run.Termination over the bytes-built budget when {@code length} is {@link
   *     #MAX_LENGTH} already
   */
  static int grown(RunState state, int length) {
    if (length >= MAX_LENGTH) {
      throw state.overBuilt();
    }
    return (int) Math.min(MAX_LENGTH, Math.max(Elements.FIRST, 2L * length));
  }

  /**
   * A built array whose length is known only once it is full, as a Collect's is (ADR-0034 amendment): the container is
   * charged when it is made and each element before it is stored (ADR-0035), never the capacity the slots grow to.
   */
  static final class Elements {
    /** The capacity of the first slots, allocated with the first element. */
    static final int FIRST = 4;

    private final RunState state;
    private Object[] values = {};
    private int length;

    Elements(RunState state) {
      this.state = state;
      state.chargeBuilt(Charges.CONTAINER);
    }

    void add(Object value) {
      state.chargeBuilt(Charges.ELEMENT);
      if (length == values.length) {
        values = Arrays.copyOf(values, grown(state, length));
      }
      values[length++] = value;
    }

    /** The elements so far, sorted by {@code order} with jqj's merge sort, {@link Sort#mergeSort} (F-48). */
    void sort(Comparator<Object> order) {
      values = Sort.mergeSort(values, length, order);
    }

    /** The array of the elements so far; nothing is added after. */
    BuiltArray array() {
      return new BuiltArray(values, length);
    }
  }
}
