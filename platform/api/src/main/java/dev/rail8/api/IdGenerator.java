package dev.rail8.api;

/**
 * Makes message ids. Handed to code through {@link Runtime}, so tests can supply predictable ids.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface IdGenerator {
    String next();
}
