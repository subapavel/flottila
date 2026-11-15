package io.flottila.mapping.mapping;

/** The result of loading a mapping model: the model, or the diagnostics that refuse it. */
public sealed interface LoadResult permits Loaded, Refused {}
