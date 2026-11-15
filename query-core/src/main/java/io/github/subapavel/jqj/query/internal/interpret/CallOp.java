package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A call of a lifted function (ADR-0045 #107): a fresh frame of the function's cells, its value parameters read on the
 * Call's input and its closure cells after its slots; then one step, then one more live activation, and the body runs
 * on the input in that frame. The activation ends when the body's results run out, at a stop or a break, or when an
 * error leaves the body; in push form a termination ends it too. On the trampoline the live activation is an
 * {@link Active} form, which holds no stack units, and a termination ends the run with the activation still counted.
 */
public final class CallOp extends Op {
  private final LiftedFunction fn;
  private final ClosureArgument[] closures;
  private final Argument[] values;

  /** {@code w}, the stack units the push form holds while the activation lives (ADR-0046 #111 amendment). */
  private final long units;

  /** {@code T}, the units a starter holds over {@link #units}. */
  private final long starter;

  public CallOp(LiftedFunction fn, ClosureArgument[] closures, Argument[] values, long units, long starter) {
    this.fn = fn;
    this.closures = closures;
    this.values = values;
    this.units = units;
    this.starter = starter;
  }

  /**
   * The push form: after the step and the activation, the call holds its units, and when the units held reach the
   * run's unit limit it holds {@code T} more and starts the trampoline as a starter (ADR-0046 #111 amendment), which
   * runs the body; otherwise the body runs here.
   */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] frame = frame(slots, in);
    state.tick();
    state.enter();
    long held = units;
    boolean starts = state.addUnits(held);
    if (starts) {
      state.addUnits(starter);
      held += starter;
    }
    try {
      return starts ? Trampoline.run(state, fn.body(), frame, in, out) : fn.body().run(state, frame, in, out);
    } finally {
      state.removeUnits(held);
      state.leave();
    }
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    Object[] frame = frame(slots, in);
    t.state.tick();
    t.state.enter();
    t.start(fn.body(), new Active(under), frame, in, out);
  }

  /** The callee's frame: its value parameters read on {@code in}, then its closures, bound in the caller's frame. */
  private Object[] frame(Object[] slots, Object in) {
    Object[] frame = new Object[fn.cells()];
    for (int k = 0; k < values.length; k++) {
      frame[k] = values[k].read(slots, in);
    }
    int base = fn.slots();
    for (int j = 0; j < closures.length; j++) {
      frame[base + j] = closures[j].bind(slots);
    }
    return frame;
  }

  /**
   * The heap form of one live call activation, a Call's or a real closure's (ADR-0046): it leaves the activation when
   * the body's run answers or an error passes it, as the push form's {@code finally} does.
   */
  static final class Active extends HeapForm {
    Active(HeapForm under) {
      super(under);
    }

    @Override
    void resume(Trampoline t, boolean more) {
      t.state.leave();
      t.answer(under, more);
    }

    @Override
    void fail(Trampoline t, FilterException e) {
      t.state.leave();
      t.raise(under, e);
    }
  }
}
