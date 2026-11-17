package io.github.subapavel.jqj.query.run;

/**
 * Every error a filter raises (ADR-0008): it carries the error value and is created without a stack trace. Its wrap
 * depth (ADR-0039, GLOSSARY "Wrap depth") counts the Trys whose sink it was thrown from and whose run it has not left
 * yet: a Try runs its handler only on an error of depth 0, so a downstream error passes it. A new error has depth 0, and
 * so does every error that leaves a whole run, since each Try that wrapped it unwrapped it on its way out.
 */
public final class FilterException extends EvaluationException {
  private static final long serialVersionUID = 1L;

  // A value need not be Serializable: serializing the exception fails instead of dropping it, as a transient would.
  @SuppressWarnings("serial")
  private final Object value;

  /** The Trys this error passed through a sink of and has not left the run of yet. */
  private int depth;

  public FilterException(Object value) {
    super(null);
    this.value = value;
  }

  /** The error value. */
  public Object value() {
    return value;
  }

  /** The wrap depth: 0 for an error a Try may catch. */
  public int depth() {
    return depth;
  }

  /** Marks the error as a Try's downstream error: thrown from that Try's sink, on its way back through the body. */
  public void wrap() {
    depth++;
  }

  /** Takes off the mark of the Try whose run the error now leaves. */
  public void unwrap() {
    depth--;
  }
}
