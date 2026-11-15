package io.flottila.mapping.ir;

/**
 * The type of an IR value (ADR-0006): an atomic value or a queue. A node's type is derived from the
 * node; only {@link VarRef} stores one, and a {@link FunctionRef} names the types of its calls.
 */
public sealed interface IrType permits AtomicType, QueueType {}
