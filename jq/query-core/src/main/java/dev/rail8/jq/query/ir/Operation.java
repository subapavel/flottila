package dev.rail8.jq.query.ir;

import dev.rail8.jq.query.run.CallForm;

/**
 * An operation a format adds, called through an {@link Intrinsic}: its name, its call form and its facts. An Operation
 * holds no node, so Intrinsics may share one.
 */
public record Operation(String name, CallForm form, Facts facts) {
  /** An operation with {@link Facts#UNTRACKED} facts. */
  public Operation(String name, CallForm form) {
    this(name, form, Facts.UNTRACKED);
  }
}
