package io.flottila.api;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An expression language over the envelope ({@code xpath} first), pluggable like a {@link StepFactory}.
 * The flow builder's built-in {@code route}, {@code filter}, {@code extractKey} and {@code validate} take
 * their conditions and values from it, so core composes the patterns and never parses a body itself.
 * Both methods compile at build time: a bad expression is an {@link IllegalArgumentException} there,
 * never on the first message.
 *
 * @since 0.1
 */
public interface Expressions {

    /** The language's name as flows refer to it, e.g. {@code xpath}. */
    String language();

    /** A condition over the envelope. */
    Predicate<Envelope> predicate(String expression, StepResources resources);

    /** A string value from the envelope; empty when the expression selects nothing. */
    Function<Envelope, Optional<String>> value(String expression, StepResources resources);
}
