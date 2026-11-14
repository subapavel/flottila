package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
import io.github.subapavel.jqj.query.run.Fn0;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** A call of an {@link Fn0}: its result goes to the sink, whose answer is the run's; {@link CallForm#NONE} is none. */
public final class IntrinsicOp extends Op {
  private final Fn0 fn;

  public IntrinsicOp(Fn0 fn) {
    this.fn = fn;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object v = fn.apply(state, in);
    return v == CallForm.NONE || out.emit(v);
  }
}
