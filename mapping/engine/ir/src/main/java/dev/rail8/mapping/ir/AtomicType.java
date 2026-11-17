package dev.rail8.mapping.ir;

/**
 * The atomic types of IR values: a queue's value is an {@code xs:string}, and a slot number is an
 * {@code xs:integer}.
 */
public enum AtomicType implements IrType {
  STRING,
  INTEGER
}
