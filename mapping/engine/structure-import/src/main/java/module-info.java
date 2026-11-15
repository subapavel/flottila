/** Reads schema bundles into the structure model (ADR-0008). */
module io.flottila.mapping.structure.importer {
  requires transitive io.flottila.mapping.structure;
  requires java.xml;
  requires xmlschema.core;
  requires json.sKema;

  exports io.flottila.mapping.structure.importer;
}
