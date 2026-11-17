package io.github.subapavel.jqj.query.ir;

import java.util.List;

/**
 * A call of an {@link Operation} on the input (ADR-0034): its arguments are atomic ({@link Identity}, {@link Var} or
 * {@link Const}), as many as its call form takes, and each is read on the Intrinsic's own input.
 */
public record Intrinsic(Source source, Operation op, List<Node> args) implements Node {
  public Intrinsic {
    args = List.copyOf(args);
  }
}
