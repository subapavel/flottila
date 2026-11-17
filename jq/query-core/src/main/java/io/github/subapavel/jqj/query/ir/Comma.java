package io.github.subapavel.jqj.query.ir;

/**
 * {@code left, right} (ADR-0034): the results of {@code left}, then those of {@code right}, both on the Comma's input.
 * Entering {@code right} is a step (F-32); a stop on the left returns without it.
 */
public record Comma(Source source, Node left, Node right) implements Node {}
