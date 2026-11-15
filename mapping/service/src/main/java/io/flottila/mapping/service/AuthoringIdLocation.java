package io.flottila.mapping.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An authoring element, as stage 2 and stage 3 report it (ADR-0011).
 *
 * @param kind {@value #KIND}
 * @param authoringId the element's authoring id
 */
public record AuthoringIdLocation(
    @Schema(required = true, constValue = AuthoringIdLocation.KIND) String kind,
    @Schema(required = true) String authoringId)
    implements LocationDto {

  static final String KIND = "authoring-id";

  AuthoringIdLocation(String authoringId) {
    this(KIND, authoringId);
  }
}
