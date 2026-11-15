package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A call of a lifted function (ADR-0045 #107): a fresh frame of the function's cells, its value parameters read on the
 * Call's input and its closure cells after its slots; then one step, then one more live activation, and the body runs
 * on the input in that frame. The activation ends however the body ends: its results running out, a stop, a break,
 * an error or a termination. On the trampoline the live activation is an {@link Active} form.
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
    Object[] frame = frame(slots, in);
    state.tick();
    state.enter();
    try {
      return fn.body().run(state, frame, in, out);
    } finally {
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
