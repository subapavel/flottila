package io.flottila.mapping.mapping;

/** Where a diagnostic points (ADR-0015). */
public sealed interface Location permits AtPointer, AtAuthoringId, AtFilePosition {}
