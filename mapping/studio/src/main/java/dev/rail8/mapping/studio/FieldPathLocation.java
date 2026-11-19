package dev.rail8.mapping.studio;

/**
 * A field of the source or the target structure, by its field path. No engine diagnostic is located
 * here yet.
 *
 * @param kind {@value #KIND}
 * @param side {@code source} or {@code target}
 * @param fieldPath the field's path
 */
record FieldPathLocation(String kind, String side, String fieldPath) implements LocationDto {

  static final String KIND = "field-path";

  FieldPathLocation(String side, String fieldPath) {
    this(KIND, side, fieldPath);
  }
}
