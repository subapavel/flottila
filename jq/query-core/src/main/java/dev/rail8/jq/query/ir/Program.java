package dev.rail8.jq.query.ir;

import java.util.List;

/**
 * A main tree and the functions its {@link Call}s name, by their place in {@code functions} (ADR-0045), which is no node
 * kind. No node object is used twice in it, across the main tree and the functions too.
 */
public record Program(Node main, List<Function> functions) {
  public Program {
    functions = List.copyOf(functions);
  }
}
