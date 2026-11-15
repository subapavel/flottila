package io.flottila.mapping.ir;

/**
 * A read of a variable in scope (ADR-0006). The only node that stores its type.
 *
 * @param name the variable's name
 * @param type the type the variable is bound with
 * @param origin the authoring element that reads it
 */
public record VarRef(String name, AtomicType type, AuthoringId origin) implements Expr {}
