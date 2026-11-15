/** The one structure model (ADR-0008): records only, no dependencies. */
// javac's module lint: the name component rail8 ends in a digit (D-149, D-151)
@SuppressWarnings("module")
module io.rail8.mapping.structure {
  exports io.rail8.mapping.structure;
}
