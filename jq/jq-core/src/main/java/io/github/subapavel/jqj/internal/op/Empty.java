package io.github.subapavel.jqj.internal.op;

import io.github.subapavel.jqj.query.ir.Operation;
import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;

/** jq's {@code empty}: no result, whatever the input (jq-ref 1.8.2, recorded: construct/object-empty-value). */
public final class Empty implements Fn0 {
  /** {@code empty}. */
  public static final Operation OP = new Operation("empty", new Empty());

  private Empty() {}

  @Override
  public Object apply(RunState state, Object in) {
    return CallForm.NONE;
  }
}
