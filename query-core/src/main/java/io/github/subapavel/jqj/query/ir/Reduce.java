package io.github.subapavel.jqj.query.ir;

/**
 * {@code reduce SRC as P (init; update)} (ADR-0041, GLOSSARY "Loop state", "State slot"): for each result r of
 * {@code init}, on the Reduce's input, the state slot {@code slot} is set to r and {@code each} runs on the same input;
 * then the Reduce emits the slot's value and nulls the slot. Its {@code each} is the pattern's Binds around the one
 * {@link Advance} of the slot. The slot is bound by the Reduce, set by it and by that Advance, and read by no
 * {@link Var}. A false from {@code each}, a break, passes at once, with no emit. It ticks and charges nothing.
 */
public record Reduce(Source source, Node init, int slot, Node each) implements Node {}
