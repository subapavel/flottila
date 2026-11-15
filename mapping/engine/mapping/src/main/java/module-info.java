/** The mapping-model front end: the format, its validation and its canonical writer (ADR-0010). */
module io.flottila.mapping.mapping {
  requires transitive io.flottila.mapping.ir;
  requires transitive io.flottila.mapping.structure;
  requires io.flottila.mapping.xdm;
  requires json.sKema;

  exports io.flottila.mapping.mapping;
}
