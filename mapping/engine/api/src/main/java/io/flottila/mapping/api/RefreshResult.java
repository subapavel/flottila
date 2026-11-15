package io.flottila.mapping.api;

import io.flottila.mapping.mapping.Diagnostic;
import java.util.List;

/**
 * The result of a refresh (ADR-0009 as amended on 2026-11-05, ADR-0015): the refreshed model with
 * its diagnostics, or the diagnostics of validation stage 1 or 2 that refuse the model.
 */
public sealed interface RefreshResult {

  /**
   * A model that stages 1 and 2 accept, refreshed.
   *
   * @param model the model's canonical bytes, with each side's fingerprint the one its bundle
   *     gives, or the stored one when the bundle fails to load
   * @param diagnostics the load errors of the sides that fail to load; when both load, the orphaned
   *     references with every other stage-3 diagnostic; sorted by {@link Diagnostic#ORDER}
   */
  record Refreshed(byte[] model, List<Diagnostic> diagnostics) implements RefreshResult {

    public Refreshed {
      diagnostics = DiagnosticOrder.sorted(diagnostics);
    }
  }

  /**
   * A model that validation stage 1 or 2 refuses (ADR-0011).
   *
   * @param diagnostics the refusing stage's diagnostics, sorted by {@link Diagnostic#ORDER}
   */
  record Refused(List<Diagnostic> diagnostics) implements RefreshResult {

    public Refused {
      diagnostics = DiagnosticOrder.sorted(diagnostics);
    }
  }
}
