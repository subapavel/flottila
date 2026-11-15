package io.flottila.mapping.ir;

/**
 * A constant {@code xs:string} (ADR-0006), such as a box parameter's value as an argument of a
 * {@link FunCall}.
 *
 * @param value the string; {@code ""} is allowed
 * @param origin the authoring element the value comes from
 */
public record Literal(String value, AuthoringId origin) implements Expr {}
