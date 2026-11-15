// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module io.rail8.mapping.ir {
  requires io.rail8.mapping.xdm;

  exports io.rail8.mapping.ir;
}
