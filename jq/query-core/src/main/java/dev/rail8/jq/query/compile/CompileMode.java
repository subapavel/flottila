package dev.rail8.jq.query.compile;

/**
 * How a tree is compiled: engine-internal, for the compile-mode oracle. The optimised compile specialises functions and
 * uses the facts the analyses prove; the conservative one makes no copy and keeps every fact at its safe value. Both
 * must give the same results.
 */
public enum CompileMode {
  /** The optimised compile, the default: with specialised copies and the facts the analyses prove. */
  OPTIMISED,
  /** The conservative compile: with no specialised copy and every analysis fact at its safe value. */
  CONSERVATIVE
}
