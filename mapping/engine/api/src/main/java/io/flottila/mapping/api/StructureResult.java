package io.flottila.mapping.api;

import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.structure.Structure;
import java.util.List;

/** The result of loading a structure: the structure, or the load errors that refused it. */
public sealed interface StructureResult {

  record Loaded(Structure structure) implements StructureResult {}

  /**
   * Every load error of the bundle (ADR-0008), each a diagnostic at its position in a schema file
   * ({@code AtFilePosition}).
   *
   * @param diagnostics the errors, with codes of {@code StructureImportDiagnostics}: files in
   *     bundle order, each file in document order
   */
  record Refused(List<Diagnostic> diagnostics) implements StructureResult {}
}
