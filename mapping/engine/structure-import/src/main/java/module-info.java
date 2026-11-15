/** Reads schema bundles into the structure model (ADR-0008). */
// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module io.rail8.mapping.structure.importer {
  requires transitive io.rail8.mapping.structure;
  requires java.xml;
  requires xmlschema.core;
  requires json.sKema;

  exports io.rail8.mapping.structure.importer;
}
