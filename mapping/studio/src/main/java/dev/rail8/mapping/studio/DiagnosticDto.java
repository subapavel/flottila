package dev.rail8.mapping.studio;

import dev.rail8.mapping.mapping.Diagnostic;
import java.util.List;
import java.util.Locale;

/**
 * A design-time error or warning, on the wire.
 *
 * @param severity {@code error} or {@code warning}
 * @param code the stable kebab-case code of an engine catalogue
 * @param message what is wrong, in English
 * @param location where the diagnostic points
 */
record DiagnosticDto(String severity, String code, String message, LocationDto location) {

  static DiagnosticDto of(Diagnostic diagnostic) {
    return new DiagnosticDto(
        diagnostic.severity().name().toLowerCase(Locale.ROOT),
        diagnostic.code(),
        diagnostic.message(),
        LocationDto.of(diagnostic.location()));
  }

  /** The diagnostics in their order. */
  static List<DiagnosticDto> all(List<Diagnostic> diagnostics) {
    return diagnostics.stream().map(DiagnosticDto::of).toList();
  }
}
