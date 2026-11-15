package io.flottila.mapping.mapping;

/** The result of lowering a mapping model: the IR, or the stage-3 diagnostics that refuse it. */
public sealed interface LowerResult permits Lowered, Refused {}
