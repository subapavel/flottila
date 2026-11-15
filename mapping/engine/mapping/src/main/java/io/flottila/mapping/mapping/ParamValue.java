package io.flottila.mapping.mapping;

/** The value of a box parameter: a string or a table of strings (ADR-0010). */
public sealed interface ParamValue permits Text, Table {}
