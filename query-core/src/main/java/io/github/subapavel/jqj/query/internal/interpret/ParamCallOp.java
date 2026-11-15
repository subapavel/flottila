package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A call activation of a real closure (ADR-0045 #107): the closure in cell {@code cell} of the frame {@code level}
 * links out from the running one, a closure cell of the function's frame, runs on the input in the scope it was written
 * in. It ticks one step, then adds one to the activation count until it ends, however it ends.
 */
public final class ParamCallOp extends Op {
  private final int level;
  private final int cell;

  public ParamCallOp(int level, int cell) {
    this.level = level;
    this.cell = cell;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Closure closure = (Closure) Frames.out(slots, level)[cell];
    state.tick();
    state.enter();
    try {
      return closure.run(state, in, out);
    } finally {
      state.leave();
    }
  }
}
