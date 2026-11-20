package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.ir.Facts;
import dev.rail8.jq.query.ir.PathRule;

/** The facts jq's operations declare: their path rule and whether a result may keep an argument or the input. */
public final class Declared {
  /** Untracked results, each built afresh or holding no container and no string. */
  public static final Facts FRESH = new Facts(PathRule.NONE, false);

  /** Untracked results that may share a container or string with an argument or with the input, at any depth. */
  public static final Facts KEEPS = new Facts(PathRule.NONE, true);

  private Declared() {}

  /** The facts of a navigation step by {@code rule}: its result is a part of its input, so it keeps the input. */
  public static Facts step(PathRule rule) {
    return new Facts(rule, true);
  }
}
