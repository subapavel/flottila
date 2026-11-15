package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.CallForm;
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

  public CallOp(boolean valueForm, LiftedFunction fn, ClosureArgument[] closures, Argument[] values, long units, long starter) {
    super(valueForm);
    this.fn = fn;
    this.closures = closures;
    this.values = values;
    this.units = units;
    this.starter = starter;
  }

  /**
   * The push form: after the step and the activation, the call holds its units, and when the units held reach the
   * run's unit limit it holds {@code T} more and starts the trampoline as a starter (ADR-0046 #111 amendment), which
   * runs the body; otherwise the body runs here in its run form, a value-form body's value emitted here, inside the
   * activation.
   */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] frame = frame(slots, in);
    boolean starts = enter(state);
    long held = starts ? units + starter : units;
    try {
      if (starts) {
        return Trampoline.run(state, fn.body(), frame, in, out);
      }
      Op body = fn.body();
      if (!body.valueForm) {
        return body.run(state, frame, in, out);
      }
      Object v = body.eval(state, frame, in);
      return v == CallForm.NONE || out.emit(v);
    } finally {
      state.removeUnits(held);
      state.leave();
    }
  }

  /**
   * The value form (ADR-0047 #116): the body entry of {@link #run}, then the body's value, or at the unit limit the
   * first result the trampoline hands back, which the starter answers with a stop. The callee of a value-form call is
   * value form, at most one and never breaking out, so that stop loses nothing and a true from the trampoline is no
   * result. The activation and the units end when this returns (ADR-0046 #116), before the caller runs the value on.
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    Object[] frame = frame(slots, in);
    boolean starts = enter(state);
    long held = starts ? units + starter : units;
    try {
      if (starts) {
        Holder first = new Holder();
        return Trampoline.run(state, fn.body(), frame, in, first) ? CallForm.NONE : first.take();
      }
      return fn.body().eval(state, frame, in);
    } finally {
      state.removeUnits(held);
      state.leave();
    }
  }

  /**
   * The body entry both forms share: one step, one more live activation, then the call's units held. True when the
   * units held reach the run's unit limit: the call is a starter and holds {@code T} more (ADR-0046 #111 amendment).
   */
  private boolean enter(RunState state) {
    state.tick();
    state.enter();
    if (!state.addUnits(units)) {
      return false;
    }
    state.addUnits(starter);
    return true;
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
