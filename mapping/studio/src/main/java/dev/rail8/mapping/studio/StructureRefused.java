package dev.rail8.mapping.studio;

import java.util.List;

/**
 * A structure load error, at its position in a schema file (a result, never a 4xx).
 *
 * @param kind {@value #KIND}
 * @param diagnostics the load error
 */
record StructureRefused(String kind, List<DiagnosticDto> diagnostics) implements StructureResponse {

  static final String KIND = "refused";

  StructureRefused(List<DiagnosticDto> diagnostics) {
    this(KIND, diagnostics);
  }
}
