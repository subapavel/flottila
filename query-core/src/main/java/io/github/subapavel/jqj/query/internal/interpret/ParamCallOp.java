package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.FilterException;
import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * A call activation of a real closure (ADR-0045 #107): the closure in cell {@code cell} of the frame {@code level}
 * links out from the running one, a closure cell of the function's frame, runs on the input in the scope it was written
 * in. It ticks one step, then adds one to the activation count until the closure's run ends, as a {@link CallOp}'s
 * activation does: on the trampoline a termination ends the run with the activation still counted.
 */
public final class ParamCallOp extends Op {
  private final int level;
  private final int cell;

  /** The stack units of the site, its descent and rise; the closure it runs adds its leaf-emit depth (ADR-0046 #111). */
  private final long units;

  /** {@code T}, the units a starter holds over the call's. */
  private final long starter;

  public ParamCallOp(int level, int cell, long units, long starter) {
    super(false);
    this.level = level;
    this.cell = cell;
    this.units = units;
    this.starter = starter;
  }

  /**
   * The push form: after the step and the activation, the call holds the site's units and the closure's leaf-emit
   * depth, and when the units held reach the run's unit limit it holds {@code T} more and starts the trampoline as a
   * starter, which runs the closure's body (ADR-0046 #111 amendment); otherwise the closure runs here.
   */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    Closure closure = (Closure) Frames.out(slots, level)[cell];
    state.tick();
    state.enter();
    long held = units + closure.emitDepth();
    boolean starts = state.addUnits(held);
    if (starts) {
      state.addUnits(starter);
      held += starter;
    }
    try {
      return starts ? closure.drive(state, in, out) : closure.run(state, in, out);
    } finally {
      state.removeUnits(held);
      state.leave();
    }
  }

  /** Never called: a ParamCall is never eligible for the value form (ADR-0047 #116). */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    throw new IllegalStateException("A ParamCall has no value form");
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    Closure closure = (Closure) Frames.out(slots, level)[cell];
    t.state.tick();
    t.state.enter();
    closure.start(t, new Active(under), in, out);
  }

  /**
   * The heap form of one live closure activation (ADR-0046): it leaves the activation when the closure's run answers or
   * an error passes it, as the push form's {@code finally} does. A closure's body holds no tail call (ADR-0047 #118), so
   * it takes no jump.
   */
  private static final class Active extends HeapForm {
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
