package io.flottila.mapping.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.flottila.mapping.ir.AuthoringId;
import io.flottila.mapping.ir.TargetDocument;
import java.util.List;

/**
 * Reads a {@link LoadResult} or a {@link LowerResult} for an assertion. Tests match a diagnostic on
 * its severity, code and location, never on its message (ADR-0015); the message only has to be
 * there.
 */
final class LoadResults {

  private LoadResults() {}

  /** The loaded model; a refusal fails the test and lists its diagnostics. */
  static MappingModel loaded(LoadResult result) {
    return switch (result) {
      case Loaded l -> l.model();
      case Refused r -> throw new AssertionError("expected Loaded, got Refused " + render(r));
    };
  }

  /** The refusal's diagnostics as {@code "<severity> <code> at <location>"}, in reported order. */
  static List<String> refused(LoadResult result) {
    return switch (result) {
      case Loaded l -> throw new AssertionError("expected Refused, got Loaded " + l.model());
      case Refused r -> render(r);
    };
  }

  /** The lowered document; a refusal fails the test and lists its diagnostics. */
  static TargetDocument lowered(LowerResult result) {
    return switch (result) {
      case Lowered(TargetDocument document) -> document;
      case Refused r -> throw new AssertionError("expected Lowered, got Refused " + render(r));
    };
  }

  /** A lowering refusal's diagnostics as {@code "<severity> <code> at <location>"}, in order. */
  static List<String> refused(LowerResult result) {
    return switch (result) {
      case Lowered(TargetDocument document) ->
          throw new AssertionError("expected Refused, got Lowered " + document);
      case Refused r -> render(r);
    };
  }

  static String error(String code, Location location) {
    return "error " + code + " at " + describe(location);
  }

  static String errorAtPointer(String code, String pointer) {
    return error(code, new AtPointer(pointer));
  }

  static String errorAtId(String code, String authoringId) {
    return error(code, new AtAuthoringId(new AuthoringId(authoringId)));
  }

  private static List<String> render(Refused refused) {
    assertThat(refused.diagnostics()).as("a refusal names at least one diagnostic").isNotEmpty();
    return refused.diagnostics().stream().map(LoadResults::renderOne).toList();
  }

  private static String renderOne(Diagnostic d) {
    assertThat(d.message()).as("message of %s", d.code()).isNotBlank();
    String severity =
        switch (d.severity()) {
          case ERROR -> "error";
          case WARNING -> "warning";
        };
    return severity + " " + d.code() + " at " + describe(d.location());
  }

  private static String describe(Location location) {
    return switch (location) {
      case AtPointer p -> "pointer " + p.pointer();
      case AtAuthoringId a -> "id " + a.id().value();
      case AtFilePosition f -> "file " + f.file() + ":" + f.line() + ":" + f.column();
    };
  }
}
