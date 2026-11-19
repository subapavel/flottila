package dev.rail8.jq.query.run;

/**
 * What a format makes of paths: the value of a path, the error for a result that is no path, and the edits that update
 * a value along paths. A program that uses paths holds one instance, shared by all its nodes that take one.
 */
public interface PathFormat {
  /** The path {@code p} as a value, for a node that emits paths; the node has charged one step per key of {@code p}. */
  Object value(RunState s, Path p);

  /** The error for {@code result}, a result that was used as a path but was not found in the input. */
  FilterException invalid(RunState s, Object result);

  /**
   * A new edit of {@code root} for one run of an update that replaces each value at a path by the first result of a
   * filter on it, and deletes the paths whose filter gave none.
   */
  Edit modify(RunState s, Object root);

  /** A new edit of {@code root} for one run of an update that sets each path to one value and deletes nothing. */
  Edit assign(RunState s, Object root);
}
