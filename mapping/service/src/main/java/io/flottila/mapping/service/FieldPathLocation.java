package io.flottila.mapping.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A field of the source or the target structure, by its field path (ADR-0015, ADR-0007). No engine
 * diagnostic is located here yet (#105).
 *
 * @param kind {@value #KIND}
 * @param side {@code source} or {@code target}
 * @param fieldPath the field's path
 */
public record FieldPathLocation(
    @Schema(required = true, constValue = FieldPathLocation.KIND) String kind,
    @Schema(
            required = true,
            enumeration = {"source", "target"})
        String side,
    @Schema(required = true) String fieldPath)
    implements LocationDto {

  static final String KIND = "field-path";

  FieldPathLocation(String side, String fieldPath) {
    this(KIND, side, fieldPath);
  }
}
