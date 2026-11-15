/** Writes output documents byte for byte as ADR-0022 fixes them. */
// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module dev.rail8.mapping.serializer {
  requires dev.rail8.mapping.xdm;

  exports dev.rail8.mapping.serializer;
}
