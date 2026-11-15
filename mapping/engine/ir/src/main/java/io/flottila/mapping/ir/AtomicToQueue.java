package io.flottila.mapping.ir;

/**
 * A single value where a queue belongs (ADR-0006 as amended by #83): at a context or queue
 * function's input, a generating function's value is one context with one value (ADR-0004). It is a
 * queue over a {@code STRING}, it is not traced, and it exports as {@code [(value)]}.
 *
 * @param value the single value, a {@code STRING}
 * @param origin the box whose value it is
 */
public record AtomicToQueue(Expr value, AuthoringId origin) implements Expr {}
