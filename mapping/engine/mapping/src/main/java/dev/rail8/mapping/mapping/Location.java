package dev.rail8.mapping.mapping;

/** Where a diagnostic points (ADR-0015). */
public sealed interface Location permits AtPointer, AtAuthoringId, AtFilePosition {}
