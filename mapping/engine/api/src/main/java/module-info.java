// javac's module lint: the name component rail8 ends in a digit
@SuppressWarnings("module")
module dev.rail8.mapping.api {
  requires transitive dev.rail8.mapping.mapping;
  // transitive: EngineFacade.run throws runtime's MalformedInputException
  requires transitive dev.rail8.mapping.runtime;
  // transitive: Structures.load, compile and export take its ReferenceResolver
  requires transitive dev.rail8.mapping.structure.importer;
  requires transitive dev.rail8.mapping.structure;
  requires dev.rail8.mapping.xslt.export;
  requires dev.rail8.mapping.udf.compiler;

  exports dev.rail8.mapping.api;
}
