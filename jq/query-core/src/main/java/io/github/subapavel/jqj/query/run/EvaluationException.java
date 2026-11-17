package io.github.subapavel.jqj.query.run;

/**
 * The two exceptions that end an evaluation (ADR-0008): every error a filter raises, which a {@code try} may catch, and
 * a termination, which nothing catches. Both are created without a stack trace.
 */
public abstract sealed class EvaluationException extends RuntimeException permits FilterException, Termination {
  private static final long serialVersionUID = 1L;

  EvaluationException(String message) {
    super(message, null, false, false);
  }
}
