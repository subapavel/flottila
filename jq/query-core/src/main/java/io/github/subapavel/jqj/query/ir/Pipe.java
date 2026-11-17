package io.github.subapavel.jqj.query.ir;

import java.util.Objects;

/** {@code left | right}: each result of {@code left} is an input of {@code right}. */
public record Pipe(Source source, Node left, Node right) implements Node {
  public Pipe {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
  }
}
