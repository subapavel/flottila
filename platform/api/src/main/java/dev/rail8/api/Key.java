package dev.rail8.api;

/**
 * The typed name of a prop, so a prop is read with the type it was written with, for example
 * {@code Key<Integer> SPLIT_INDEX = new Key<>("splitIndex")}.
 *
 * @since 0.1
 */
public record Key<T>(String name) {}
