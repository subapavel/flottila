package io.github.subapavel.jqj.query.internal.interpret;

/**
 * An atomic argument of an intrinsic or a call (ADR-0034, ADR-0045 #107): the input, a cell's value or a constant. A
 * cell is read in the frame {@code level} links out from the running one (cell 0 of a closure's frame links its defining
 * frame).
 */
public final class Argument {
  /** {@link #slot} of the input. */
  private static final int INPUT = -1;

  /** {@link #slot} of a constant. */
  private static final int CONSTANT = -2;

  /** The cell read, from 0, or {@link #INPUT} or {@link #CONSTANT}. */
  private final int slot;

  /** The links from the running frame to the frame of {@link #slot}; 0 for the other two. */
  private final int level;

  /** The constant's value; null for the other two. */
  private final Object value;

  private Argument(int slot, int level, Object value) {
    this.slot = slot;
    this.level = level;
    this.value = value;
  }

  /** The input. */
  public static Argument input() {
    return new Argument(INPUT, 0, null);
  }

  /** The value of cell {@code slot} of the frame {@code level} links out from the running one. */
  public static Argument slot(int level, int slot) {
    return new Argument(slot, level, null);
  }

  /** {@code value}, whatever the input. */
  public static Argument constant(Object value) {
    return new Argument(CONSTANT, 0, value);
  }

  /** The argument's value for the input {@code in}, with the running {@code frame}. */
  Object read(Object[] frame, Object in) {
    if (slot >= 0) {
      return Frames.out(frame, level)[slot];
    }
    return slot == INPUT ? in : value;
  }
}
