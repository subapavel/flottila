package io.github.subapavel.jqj.internal.parse;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.JqEngine;
import java.util.List;

/**
 * {@link PreludeLoadTest}'s fresh JVM: compiles {@code .a}, {@code empty}, {@code .a|length} and {@code map(.)} twice
 * through the exported API and prints {@link Prelude#loads()} before the first and after each, one line each. A
 * compile that ends with a Stub (an unbuilt native, {@code length} until #72) still counts as a compile; any other
 * compile error ends the probe with a stack trace and exit code 1.
 */
public final class PreludeLoadProbe {
  private PreludeLoadProbe() {}

  /** The filters, in order. */
  static final List<String> FILTERS = List.of(".a", "empty", ".a|length", "map(.)", "map(.)");

  public static void main(String[] args) {
    JqEngine engine = JqEngine.builder().build();
    StringBuilder out = new StringBuilder("start " + Prelude.loads() + "\n");
    for (String filter : FILTERS) {
      try {
        engine.compile(filter);
      } catch (JqCompileException e) {
        if (e.getMessage() == null || !e.getMessage().startsWith("jqj stub: ")) throw e;
      }
      out.append(filter).append(' ').append(Prelude.loads()).append('\n');
    }
    System.out.print(out);
    System.out.flush();
  }
}
