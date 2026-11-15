/** Tier 0: the closure-compiled interpreter and its projection (ADR-0021). */
module io.flottila.mapping.runtime {
  requires transitive io.flottila.mapping.ir;
  requires transitive io.flottila.mapping.xdm;
  requires io.flottila.mapping.serializer;
  requires io.flottila.mapping.udf;
  requires java.xml;

  exports io.flottila.mapping.runtime;
}
