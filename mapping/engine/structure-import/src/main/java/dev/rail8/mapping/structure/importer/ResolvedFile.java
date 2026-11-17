package dev.rail8.mapping.structure.importer;

/**
 * A file a {@link ReferenceResolver} has read.
 *
 * @param name the file as the schema bundle spells it; the engine's load errors name the reference
 *     it asked for, never this name
 * @param content the file's bytes
 */
public record ResolvedFile(String name, byte[] content) {}
