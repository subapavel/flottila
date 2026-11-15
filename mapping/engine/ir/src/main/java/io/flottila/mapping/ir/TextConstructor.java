package io.flottila.mapping.ir;

/**
 * A text node of the output (ADR-0006).
 *
 * @param value the text, a {@code STRING}
 * @param origin the target field mapping
 */
public record TextConstructor(Expr value, AuthoringId origin) implements Expr {}
