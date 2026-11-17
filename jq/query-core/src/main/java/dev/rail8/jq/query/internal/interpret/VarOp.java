package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/** A slot's value (ADR-0034): its one result, whatever the input. */
public final class VarOp extends Op {
  private final int slot;

  public VarOp(boolean valueForm, int slot) {
    super(valueForm);
    this.slot = slot;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(slots[slot]);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return slots[slot];
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.emit(out, under, slots[slot]);
  }
}
