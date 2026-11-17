package io.github.subapavel.jqj.query.analysis;

/**
 * A Call's kind (GLOSSARY "Call kind", ADR-0006, ADR-0047 "Amendment (ticket #118)"): the fact by which the interpreter
 * runs a tail call as a pending jump. The conservative compile stores no tail kind (ADR-0018).
 */
public enum CallKind {
  /** A call from main, or from a function into a function outside its recursive group. */
  PLAIN,
  /** A call from a function into its recursive group that is no tail call. */
  RECURSIVE,
  /** A tail call of the caller itself. */
  TAIL_SELF,
  /** A tail call of another function of the caller's recursive group. */
  TAIL_GROUP
}
