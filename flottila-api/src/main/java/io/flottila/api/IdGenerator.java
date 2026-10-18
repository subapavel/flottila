package io.flottila.api;

/**
 * Message id generation, injected through {@link Runtime} (no static singletons).
 *
 * @since 0.1
 */
@FunctionalInterface
public interface IdGenerator {
    String next();
}
