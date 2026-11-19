package dev.rail8.mapping.studio;

/**
 * A place in the mapping model's document, as stage 1 reports it.
 *
 * @param kind {@value #KIND}
 * @param pointer the RFC 6901 JSON pointer; {@code ""} is the document itself
 */
record PointerLocation(String kind, String pointer) implements LocationDto {

  static final String KIND = "pointer";

  PointerLocation(String pointer) {
    this(KIND, pointer);
  }
}
