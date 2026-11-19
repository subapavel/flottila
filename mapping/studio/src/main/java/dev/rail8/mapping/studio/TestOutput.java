package dev.rail8.mapping.studio;

import java.util.List;
import java.util.Map;

/**
 * The target message of a test run.
 *
 * @param kind {@value #KIND}
 * @param output the target message
 * @param diagnostics the warnings of the compile
 * @param trace the run's trace, its entries by authoring id
 */
record TestOutput(
    String kind, String output, List<DiagnosticDto> diagnostics, Map<String, TraceEntryDto> trace)
    implements TestResult {

  static final String KIND = "output";

  TestOutput(String output, List<DiagnosticDto> diagnostics, Map<String, TraceEntryDto> trace) {
    this(KIND, output, diagnostics, trace);
  }
}
