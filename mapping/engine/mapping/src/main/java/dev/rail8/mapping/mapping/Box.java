package dev.rail8.mapping.mapping;

/**
 * A box of a function graph, by its {@code kind}: a source-field box, a standard function, a UDF or
 * an unresolved function.
 */
public sealed interface Box
    permits SourceFieldBox, StandardFunctionBox, UdfBox, UnresolvedFunctionBox {}
