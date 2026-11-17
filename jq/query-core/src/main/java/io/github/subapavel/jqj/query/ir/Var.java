package io.github.subapavel.jqj.query.ir;

/**
 * The value its {@link Bind} or {@link AltBind} set in {@code slot}, or the run gave a parameter slot (ADR-0034,
 * ADR-0038): one result, whatever the input.
 */
public record Var(Source source, int slot) implements Node {}
