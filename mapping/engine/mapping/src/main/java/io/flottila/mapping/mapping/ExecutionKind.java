package io.flottila.mapping.mapping;

/** What one call of a UDF sees: one value, the values of one context, or the whole queue. */
public enum ExecutionKind {
  SINGLE,
  CONTEXT,
  QUEUE
}
