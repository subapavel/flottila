package io.flottila.mapping.mapping;

/**
 * A mapping model that passed validation stages 1 and 2 (ADR-0011).
 *
 * @param model the model
 */
public record Loaded(MappingModel model) implements LoadResult {}
