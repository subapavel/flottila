package dev.rail8.jq;

/**
 * Every exception the engine throws. Sealed and abstract, so a host's exhaustive {@code switch} over
 * the four subclasses needs no default. No subclass records a stack trace.
 */
public abstract sealed class JqException extends RuntimeException
    permits JqCompileException, JqSyntaxException, JqFilterException, JqTermination {
  private static final long serialVersionUID = 1L;

  JqException(String message) {
    super(message, null, false, false);
  }
}
