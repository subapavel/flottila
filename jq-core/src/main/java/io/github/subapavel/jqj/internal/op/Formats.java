package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.query.ir.Operation;
import java.util.Map;

/**
 * jq's formats ({@code @name}), the family's one file: the operation of each format built so far, by its name without
 * the {@code @}. A format ticket adds its entry here.
 */
public final class Formats {
  private static final Map<String, Operation> BUILT = Map.of("text", new Operation("@text", new TextFormat()));

  private Formats() {}

  /** The operation of {@code @name}, or null when that format is not built yet. */
  public static Operation lookup(String name) {
    return BUILT.get(name);
  }
}
