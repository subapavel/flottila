package dev.rail8.jq.query.ir;

import dev.rail8.jq.query.run.CallForm;

/**
 * An operation a format adds (ADR-0005), called through an {@link Intrinsic}: its name and its call form (ADR-0009,
 * ADR-0034). An Operation holds no node, so Intrinsics may share one.
 */
public record Operation(String name, CallForm form) {}
