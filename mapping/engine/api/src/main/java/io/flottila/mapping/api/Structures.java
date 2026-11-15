package io.flottila.mapping.api;

import io.flottila.mapping.mapping.AtFilePosition;
import io.flottila.mapping.mapping.Diagnostic;
import io.flottila.mapping.mapping.Severity;
import io.flottila.mapping.structure.RootSelector;
import io.flottila.mapping.structure.SchemaBundle;
import io.flottila.mapping.structure.importer.ReferenceResolver;
import io.flottila.mapping.structure.importer.StructureLoadException;
import io.flottila.mapping.structure.importer.StructureReader;

/** The engine facade's entry point for loading a structure from a schema bundle (ADR-0008). */
public final class Structures {

  private Structures() {}

  /**
   * Loads the structure under {@code root}. Load errors are a result, every one of the bundle's
   * (ADR-0008), never an exception; an unchecked exception from {@code resolver} passes through
   * unchanged.
   *
   * @param resolver the host's resolver of the bundle's references (ADR-0014 "Reference resolver
   *     shape"), through which every file is read
   */
  public static StructureResult load(
      ReferenceResolver resolver, SchemaBundle bundle, RootSelector root) {
    try {
      return new StructureResult.Loaded(new StructureReader(resolver).read(bundle, root));
    } catch (StructureLoadException e) {
      return new StructureResult.Refused(
          e.errors().stream()
              .map(
                  error ->
                      new Diagnostic(
                          Severity.ERROR,
                          error.code(),
                          new AtFilePosition(error.file(), error.line(), error.column()),
                          error.getMessage()))
              .toList());
    }
  }
}
