package dev.rail8.jq.query.compile;

import dev.rail8.jq.query.analysis.Cardinality;
import dev.rail8.jq.query.analysis.Facts;

/**
 * Which of the two forms a node runs by: fixed per node from the facts when the tree is built, and read as
 * {@link Executable#form(int, int)}. The tail emitter is a fast path of the push form, not a form of its own.
 */
public enum RunForm {
  /** The push form: the node pushes its results into a sink. */
  PUSH,
  /** The value form: the node returns its one result, or none, and its parent, its Call or the run entry emits it. */
  VALUE;

  /**
   * The run form of node {@code id} of the body {@code body}, -1 for the main tree: VALUE where its cardinality is at
   * most one and it may not break out, PUSH everywhere else. It reads the facts only, so the conservative compile,
   * whose facts are MANY and may break out, never has a value-form node.
   */
  static RunForm of(Facts facts, int body, int id) {
    return facts.cardinality(body, id) != Cardinality.MANY && !facts.mayBreakOut(body, id) ? VALUE : PUSH;
  }
}
