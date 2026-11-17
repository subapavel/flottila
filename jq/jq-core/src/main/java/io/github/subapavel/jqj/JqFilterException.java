package io.github.subapavel.jqj;

import io.github.subapavel.jqj.value.JqValue;

/**
 * jq's catchable error, ending an evaluation that did not catch it (ADR-0016). It carries jq's error
 * value; the text a host prints is made from that value, so the exception has no message of its own.
 */
public final class JqFilterException extends JqException {
  private static final long serialVersionUID = 1L;

  // A JqValue is not Serializable (a view lives on its Workspace's tape): serializing the exception
  // fails instead of dropping the value, as a transient field would.
  @SuppressWarnings("serial")
  private final JqValue value;

  public JqFilterException(JqValue value) {
    super(null);
    this.value = value;
  }

  /** jq's error value. */
  public JqValue value() {
    return value;
  }
}
