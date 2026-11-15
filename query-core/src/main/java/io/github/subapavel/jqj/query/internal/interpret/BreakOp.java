package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code break $name} (ADR-0008, ADR-0039, ADR-0045 #107): marks the token its Label's run put in the cell, or that a
 * call passed as a value parameter, and returns false, with no result and no step. The cell is in the frame {@code
 * level} links out from the running one.
 */
public final class BreakOp extends Op {
  private final int level;
  private final int slot;

  /** The Break of cell {@code slot} of the running frame. */
  public BreakOp(int slot) {
    this(0, slot);
  }

  /** The Break of cell {@code slot} of the frame {@code level} links out from the running one. */
  public BreakOp(int level, int slot) {
    this.level = level;
    this.slot = slot;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    state.breakTo((Long) Frames.out(slots, level)[slot]);
    return false;
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.state.breakTo((Long) Frames.out(slots, level)[slot]);
    t.answer(under, false);
  }
}
