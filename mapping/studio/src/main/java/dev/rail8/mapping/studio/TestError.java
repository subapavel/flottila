package dev.rail8.mapping.studio;

import java.util.List;
import java.util.Map;

/**
 * A test run that stopped with a runtime error: no output.
 *
 * @param kind {@value #KIND}
 * @param error the runtime error
 * @param diagnostics the warnings of the compile
 * @param trace the trace up to the error, at a timeout the trace so far
 */
record TestError(
    String kind,
    RuntimeErrorDto error,
    List<DiagnosticDto> diagnostics,
    Map<String, TraceEntryDto> trace)
    implements TestResult {

  static final String KIND = "error";

  TestError(
      RuntimeErrorDto error, List<DiagnosticDto> diagnostics, Map<String, TraceEntryDto> trace) {
    this(KIND, error, diagnostics, trace);
  }
}
