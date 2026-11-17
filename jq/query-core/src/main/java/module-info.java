// javac's module lint warns that the name component rail8 ends in a digit; the name is the project's
@SuppressWarnings("module")
module dev.rail8.jq.query {
  exports dev.rail8.jq.query.ir;
  exports dev.rail8.jq.query.run;
  exports dev.rail8.jq.query.compile;
  exports dev.rail8.jq.query.analysis;
}
