package io.github.subapavel.jqj.query.ir;

/** The identity: one result, its input. */
public record Identity(Source source) implements Node {}
