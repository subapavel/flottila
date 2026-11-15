package io.flottila.mapping.mapping;

import java.util.Comparator;

/**
 * A design-time error or warning with a stable code and one location (ADR-0015). Tests and the
 * editor match on the code; the message is English text for a person.
 *
 * @param severity error or warning
 * @param code the stable kebab-case code, one of {@link MappingDiagnostics} or another module's
 *     catalogue class (ADR-0018)
 * @param location where the diagnostic points
 * @param message what is wrong
 */
public record Diagnostic(Severity severity, String code, Location location, String message) {

  /**
   * The order of a report: by location, then code. Pointers and authoring ids sort by their text,
   * file positions by file, then line and column as numbers; pointers come before authoring ids,
   * and those before file positions.
   */
  public static final Comparator<Diagnostic> ORDER =
      Comparator.comparing((Diagnostic d) -> Place.of(d.location()), Place.ORDER)
          .thenComparing(Diagnostic::code);

  /** A location as a sort key: its kind, its text, and a file position's line and column. */
  private record Place(int kind, String text, int line, int column) {

    static final Comparator<Place> ORDER =
        Comparator.comparingInt(Place::kind)
            .thenComparing(Place::text)
            .thenComparingInt(Place::line)
            .thenComparingInt(Place::column);

    static Place of(Location location) {
      return switch (location) {
        case AtPointer p -> new Place(0, p.pointer(), 0, 0);
        case AtAuthoringId a -> new Place(1, a.id().value(), 0, 0);
        case AtFilePosition f -> new Place(2, f.file(), f.line(), f.column());
      };
    }
  }
}
