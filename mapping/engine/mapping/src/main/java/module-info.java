/** The mapping-model front end: the format, its validation and its canonical writer (ADR-0010). */
// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module dev.rail8.mapping.mapping {
  requires transitive dev.rail8.mapping.ir;
  requires transitive dev.rail8.mapping.structure;
  requires dev.rail8.mapping.xdm;
  requires json.sKema;

  exports dev.rail8.mapping.mapping;
}
