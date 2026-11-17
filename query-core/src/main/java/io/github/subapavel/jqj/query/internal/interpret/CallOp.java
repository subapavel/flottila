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
 *
 * <p>Each body entry is a loop (ADR-0047 #118): when the body returns with a {@link JumpOp}'s jump pending from the
 * activation's frame, the loop takes it, clearing it, and runs the callee's body in the callee's frame in the same
 * activation, with the same units, until a body returns with no jump of its own pending.
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
   * activation. Each jump the body leaves pending runs the next body the same way.
   */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Object[] frame = fn.frame(values, closures, slots, in);
    boolean starts = enter(state);
    long held = starts ? units + starter : units;
    try {
      Op body = fn.body();
      Object input = in;
      while (true) {
        // The body runs here, not in a helper: one Java frame per level, as #111's bytes per unit were measured with.
        boolean more;
        if (starts) {
          more = Trampoline.run(state, body, frame, input, out);
        } else if (!body.valueForm) {
          more = body.run(state, frame, input, out);
        } else {
          Object v = body.eval(state, frame, input);
          more = v == CallForm.NONE || out.emit(v);
        }
        if (state.jumpFrame() != frame) {
          return more;
        }
        JumpOp jump = (JumpOp) state.jumpTarget();
        input = state.jumpInput();
        state.clearJump();
        frame = jump.frame(frame, input);
        body = jump.fn().body();
      }
    } finally {
      state.removeUnits(held);
      state.leave();
    }
  }

  /**
   * The value form (ADR-0047 #116): the body entry of {@link #run}, then the body's value, or at the unit limit the
   * first result the trampoline hands back, which the starter answers with a stop. The callee of a value-form call is
   * value form, at most one and never breaking out, so that stop loses nothing and a true from the trampoline is no
   * result. The activation and the units end when this returns (ADR-0046 #116), before the caller runs the value on. A
   * jump is taken as in {@link #run}: the jump's own value is NONE, and the next body gives the call's. Eval has no
   * sink, so nothing downstream runs under a value-form call. A left side that runs on after its result is MANY
   * (F-56), so no tail position follows it. The frame check here therefore never sees a jump pending from another
   * activation today; it stays as written (ADR-0047 #118, ruling J5).
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    Object[] frame = fn.frame(values, closures, slots, in);
    boolean starts = enter(state);
    long held = starts ? units + starter : units;
    try {
      Op body = fn.body();
      Object input = in;
      while (true) {
        Object v;
        if (starts) {
          Holder first = new Holder();
          v = Trampoline.run(state, body, frame, input, first) ? CallForm.NONE : first.take();
        } else {
          v = body.eval(state, frame, input);
        }
        if (state.jumpFrame() != frame) {
          return v;
        }
        JumpOp jump = (JumpOp) state.jumpTarget();
        input = state.jumpInput();
        state.clearJump();
        frame = jump.frame(frame, input);
        body = jump.fn().body();
      }
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
    Object[] frame = fn.frame(values, closures, slots, in);
    t.state.tick();
    t.state.enter();
    t.start(fn.body(), new Active(under, frame, out), frame, in, out);
  }

  /**
   * The heap form of one live call activation (ADR-0046): when the body's run answers with a jump pending from the
   * activation's frame, it takes the jump and starts the callee's body under itself, in the same activation (ADR-0047
   * #118); otherwise it leaves the activation, as the push form's {@code finally} does, and so it does when an error
   * passes it.
   */
  static final class Active extends HeapForm {
    /** Where the body's results go. */
    private final HeapSink out;

    /** The frame the running body runs in: the call's, then each jump's callee's. */
    private Object[] frame;

    Active(HeapForm under, Object[] frame, HeapSink out) {
      super(under);
      this.frame = frame;
      this.out = out;
    }

    @Override
    void resume(Trampoline t, boolean more) {
      RunState state = t.state;
      if (state.jumpFrame() == frame) {
        JumpOp jump = (JumpOp) state.jumpTarget();
        Object in = state.jumpInput();
        state.clearJump();
        frame = jump.frame(frame, in);
        t.start(jump.fn().body(), this, frame, in, out);
        return;
      }
      state.leave();
      t.answer(under, more);
    }

    @Override
    void fail(Trampoline t, FilterException e) {
      t.state.leave();
      t.raise(under, e);
    }
  }
}
