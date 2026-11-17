// javac's module lint warns that the name component rail8 ends in a digit; the name is the project's
@SuppressWarnings("module")
module dev.rail8.jq.oracle {
  requires dev.rail8.jq.cli;
}
