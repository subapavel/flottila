package dev.rail8.jq.query.ir;

/**
 * How an operation's results relate to its input's path in a path variant: untracked, the input's own path, or a step
 * that adds to it. The five rules of a one-result step and the one of a many-result step each need a call form that is
 * also a {@code PathStep}, and no other rule may have one.
 */
public enum PathRule {
  /** Its results are not found in its input: each is untracked. */
  NONE,
  /** Each result has its input's path. */
  SAME,
  /** A step by a key the operation holds: the result's path is its input's with that key added. */
  KEY,
  /** A step by its argument, an index: the result's path is its input's with the index added. */
  INDEX,
  /** A step to each value its input holds: each result's path is its input's with that value's key added. */
  ALL,
  /** A step by its argument, a key known only at run time, whose kind only the format reads. */
  DYNAMIC,
  /** A step to the part of its input between its two arguments: the result's path adds the key the step builds. */
  SLICE,
  /** A step by each key of its argument, a list; on an untracked input its result is untracked, with no error. */
  KEY_LIST
}
