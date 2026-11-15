package io.flottila.mapping.ir;

/**
 * The atomic types of IR values (ADR-0006). Spec 1 uses two: a queue's value is an {@code
 * xs:string} (ADR-0004), and a slot number is an {@code xs:integer}. The other types of ADR-0004
 * join with the function boxes that need them.
 */
public enum AtomicType implements IrType {
  STRING,
  INTEGER
}
