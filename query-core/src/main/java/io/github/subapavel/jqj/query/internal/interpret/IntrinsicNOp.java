package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.FnN;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A call of an {@link FnN} on its arguments, read into an array that is new for each call, so the operation may keep
 * it (ADR-0034 amendment): its result goes to the sink; {@link CallForm#NONE} is none.
 */
public final class IntrinsicNOp extends Op {
  private final FnN fn;
  private final Argument[] args;

  public IntrinsicNOp(FnN fn, Argument[] args) {
    this.fn = fn;
    this.args = args;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] values = new Object[args.length];
    for (int i = 0; i < values.length; i++) {
      values[i] = args[i].read(slots, in);
    }
    Object v = fn.apply(state, in, values);
    return v == CallForm.NONE || out.emit(v);
  }
}
