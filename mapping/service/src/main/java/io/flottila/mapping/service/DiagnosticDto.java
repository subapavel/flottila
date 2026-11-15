package io.flottila.mapping.service;

import io.flottila.mapping.mapping.Diagnostic;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A design-time error or warning (ADR-0015), on the wire.
 *
 * @param severity {@code error} or {@code warning}
 * @param code the stable kebab-case code of an engine catalogue
 * @param message what is wrong, in English
 * @param location where the diagnostic points
 */
public record DiagnosticDto(
    @Schema(
            required = true,
            enumeration = {"error", "warning"})
        String severity,
    @Schema(required = true) String code,
    @Schema(required = true) String message,
    @Schema(required = true) LocationDto location) {

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
