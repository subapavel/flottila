package io.github.subapavel.jqj.oracle.compile;

import io.github.subapavel.jqj.oracle.diff.Outcome;

/**
 * How one run of a case under one compile ended, as the compile-mode oracle judges it (ADR-0020 amendment for #60).
 * The oracle's tests make these from jqj's own exceptions; the oracle module does not read jq-core.
 */
public sealed interface Run {

  /** The run ended with an exit code, stdout and stderr: a result, an error or a halt. */
  record Ended(Outcome outcome) implements Run {}

  /**
   * The run ended as a budget, stack, cancel or stale-view termination, which leaves the case out.
   *
   * @param cause the termination's cause, and its budget for a budget termination
   */
  record Terminated(String cause) implements Run {}

  /** An exception came out of the run. */
  record Threw(RuntimeException exception) implements Run {}
}
