/** Reads schema bundles into the structure model (ADR-0008). */
// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module dev.rail8.mapping.structure.importer {
  requires transitive dev.rail8.mapping.structure;
  requires java.xml;
  requires xmlschema.core;
  requires json.sKema;

  exports dev.rail8.mapping.structure.importer;
}
