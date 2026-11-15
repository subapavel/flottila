module io.flottila.mapping.xslt.export {
  requires io.flottila.mapping.mapping;
  // transitive: XsltExporter.export takes the IR's TargetDocument (ADR-0023)
  requires transitive io.flottila.mapping.ir;
  requires io.flottila.mapping.structure;

  exports io.flottila.mapping.xslt.export;
}
