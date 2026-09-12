package io.flottila.core;

/** Typed property key — no magic strings. {@code Key<Integer> SPLIT_INDEX = new Key<>("splitIndex")} */
public record Key<T>(String name) {}
