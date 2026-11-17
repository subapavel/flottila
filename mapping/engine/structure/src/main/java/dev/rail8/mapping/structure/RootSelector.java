package dev.rail8.mapping.structure;

/** The root of a structure in its schema bundle. */
public sealed interface RootSelector permits ElementRoot, WsdlRoot, JsonRoot {}
