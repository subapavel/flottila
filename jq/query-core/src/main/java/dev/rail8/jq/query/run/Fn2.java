package dev.rail8.jq.query.run;

/** The call form of an operation with at most one result and two arguments. */
@FunctionalInterface
public non-sealed interface Fn2 extends CallForm {
  /**
   * The one result for {@code in} and the arguments {@code a} and {@code b}, in order, in the run {@code state} belongs
   * to, or {@link CallForm#NONE}.
   */
  Object apply(RunState state, Object in, Object a, Object b);
}
