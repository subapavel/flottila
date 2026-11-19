package dev.rail8.jq.query.ir;

/**
 * How a bound value runs when its bind runs in a path variant; outside one, every value runs in value mode. Only a
 * path variant reads it.
 */
public enum Tracking {
  /** The value runs in value mode. */
  VALUE,
  /**
   * The value runs in path mode on the bind's input and its paths are dropped, so a step on a result that was not
   * found in the input raises as it does in any path variant.
   */
  PATH,
  /** As {@link #PATH}, and each result's path is kept with its slot, so a Var of the slot passes that path on. */
  ITEM
}
