package dev.rail8.jq.query.ir;

/**
 * The value its {@link Bind} or {@link AltBind} set in {@code slot}, or the run gave a parameter slot: one result,
 * whatever the input.
 */
public record Var(Source source, int slot) implements Node {}
