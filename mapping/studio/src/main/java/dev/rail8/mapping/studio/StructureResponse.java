package dev.rail8.mapping.studio;

/**
 * The result of loading a structure: its graph and fingerprint, or the load error that refuses it.
 */
sealed interface StructureResponse permits StructureLoaded, StructureRefused {}
