/**
 * The mapping engine's embedding API: compile a mapping once, run it on any number of messages. Its
 * signatures use JDK types and its own only.
 */
// javac's module lint: the name component rail8 ends in a digit
@SuppressWarnings("module")
module dev.rail8.mapping.embed {
  requires dev.rail8.mapping.api;

  exports dev.rail8.mapping.embed;
}
