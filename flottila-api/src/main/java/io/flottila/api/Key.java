package io.flottila.api;

/**
 * Typed property key — no magic strings. {@code Key<Integer> SPLIT_INDEX = new Key<>("splitIndex")}
 *
 * @since 0.1
 */
public record Key<T>(String name) {}
