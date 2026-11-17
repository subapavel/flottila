package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A constant: its one result is its value, whatever the input, and the sink's answer is the run's. It is no step site
 * (ADR-0008), so it charges no tick (ADR-0031).
 */
public final class ConstOp extends Op {
  private final Object value;

  public ConstOp(boolean valueForm, Object value) {
    super(valueForm);
    this.value = value;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(value);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return value;
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.emit(out, under, value);
  }
}
