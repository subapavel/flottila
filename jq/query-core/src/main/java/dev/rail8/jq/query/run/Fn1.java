package dev.rail8.jq.query.run;

/** The call form of an operation with at most one result and one argument. */
@FunctionalInterface
public non-sealed interface Fn1 extends CallForm {
  /** The one result for {@code in} and the argument {@code a} in the run {@code state} belongs to, or {@link CallForm#NONE}. */
  Object apply(RunState state, Object in, Object a);
}
