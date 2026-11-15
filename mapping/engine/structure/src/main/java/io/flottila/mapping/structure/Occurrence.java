package io.flottila.mapping.structure;

/**
 * An effective occurrence (ADR-0008): a field's minimum and maximum number of occurrences under one
 * occurrence of its parent field, after every enclosing particle is taken into account.
 */
public record Occurrence(long min, long max) {

  /** The maximum of a field that may repeat without bound. */
  public static final long UNBOUNDED = Long.MAX_VALUE;

  @Override
  public String toString() {
    return min + ".." + (max == UNBOUNDED ? "unbounded" : Long.toString(max));
  }
}
