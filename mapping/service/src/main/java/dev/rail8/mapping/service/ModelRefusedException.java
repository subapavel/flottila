package dev.rail8.mapping.service;

import java.io.Serial;
import java.util.List;

/**
 * A model that validation stage 1 or 2, or a structure load, refuses: a 422 {@code model-refused}.
 */
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
