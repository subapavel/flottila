// javac's module lint: the name component rail8 ends in a digit
@SuppressWarnings("module")
module dev.rail8.mapping.xslt.export {
  requires dev.rail8.mapping.mapping;
  // transitive: XsltExporter.export takes the IR's TargetDocument
  requires transitive dev.rail8.mapping.ir;
  requires dev.rail8.mapping.structure;

  exports dev.rail8.mapping.xslt.export;
}
