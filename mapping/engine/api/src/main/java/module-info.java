// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module io.rail8.mapping.api {
  requires transitive io.rail8.mapping.mapping;
  // transitive: EngineFacade.run throws runtime's MalformedInputException (ADR-0016)
  requires transitive io.rail8.mapping.runtime;
  // transitive: Structures.load, compile and export take its ReferenceResolver (ADR-0014)
  requires transitive io.rail8.mapping.structure.importer;
  requires transitive io.rail8.mapping.structure;
  requires io.rail8.mapping.xslt.export;
  requires io.rail8.mapping.udf.compiler;

  exports io.rail8.mapping.api;
}
