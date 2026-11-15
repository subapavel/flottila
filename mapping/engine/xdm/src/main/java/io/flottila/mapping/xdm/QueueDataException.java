package io.flottila.mapping.xdm;

/**
 * A context function's data error (ADR-0025 since #52): its inputs, as the run's data gave them,
 * meet one of the function's error conditions, such as unequal counts. The runtime's binding
 * rethrows it with the function's one code at its box. A broken block invariant is a plain {@link
 * IllegalArgumentException}, never this.
 */
public final class QueueDataException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message which count or value failed, naming the function
   */
  QueueDataException(String message) {
    super(message);
  }
}
