package dev.rail8.mapping.mapping;

/** Where a diagnostic points. */
public sealed interface Location permits AtPointer, AtAuthoringId, AtFilePosition {}
