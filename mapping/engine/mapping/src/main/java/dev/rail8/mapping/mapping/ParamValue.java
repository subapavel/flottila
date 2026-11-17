package dev.rail8.mapping.mapping;

/** The value of a box parameter: a string or a table of strings. */
public sealed interface ParamValue permits Text, Table {}
