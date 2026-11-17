package io.github.subapavel.jqj.query.compile;

/**
 * How a tree is compiled (ADR-0018, ADR-0030): engine-internal, for the compile-mode oracle. Both modes build the same
 * tree until spec 2b's analyses.
 */
public enum CompileMode {
  /** The optimised compile, the default: with the facts the analyses prove. */
  OPTIMISED,
  /** The conservative compile: with every analysis fact at its safe value. */
  CONSERVATIVE
}
