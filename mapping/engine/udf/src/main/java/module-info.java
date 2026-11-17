// javac's module lint: the name component rail8 ends in a digit
@SuppressWarnings("module")
module dev.rail8.mapping.udf.compiler {
  requires dev.rail8.mapping.udf;
  requires java.compiler;
}
