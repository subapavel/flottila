package dev.rail8.jq.query.run;

/**
 * The path side of a call form that navigates: one that finds its results inside its input, each at the input's path
 * plus a key. A one-result form passes its arguments to both methods, {@code (null, null)} for none, {@code (k, null)}
 * for one and {@code (k, end)} for two. A {@link Many} passes {@code (null, null)} to {@link #untracked} and, to
 * {@link #extend}, the key of each result's cursor ({@link Many#key}).
 */
public interface PathStep {
  /**
   * The error for a step on {@code in}, an input that was not found in a path variant's input, with the arguments
   * {@code key} and {@code end}. A step whose input may be such a value without error is never asked.
   */
  FilterException untracked(RunState s, Object in, Object key, Object end);

  /**
   * The path of a result this step found in {@code in}, the value at {@code at}, with {@code key} and {@code end}: by
   * default {@code at} with {@code key} added. A key that this method builds is charged here.
   */
  default Path extend(RunState s, Path at, Object in, Object key, Object end) {
    return at.child(key);
  }
}
