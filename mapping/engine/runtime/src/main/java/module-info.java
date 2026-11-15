/** Tier 0: the closure-compiled interpreter and its projection (ADR-0021). */
// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module dev.rail8.mapping.runtime {
  requires transitive dev.rail8.mapping.ir;
  requires transitive dev.rail8.mapping.xdm;
  requires dev.rail8.mapping.serializer;
  requires dev.rail8.mapping.udf;
  requires java.xml;

  exports dev.rail8.mapping.runtime;
}
