package io.flottila.mapping.service;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A working copy that stage 1, 2 or 3 or a structure load refused: no output (ADR-0015).
 *
 * @param kind {@value #KIND}
 * @param diagnostics the refusing stage's diagnostics
 */
public record TestRefused(
    @Schema(required = true, constValue = TestRefused.KIND) String kind,
    @Schema(required = true) List<DiagnosticDto> diagnostics)
    implements TestResult {

  static final String KIND = "refused";

  TestRefused(List<DiagnosticDto> diagnostics) {
    this(KIND, diagnostics);
  }
}
