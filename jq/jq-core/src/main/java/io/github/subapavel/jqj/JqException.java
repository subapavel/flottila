package io.github.subapavel.jqj;

/**
 * Every exception jqj throws (ADR-0016). Sealed and abstract, so a host's exhaustive {@code switch} over
 * the four subclasses needs no default (ADR-0024). No subclass records a stack trace.
 */
public abstract sealed class JqException extends RuntimeException
    permits JqCompileException, JqSyntaxException, JqFilterException, JqTermination {
  private static final long serialVersionUID = 1L;

  JqException(String message) {
    super(message, null, false, false);
  }
}
