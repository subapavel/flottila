/** Reads schema bundles into the structure model. */
// javac's module lint: the name component rail8 ends in a digit
@SuppressWarnings("module")
module dev.rail8.mapping.structure.importer {
  requires transitive dev.rail8.mapping.structure;
  requires java.xml;
  requires xmlschema.core;
  requires json.sKema;

  exports dev.rail8.mapping.structure.importer;
}
