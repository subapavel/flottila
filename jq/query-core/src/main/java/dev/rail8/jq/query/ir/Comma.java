package dev.rail8.jq.query.ir;

/**
 * {@code left, right}: the results of {@code left}, then those of {@code right}, both on the Comma's input.
 * Entering {@code right} is a step; a stop on the left returns without it.
 */
public record Comma(Source source, Node left, Node right) implements Node {}
