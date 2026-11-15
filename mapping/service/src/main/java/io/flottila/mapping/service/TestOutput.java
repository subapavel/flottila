package io.flottila.mapping.service;

import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The target message of a test run.
 *
 * @param kind {@value #KIND}
 * @param output the target message
 * @param diagnostics the warnings of the compile
 * @param trace the run's trace, its entries by authoring id (ADR-0015 "Trace" since #53)
 */
public record TestOutput(
    @Schema(required = true, constValue = TestOutput.KIND) String kind,
    @Schema(required = true) String output,
    @Schema(required = true) List<DiagnosticDto> diagnostics,
    @Schema(required = true) Map<String, TraceEntryDto> trace)
    implements TestResult {

  static final String KIND = "output";

  TestOutput(String output, List<DiagnosticDto> diagnostics, Map<String, TraceEntryDto> trace) {
    this(KIND, output, diagnostics, trace);
  }
}
