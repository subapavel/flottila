package dev.rail8.mapping.api;

import dev.rail8.mapping.mapping.AtFilePosition;
import dev.rail8.mapping.mapping.Diagnostic;
import dev.rail8.mapping.mapping.Severity;
import dev.rail8.mapping.structure.RootSelector;
import dev.rail8.mapping.structure.SchemaBundle;
import dev.rail8.mapping.structure.importer.ReferenceResolver;
import dev.rail8.mapping.structure.importer.StructureLoadException;
import dev.rail8.mapping.structure.importer.StructureReader;

/** The engine facade's entry point for loading a structure from a schema bundle. */
public final class Structures {

  private Structures() {}

  /**
   * Loads the structure under {@code root}. Load errors are a result, every one of the bundle's,
   * never an exception; an unchecked exception from {@code resolver} passes through unchanged.
   *
   * @param resolver the host's resolver of the bundle's references, through which every file is
   *     read
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
