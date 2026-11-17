package dev.rail8.jq.internal.value;

import dev.rail8.jq.internal.json.Printer;
import dev.rail8.jq.query.run.Charges;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.value.JqArray;
import dev.rail8.jq.value.JqValue;
import java.util.Arrays;
import java.util.Objects;

/**
 * An array built at run time, a construction's or a slice's: elements {@code [0, length)} of {@code elements}, each a
 * view or a built value, never written once the array is made. Public only so the kind can permit it. Equality is the
 * object's, never jq's.
 */
public final class BuiltArray implements JqArray, Members {
  /** The elements, each a {@link JqValue}, in {@code [0, length)}; a collector's array, so it may be longer. */
  private final Object[] elements;

  private final int length;

  /**
   * The ownership mark for updates in place: the activation that made the value and may write it without a copy. Every
   * built class carries it; nothing sets it, so it is always null and no built value is written in place.
   */
  private final Object owner;

  /**
   * The array of the values {@code [0, length)} of {@code elements}, which it keeps without a copy: the caller never
   * writes the array again.
   */
  public BuiltArray(Object[] elements, int length) {
    this.elements = elements;
    this.length = length;
    this.owner = null;
  }

  /** jq's compact text. */
  @Override
  public String toJson() {
    return Printer.json(this);
  }

  @Override
  public int length() {
    return length;
  }

  /** @throws IndexOutOfBoundsException unless {@code 0 <= i < length()} */
  @Override
  public JqValue get(int i) {
    return (JqValue) elements[Objects.checkIndex(i, length)];
  }

  /**
   * The elements {@code [from, to)}, {@code 0 <= from <= to <= length()}, as a new array: one tick per element copied,
   * and the copy charged as a built container of as many elements before it is allocated.
   */
  public BuiltArray slice(RunState state, int from, int to) {
    state.tick(to - from);
    state.chargeBuilt(Charges.CONTAINER + Charges.ELEMENT * (to - from));
    return new BuiltArray(Arrays.copyOfRange(elements, from, to), to - from);
  }

  /** The cursor of the first element, its index, or -1 for an empty array. */
  @Override
  public int first() {
    return length > 0 ? 0 : -1;
  }

  /** The cursor after the element at {@code i}, or -1 after the last. */
  @Override
  public int next(RunState state, int i) {
    return i + 1 < length ? i + 1 : -1;
  }

  /** The element at {@code i}. */
  @Override
  public JqValue member(RunState state, int i) {
    return (JqValue) elements[i];
  }
}
