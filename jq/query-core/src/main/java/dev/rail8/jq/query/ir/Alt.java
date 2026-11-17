package dev.rail8.jq.query.ir;

import dev.rail8.jq.query.run.Truth;

/**
 * The results of {@code left} for which {@code truth} holds, as they come; only when there was none, the results of
 * {@code right}, both on the Alt's input. It catches nothing and charges no step of its own.
 */
public record Alt(Source source, Node left, Node right, Truth truth) implements Node {}
