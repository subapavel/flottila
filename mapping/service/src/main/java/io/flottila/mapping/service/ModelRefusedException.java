package io.flottila.mapping.service;

import java.io.Serial;
import java.util.List;

/** A model that validation stage 1 or 2, or a structure load, refuses: the 422 of ADR-0024. */
final class ModelRefusedException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  private final transient List<DiagnosticDto> diagnostics;

  ModelRefusedException(String detail, List<DiagnosticDto> diagnostics) {
    super(detail);
    this.diagnostics = List.copyOf(diagnostics);
  }

  List<DiagnosticDto> diagnostics() {
    return diagnostics;
  }
}
