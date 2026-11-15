package io.flottila.mapping.service;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A structure load error, at its position in a schema file (ADR-0024: a result, never a 4xx).
 *
 * @param kind {@value #KIND}
 * @param diagnostics the load error
 */
public record StructureRefused(
    @Schema(required = true, constValue = StructureRefused.KIND) String kind,
    @Schema(required = true) List<DiagnosticDto> diagnostics)
    implements StructureResponse {

  static final String KIND = "refused";

  StructureRefused(List<DiagnosticDto> diagnostics) {
    this(KIND, diagnostics);
  }
}
