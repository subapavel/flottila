package dev.rail8.mapping.studio;

/**
 * An authoring element, as stage 2 and stage 3 report it.
 *
 * @param kind {@value #KIND}
 * @param authoringId the element's authoring id
 */
record AuthoringIdLocation(String kind, String authoringId) implements LocationDto {

  static final String KIND = "authoring-id";

  AuthoringIdLocation(String authoringId) {
    this(KIND, authoringId);
  }
}
