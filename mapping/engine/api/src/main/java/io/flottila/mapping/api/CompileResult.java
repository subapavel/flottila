package io.flottila.mapping.api;

import io.flottila.mapping.mapping.Diagnostic;
import java.util.List;

/** The result of a compile: the compiled mapping, or the diagnostics that refuse it. */
public sealed interface CompileResult {

  /**
   * A mapping that passed every validation stage and compiled.
   *
   * @param mapping what {@link EngineFacade#run} runs
   */
  record Compiled(CompiledMapping mapping) implements CompileResult {}

  /**
   * A mapping refused by loading, by a structure load error or by stage 3 (ADR-0011).
   *
   * @param diagnostics the refusing stage's diagnostics, sorted by {@link Diagnostic#ORDER}
   */
  record Refused(List<Diagnostic> diagnostics) implements CompileResult {

    public Refused {
      diagnostics = DiagnosticOrder.sorted(diagnostics);
    }
  }
}
