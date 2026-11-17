package io.github.subapavel.jqj.query.ir;

/**
 * {@code foreach SRC as P (init; update; extract)} (ADR-0041, GLOSSARY "Loop state", "State slot"): for each result r
 * of {@code init}, on the Foreach's input, the state slot {@code slot} is set to r and {@code each} runs on the same
 * input, and the Foreach emits each of its results. Its {@code each} is the pattern's Binds around the one
 * {@link Advance} of the slot, piped into the extract when there is one. The slot is bound by the Foreach, set by it and
 * by that Advance, and read by no {@link Var}. It ticks and charges nothing.
 */
public record Foreach(Source source, Node init, int slot, Node each) implements Node {}
