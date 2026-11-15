package io.flottila.mapping.mapping;

import java.util.Optional;

/**
 * The box that reads one source field's queue in one context ({@code kind: source}).
 *
 * @param field the source field's field path
 * @param context the context field's path, a proper ancestor of {@code field}; empty for the
 *     field's parent, the default
 */
public record SourceFieldBox(String field, Optional<String> context) implements Box {}
