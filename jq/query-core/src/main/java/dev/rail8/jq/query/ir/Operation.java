package dev.rail8.jq.query.ir;

import dev.rail8.jq.query.run.CallForm;

/**
 * An operation a format adds, called through an {@link Intrinsic}: its name and its call form. An Operation holds no
 * node, so Intrinsics may share one.
 */
public record Operation(String name, CallForm form) {}
