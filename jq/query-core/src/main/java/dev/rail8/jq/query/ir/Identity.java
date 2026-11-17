package dev.rail8.jq.query.ir;

/** The identity: one result, its input. */
public record Identity(Source source) implements Node {}
