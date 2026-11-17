package dev.rail8.jq.query.run;

/** The call form of an operation with at most one result and no argument. */
@FunctionalInterface
public non-sealed interface Fn0 extends CallForm {
  /**
   * The one result for {@code in} in the run {@code state} belongs to, with the run state first, or
   * {@link CallForm#NONE} for none.
   */
  Object apply(RunState state, Object in);
}
