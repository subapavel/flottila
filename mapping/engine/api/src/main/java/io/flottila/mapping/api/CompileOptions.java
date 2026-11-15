package io.flottila.mapping.api;

/**
 * How a mapping is compiled (ADR-0021): a deploy runs optimised IR untraced, and the editor's test
 * run unoptimised IR traced, so every box has its node. Every combination gives the same output
 * bytes (the metamorphic variants, ADR-0018).
 *
 * @param optimised whether the optimiser runs on the IR
 * @param traced whether the compiled nodes report to a tracer
 */
public record CompileOptions(boolean optimised, boolean traced) {

  /** Use through {@code engine/api} outside the editor: optimised, untraced. */
  public static final CompileOptions DEPLOY = new CompileOptions(true, false);

  /** The editor's test run: unoptimised, traced. */
  public static final CompileOptions TEST_RUN = new CompileOptions(false, true);
}
