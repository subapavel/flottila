package io.flottila.mapping.mapping;

import java.util.ArrayList;
import java.util.List;

/**
 * A mapping model that a validation stage refused (ADR-0011): on load, stage 1 or 2; on lowering,
 * stage 3.
 *
 * @param diagnostics the stage's diagnostics, sorted by {@link Diagnostic#ORDER}
 */
public record Refused(List<Diagnostic> diagnostics) implements LoadResult, LowerResult {

  public Refused {
    List<Diagnostic> sorted = new ArrayList<>(diagnostics);
    sorted.sort(Diagnostic.ORDER);
    diagnostics = List.copyOf(sorted);
  }
}
