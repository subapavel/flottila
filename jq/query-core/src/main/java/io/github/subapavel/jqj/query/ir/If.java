package io.github.subapavel.jqj.query.ir;

import io.github.subapavel.jqj.query.run.Truth;

/**
 * Each result of {@code cond}, run on the If's input, runs {@code then} when {@code truth} holds for it and
 * {@code otherwise} when it does not, each on the If's own input (ADR-0037). The condition is the outer loop. It charges
 * no step of its own: every result after the first has already paid one.
 */
public record If(Source source, Node cond, Node then, Node otherwise, Truth truth) implements Node {}
