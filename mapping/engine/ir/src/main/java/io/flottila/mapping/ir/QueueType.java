package io.flottila.mapping.ir;

/**
 * The type of a queue (ADR-0004, ADR-0006): values split into contexts, a queue block at run time.
 * Every queue has this one type, because every queue value is an {@code xs:string}.
 */
public record QueueType() implements IrType {}
