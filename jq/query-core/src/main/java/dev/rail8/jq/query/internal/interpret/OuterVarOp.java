package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * A slot of a closure's defining frame, or of one further out: the value of cell {@code cell} of the frame
 * {@code level} links out from the running one, its one result whatever the input.
 */
public final class OuterVarOp extends Op {
  private final int level;
  private final int cell;

  public OuterVarOp(boolean valueForm, int level, int cell) {
    super(valueForm);
    this.level = level;
    this.cell = cell;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(Frames.out(slots, level)[cell]);
  }

  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    return Frames.out(slots, level)[cell];
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.emit(out, under, Frames.out(slots, level)[cell]);
  }
}
