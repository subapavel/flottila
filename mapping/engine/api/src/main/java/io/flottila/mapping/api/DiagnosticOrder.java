package io.flottila.mapping.api;

import io.flottila.mapping.mapping.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/** The order the facade's refusals give their diagnostics in. */
final class DiagnosticOrder {

  private DiagnosticOrder() {}

  /** An unmodifiable copy of {@code diagnostics}, sorted by {@link Diagnostic#ORDER}. */
  static List<Diagnostic> sorted(List<Diagnostic> diagnostics) {
    List<Diagnostic> sorted = new ArrayList<>(diagnostics);
    sorted.sort(Diagnostic.ORDER);
    return List.copyOf(sorted);
  }
}
