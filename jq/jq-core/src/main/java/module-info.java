// javac's module lint warns that the name component rail8 ends in a digit; the name is the project's
@SuppressWarnings("module")
module dev.rail8.jq {
  requires dev.rail8.jq.query;

  exports dev.rail8.jq;
  exports dev.rail8.jq.host;
  exports dev.rail8.jq.value;
}
