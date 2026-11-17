package io.github.subapavel.jqj.query.ir;

import io.github.subapavel.jqj.query.run.Collector;

/**
 * Gathers the results of {@code body}, run on the Collect's input, and emits the one value {@code collector} makes of
 * them (ADR-0034 amendment). It charges no step of its own: every result after the first has already paid one.
 */
public record Collect(Source source, Node body, Collector collector) implements Node {}
