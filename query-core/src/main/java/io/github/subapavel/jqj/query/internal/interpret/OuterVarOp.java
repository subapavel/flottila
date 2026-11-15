package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A slot of a closure's defining frame, or of one further out (ADR-0045 #107): the value of cell {@code cell} of the
 * frame {@code level} links out from the running one, its one result whatever the input.
 */
public final class OuterVarOp extends Op {
  private final int level;
  private final int cell;

  public OuterVarOp(int level, int cell) {
    this.level = level;
    this.cell = cell;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    return out.emit(Frames.out(slots, level)[cell]);
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.emit(out, under, Frames.out(slots, level)[cell]);
  }
}
