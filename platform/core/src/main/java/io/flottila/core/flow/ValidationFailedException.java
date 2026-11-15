package io.flottila.core.flow;

import io.flottila.api.Fault;

import java.util.Objects;

/**
 * A {@code validate} step's condition was false: the message fails at that step (a sync flow answers
 * the caller with it, DLQ for the others is the runtime's job). Never retried on its own. A {@link Fault.Kind#INVALID} fault.
 *
 * @since 0.1
 */
public final class ValidationFailedException extends RuntimeException implements Fault {

    private final String step;
    private final String expression;

    public ValidationFailedException(String step, String expression) {
        super("validation failed at step '" + step + "': " + expression);
        this.step = Objects.requireNonNull(step, "step");
        this.expression = Objects.requireNonNull(expression, "expression");
    }

    public String step() { return step; }

    public String expression() { return expression; }

    /** {@link Fault.Kind#INVALID}: the message broke the step's rule. */
    @Override
    public Kind kind() { return Kind.INVALID; }
}
