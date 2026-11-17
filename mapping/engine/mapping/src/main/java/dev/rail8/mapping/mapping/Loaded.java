package dev.rail8.mapping.mapping;

/**
 * A mapping model that passed validation stages 1 and 2.
 *
 * @param model the model
 */
public record Loaded(MappingModel model) implements LoadResult {}
