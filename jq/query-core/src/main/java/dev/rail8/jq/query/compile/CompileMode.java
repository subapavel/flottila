package dev.rail8.jq.query.compile;

/**
 * How a tree is compiled: engine-internal, for the compile-mode oracle. The modes differ only in the facts the tree is
 * built from, so both must give the same results.
 */
public enum CompileMode {
  /** The optimised compile, the default: with the facts the analyses prove. */
  OPTIMISED,
  /** The conservative compile: with every analysis fact at its safe value. */
  CONSERVATIVE
}
