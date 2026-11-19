package dev.rail8.jq.query.run;

/**
 * One update of a value along paths, made by {@link PathFormat}: it holds the current root, which starts as the value
 * it was made for, the paths to delete, their charges, and every limit and message of the update. An edit is used once,
 * by one run of an update: per path in order, a modify calls {@link #get}, then {@link #set} with the replacement or
 * {@link #delete} when there is none, and an assign calls {@link #set} only; then {@link #end} once. A method that
 * raises leaves the edit as it was, so the run may go on with it. A run that stops or raises before {@link #end}
 * drops the edit, and nothing is restored.
 */
public interface Edit {
  /** The value at {@code p} in the current root, which the replacement is made from. */
  Object get(RunState s, Path p);

  /** Makes the current root the old one with {@code v} at {@code p}. */
  void set(RunState s, Path p, Object v);

  /** Adds {@code p} to the paths that {@link #end} deletes. */
  void delete(RunState s, Path p);

  /** The current root with every added path deleted: the update's one result. */
  Object end(RunState s);
}
