package dev.rail8.mapping.ir;

/**
 * The type of a queue: values split into contexts, a queue block at run time. Every queue has this
 * one type, because every queue value is an {@code xs:string}.
 */
public record QueueType() implements IrType {}
