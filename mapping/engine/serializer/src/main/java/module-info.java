/** Writes output documents byte for byte. */
// javac's module lint: the name component rail8 ends in a digit
@SuppressWarnings("module")
module dev.rail8.mapping.serializer {
  requires dev.rail8.mapping.xdm;

  exports dev.rail8.mapping.serializer;
}
