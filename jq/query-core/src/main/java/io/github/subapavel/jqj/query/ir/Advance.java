package io.github.subapavel.jqj.query.ir;

/**
 * The step of a {@link Reduce} or {@link Foreach} (ADR-0041, GLOSSARY "State slot"): it takes the value v of the state
 * slot {@code slot}, leaving null, runs {@code update} on v, and stores and emits each of its results. So every result
 * starts from v, the last one stays in the slot, and an update with no result leaves null. A loop has one Advance,
 * inside its {@code each}. It ticks and charges nothing.
 */
public record Advance(Source source, int slot, Node update) implements Node {}
