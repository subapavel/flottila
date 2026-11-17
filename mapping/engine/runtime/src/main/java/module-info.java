/** Tier 0: the closure-compiled interpreter and its projection. */
// javac's module lint: the name component rail8 ends in a digit
@SuppressWarnings("module")
module dev.rail8.mapping.runtime {
  requires transitive dev.rail8.mapping.ir;
  requires transitive dev.rail8.mapping.xdm;
  requires dev.rail8.mapping.serializer;
  requires dev.rail8.mapping.udf;
  requires java.xml;

  exports dev.rail8.mapping.runtime;
}
