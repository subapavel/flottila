// jmh-core 1.37 has no Automatic-Module-Name, so jmh.core is its file name; the bench runs on the class path
// (ADR-0013), where the name is never resolved.
@SuppressWarnings("requires-automatic")
module io.github.subapavel.jqj.bench {
  requires io.github.subapavel.jqj;
  // CompileMode, the type of Diagnostics' mode (ADR-0020 #119 amendment).
  requires io.github.subapavel.jqj.query;
  requires jmh.core;
}
