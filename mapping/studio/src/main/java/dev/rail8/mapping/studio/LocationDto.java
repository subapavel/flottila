package dev.rail8.mapping.studio;

import dev.rail8.mapping.mapping.AtAuthoringId;
import dev.rail8.mapping.mapping.AtFilePosition;
import dev.rail8.mapping.mapping.AtPointer;
import dev.rail8.mapping.mapping.Location;

/**
 * Where a diagnostic points, on the wire: a union on {@code kind}. The engine locates no diagnostic
 * at a {@link FieldPathLocation} yet, so {@link #of} never gives one.
 */
sealed interface LocationDto
    permits PointerLocation, AuthoringIdLocation, FilePositionLocation, FieldPathLocation {

  static LocationDto of(Location location) {
    return switch (location) {
      case AtPointer p -> new PointerLocation(p.pointer());
      case AtAuthoringId a -> new AuthoringIdLocation(a.id().value());
      case AtFilePosition f -> new FilePositionLocation(f.file(), f.line(), f.column());
    };
  }
}
