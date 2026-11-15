module io.flottila.mapping.api {
  requires transitive io.flottila.mapping.mapping;
  // transitive: EngineFacade.run throws runtime's MalformedInputException (ADR-0016)
  requires transitive io.flottila.mapping.runtime;
  // transitive: Structures.load, compile and export take its ReferenceResolver (ADR-0014)
  requires transitive io.flottila.mapping.structure.importer;
  requires transitive io.flottila.mapping.structure;
  requires io.flottila.mapping.xslt.export;
  requires io.flottila.mapping.udf.compiler;

  exports io.flottila.mapping.api;
}
