package io.flottila.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An expression language over the envelope ({@code xpath} first), pluggable like a {@link StepFactory}.
 * The flow builder's built-in {@code route}, {@code filter}, {@code extractKey}, {@code validate} and {@code split} take
 * their conditions, values and parts from it, so core composes the patterns and never parses a body itself.
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

    /**
     * The parts a {@code split} step cuts the body into, each stored by the language as a part of the parent (keyed by the
     * parent's id and the split's step) and handed back as a {@link Body}; a language that cannot split throws
     * {@link UnsupportedOperationException} at build time.
     */
    default Function<Envelope, List<Body>> parts(String expression, StepResources resources) {
        throw new UnsupportedOperationException("the " + language() + " language cannot split a body into parts");
    }

    /** What evaluating an expression needs of the body; the whole body unless the language says otherwise. */
    default BodyKind bodyKind() { return BodyKind.WHOLE; }
}
