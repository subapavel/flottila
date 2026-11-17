// jmh-core 1.37 has no Automatic-Module-Name, so jmh.core is its file name; the bench runs on the class path
// (ADR-0013), where the name is never resolved.
// javac's module lint warns that the name component rail8 ends in a digit; the name is the project's
@SuppressWarnings({"module", "requires-automatic"})
module dev.rail8.jq.bench {
  requires dev.rail8.jq;
  // CompileMode, the type of Diagnostics' mode (ADR-0020 #119 amendment).
  requires dev.rail8.jq.query;
  requires jmh.core;
}
