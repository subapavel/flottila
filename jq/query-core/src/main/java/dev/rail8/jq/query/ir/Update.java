package dev.rail8.jq.query.ir;

import dev.rail8.jq.query.run.PathFormat;

/**
 * {@code lhs |= rhs} ({@link Kind#MODIFY}) or {@code lhs = rhs} ({@link Kind#ASSIGN}): {@code lhs} runs in path mode on
 * the Update's input, from the input's own path, and each of its results' paths is edited through one edit of
 * {@code paths} on that input, in {@code lhs}'s order. After {@code lhs} runs out, the Update emits the edit's one
 * result. A result of {@code lhs} that was not found in the input raises {@code paths}' invalid-path error when it is
 * reached, and a break leaves with no result.
 */
public record Update(Source source, Kind kind, Node lhs, Node rhs, PathFormat paths) implements Node {
  /** How the value at each path is replaced. */
  public enum Kind {
    /**
     * {@code rhs} runs on the value at the path, which it replaces with its first result; a path where it gives none
     * is deleted at the end.
     */
    MODIFY,
    /** {@code rhs} is atomic (Identity, Var or Const), read on the Update's input, and set at every path; nothing is deleted. */
    ASSIGN
  }
}
