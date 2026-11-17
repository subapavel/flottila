package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.ir.Operation;
import java.util.Map;

/**
 * jq's formats ({@code @name}), the family's one file: the operation of each format this engine has, by its name
 * without the {@code @}. A new format adds its entry here.
 */
public final class Formats {
  private static final Map<String, Operation> BUILT = Map.of("text", new Operation("@text", new TextFormat()));

  private Formats() {}

  /** The operation of {@code @name}, or null when this engine has no such format. */
  public static Operation lookup(String name) {
    return BUILT.get(name);
  }
}
