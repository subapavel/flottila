package io.flottila.mapping.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A place in the mapping model's document, as stage 1 reports it (ADR-0011).
 *
 * @param kind {@value #KIND}
 * @param pointer the RFC 6901 JSON pointer; {@code ""} is the document itself
 */
public record PointerLocation(
    @Schema(required = true, constValue = PointerLocation.KIND) String kind,
    @Schema(required = true) String pointer)
    implements LocationDto {

  static final String KIND = "pointer";

  PointerLocation(String pointer) {
    this(KIND, pointer);
  }
}
