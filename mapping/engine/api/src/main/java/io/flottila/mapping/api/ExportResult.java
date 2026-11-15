package io.flottila.mapping.api;

import io.flottila.mapping.mapping.Diagnostic;
import java.util.List;

/** The result of an export: the stylesheet and its helper module, or the refusing diagnostics. */
public sealed interface ExportResult {

  /**
   * A mapping that passed every validation stage and exported whole (ADR-0023).
   *
   * @param stylesheet the exported stylesheet
   * @param helperModule the text of {@code po-runtime.xsl}, which the stylesheet imports from its
   *     own directory
   */
  record Exported(String stylesheet, String helperModule) implements ExportResult {}

  /**
   * A mapping refused by loading, by a structure load error or by stage 3, as compile refuses it.
   *
   * @param diagnostics the refusing stage's diagnostics, sorted by {@link Diagnostic#ORDER}
   */
  record Refused(List<Diagnostic> diagnostics) implements ExportResult {

    public Refused {
      diagnostics = DiagnosticOrder.sorted(diagnostics);
    }
  }
}
