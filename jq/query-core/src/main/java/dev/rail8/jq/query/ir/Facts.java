package dev.rail8.jq.query.ir;

/** What the passes may assume of an operation beyond its call form: its path rule. */
public record Facts(PathRule path) {
  /** The facts of an operation that declares none: its results are untracked. */
  public static final Facts UNTRACKED = new Facts(PathRule.NONE);
}
