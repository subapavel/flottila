package io.flottila.mapping.service;

import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A test run that stopped with a runtime error: no output (ADR-0015, "Diagnostics and errors").
 *
 * @param kind {@value #KIND}
 * @param error the runtime error
 * @param diagnostics the warnings of the compile
 * @param trace the trace up to the error, at a timeout the trace so far (ADR-0015 "Trace", ADR-0014
 *     since #53)
 */
public record TestError(
    @Schema(required = true, constValue = TestError.KIND) String kind,
    @Schema(required = true) RuntimeErrorDto error,
    @Schema(required = true) List<DiagnosticDto> diagnostics,
    @Schema(required = true) Map<String, TraceEntryDto> trace)
    implements TestResult {

  static final String KIND = "error";

  TestError(
      RuntimeErrorDto error, List<DiagnosticDto> diagnostics, Map<String, TraceEntryDto> trace) {
    this(KIND, error, diagnostics, trace);
  }
}
