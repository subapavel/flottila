package io.github.subapavel.jqj.query.internal.interpret;

/** An atomic argument of an intrinsic (ADR-0034): the intrinsic's input, a slot's value or a constant. */
public final class Argument {
  /** {@link #slot} of the input. */
  private static final int INPUT = -1;

  /** {@link #slot} of a constant. */
  private static final int CONSTANT = -2;

  /** The slot read, from 0, or {@link #INPUT} or {@link #CONSTANT}. */
  private final int slot;

  /** The constant's value; null for the other two. */
  private final Object value;

  private Argument(int slot, Object value) {
    this.slot = slot;
    this.value = value;
  }

  /** The intrinsic's input. */
  public static Argument input() {
    return new Argument(INPUT, null);
  }

  /** The value of {@code slot}. */
  public static Argument slot(int slot) {
    return new Argument(slot, null);
  }

  /** {@code value}, whatever the input. */
  public static Argument constant(Object value) {
    return new Argument(CONSTANT, value);
  }

  /** The argument's value for the input {@code in}, with the run's {@code slots}. */
  Object read(Object[] slots, Object in) {
    if (slot >= 0) {
      return slots[slot];
    }
    return slot == INPUT ? in : value;
  }
}
