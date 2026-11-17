package io.github.subapavel.jqj.query.run;

/**
 * The call form of an operation with any number of results (ADR-0009, ADR-0034, ADR-0041): a resumable cursor, so the
 * caller runs it as a loop and charges one step per result before it emits it. A cursor is a {@code long} the operation
 * chooses, {@link #END} after the last result. The arguments come as an array, as many as the call has and then
 * {@link #loopSlots} more, the same array at each call of one loop and a new one for each loop; {@link #first} may
 * replace an argument with one the later calls read more cheaply, and may set the loop slots to what the later calls of
 * that loop read (ADR-0034 as amended by ADR-0044); no other call writes the array.
 */
public non-sealed interface Many extends CallForm {
  /** The cursor after the last result. */
  long END = -1;

  /** The slots after the arguments that {@link #first} sets for the later calls of its loop; none by default. */
  default int loopSlots() {
    return 0;
  }

  /** The cursor of the first result for {@code in} and {@code args}, or {@link #END} when there is none. */
  long first(RunState state, Object in, Object[] args);

  /** The cursor of the result after {@code cursor}'s, or {@link #END}. */
  long next(RunState state, Object in, Object[] args, long cursor);

  /** The result at {@code cursor}, which is not {@link #END}, for {@code in} and {@code args}. */
  Object value(RunState state, Object in, Object[] args, long cursor);
}
