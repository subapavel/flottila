package dev.rail8.api.settings;

/**
 * Which way an endpoint carries messages: {@code in}, a consumer that takes them from a partner, or {@code out}, a producer
 * that sends them to one. The endpoint file's {@code direction} names it; a field of a description serves one or both.
 *
 * @since 0.1
 */
public enum Direction {
    IN, OUT
}
