package io.github.subapavel.jqj.query.ir;

import io.github.subapavel.jqj.query.run.Truth;

/**
 * The results of {@code left} for which {@code truth} holds, as they come; only when there was none, the results of
 * {@code right}, both on the Alt's input (ADR-0037). It catches nothing (ADR-0008) and charges no step of its own.
 */
public record Alt(Source source, Node left, Node right, Truth truth) implements Node {}
