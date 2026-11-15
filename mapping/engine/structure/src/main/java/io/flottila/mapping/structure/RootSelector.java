package io.flottila.mapping.structure;

/** The root of a structure in its schema bundle (ADR-0009, ADR-0010). */
public sealed interface RootSelector permits ElementRoot, WsdlRoot, JsonRoot {}
