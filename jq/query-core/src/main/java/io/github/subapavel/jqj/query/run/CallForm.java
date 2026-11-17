package io.github.subapavel.jqj.query.run;

/**
 * How an operation is called (ADR-0009, ADR-0034): a one-result form of its arity ({@link Fn0}, {@link Fn1},
 * {@link Fn2}, and {@link FnN} for any number of arguments), which returns its result or {@link #NONE}, or
 * {@link Many}, a resumable cursor over its results. Every form takes the run state first (ADR-0030).
 */
public sealed interface CallForm permits Fn0, Fn1, Fn2, FnN, Many {
  /** What a one-result form returns for no result. */
  Object NONE = new Object();
}
