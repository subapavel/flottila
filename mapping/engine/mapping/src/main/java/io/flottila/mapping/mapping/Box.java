package io.flottila.mapping.mapping;

/**
 * A box of a function graph, by its {@code kind} (ADR-0010): a source-field box, a standard
 * function, a UDF or an unresolved function.
 */
public sealed interface Box
    permits SourceFieldBox, StandardFunctionBox, UdfBox, UnresolvedFunctionBox {}
