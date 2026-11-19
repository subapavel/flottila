package dev.rail8.mapping.studio;

import java.util.List;

/**
 * A working copy that stage 1, 2 or 3 or a structure load refused: no output.
 *
 * @param kind {@value #KIND}
 * @param diagnostics the refusing stage's diagnostics
 */
record TestRefused(String kind, List<DiagnosticDto> diagnostics) implements TestResult {

  static final String KIND = "refused";

  TestRefused(List<DiagnosticDto> diagnostics) {
    this(KIND, diagnostics);
  }
}
