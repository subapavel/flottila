package dev.rail8.jq.internal.op;

import dev.rail8.jq.query.ir.Operation;
import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.Fn0;
import dev.rail8.jq.query.run.RunState;

/** jq's {@code empty}: no result, whatever the input. */
public final class Empty implements Fn0 {
  /** {@code empty}. */
  public static final Operation OP = new Operation("empty", new Empty());

  private Empty() {}

  @Override
  public Object apply(RunState state, Object in) {
    return CallForm.NONE;
  }
}
