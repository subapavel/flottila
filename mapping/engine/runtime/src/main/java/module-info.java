/** Tier 0: the closure-compiled interpreter and its projection (ADR-0021). */
// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module io.rail8.mapping.runtime {
  requires transitive io.rail8.mapping.ir;
  requires transitive io.rail8.mapping.xdm;
  requires io.rail8.mapping.serializer;
  requires io.rail8.mapping.udf;
  requires java.xml;

  exports io.rail8.mapping.runtime;
}
