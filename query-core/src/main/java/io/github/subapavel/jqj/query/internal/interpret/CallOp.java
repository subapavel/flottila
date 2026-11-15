package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A call of a lifted function (ADR-0045 #107): a fresh frame of the function's cells, its value parameters read on the
 * Call's input and its closure cells after its slots; then one step, then one more live activation, and the body runs
 * on the input in that frame. The activation ends however the body ends: its results running out, a stop, a break,
 * an error or a termination.
 */
public final class CallOp extends Op {
  private final LiftedFunction fn;
  private final ClosureArgument[] closures;
  private final Argument[] values;

  public CallOp(LiftedFunction fn, ClosureArgument[] closures, Argument[] values) {
    this.fn = fn;
    this.closures = closures;
    this.values = values;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] frame = new Object[fn.cells()];
    for (int k = 0; k < values.length; k++) {
      frame[k] = values[k].read(slots, in);
    }
    int base = fn.slots();
    for (int j = 0; j < closures.length; j++) {
      frame[base + j] = closures[j].bind(slots);
    }
    state.tick();
    state.enter();
    try {
      return fn.body().run(state, frame, in, out);
    } finally {
      state.leave();
    }
  }
}
