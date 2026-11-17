package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/** The identity: its one result is its input, and the sink's answer is the run's. */
public final class IdentityOp extends Op {
  /** The identity in the run form {@code valueForm}. */
  public IdentityOp(boolean valueForm) {
    super(valueForm);
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(in);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return in;
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.emit(out, under, in);
  }
}
