package dev.rail8.jq.query.ir;

/**
 * What the passes may assume of an operation beyond its call form: its path rule, and whether a result may share a
 * container or string with an argument or with the input, at any depth ({@code keepsArgs}). True is the safe value of
 * {@code keepsArgs}: a false one is a promise that every result is built afresh or holds no container and no string.
 */
public record Facts(PathRule path, boolean keepsArgs) {
  /** The facts of an operation that declares none: its results are untracked and may keep its arguments. */
  public static final Facts UNTRACKED = new Facts(PathRule.NONE, true);
}
