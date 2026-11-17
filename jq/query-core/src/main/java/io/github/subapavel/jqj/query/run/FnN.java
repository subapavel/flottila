package io.github.subapavel.jqj.query.run;

/**
 * The call form of an operation with at most one result and any number of arguments (ADR-0009, ADR-0034 amendment).
 * The arguments come as an array that is new for each call, so the operation may keep it, as a value it builds.
 */
@FunctionalInterface
public non-sealed interface FnN extends CallForm {
  /**
   * The one result for {@code in} and {@code args}, in order, in the run {@code state} belongs to, or
   * {@link CallForm#NONE}.
   */
  Object apply(RunState state, Object in, Object[] args);
}
