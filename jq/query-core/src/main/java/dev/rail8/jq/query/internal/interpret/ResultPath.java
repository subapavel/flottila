package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.Path;
import dev.rail8.jq.query.run.PathStep;
import dev.rail8.jq.query.run.RunState;

/** How an intrinsic's path variant makes the path of each result from its input's: its operation's path rule. */
public enum ResultPath {
  /** Each result is untracked. */
  UNTRACKED,
  /** Each result has the input's path. */
  SAME,
  /** A step: it raises on an untracked input, before its call, and extends the input's path by each result's key. */
  STEP,
  /** A step by a list of keys: an untracked input gives an untracked result, with no error. */
  KEY_LIST;

  /** Raises {@code step}'s error for the call on {@code in} with {@code key} and {@code end} when it is a STEP at no path. */
  void check(PathStep step, RunState state, Path at, Object in, Object key, Object end) {
    if (this == STEP && at == null) {
      throw step.untracked(state, in, key, end);
    }
  }

  /** The path of a result of the call on {@code in}, found at {@code at}, with {@code key} and {@code end}. */
  Path of(PathStep step, RunState state, Path at, Object in, Object key, Object end) {
    return switch (this) {
      case UNTRACKED -> null;
      case SAME -> at;
      case STEP -> step.extend(state, at, in, key, end);
      case KEY_LIST -> at == null ? null : step.extend(state, at, in, key, end);
    };
  }

  /** Whether this rule extends paths through the call form's {@code PathStep}. */
  boolean steps() {
    return this == STEP || this == KEY_LIST;
  }
}
