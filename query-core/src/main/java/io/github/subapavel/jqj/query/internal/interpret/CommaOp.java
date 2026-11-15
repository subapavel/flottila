package io.github.subapavel.jqj.query.internal.interpret;

import io.github.subapavel.jqj.query.run.RunState;
import io.github.subapavel.jqj.query.run.Sink;

/**
 * {@code left, right} (ADR-0034): the left side's results, then one step for entering the right side (F-32), whatever
 * the left gave, then the right side's results. A stop on the left returns at once, without the step.
 */
public final class CommaOp extends Op {
  private final Op left;
  private final Op right;

  public CommaOp(Op left, Op right) {
    this.left = left;
    this.right = right;
  }

  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    if (!left.run(state, slots, in, out)) {
      return false;
    }
    state.tick();
    return right.run(state, slots, in, out);
  }

  @Override
  void start(Trampoline t, HeapForm under, Object[] slots, Object in, HeapSink out) {
    t.start(left, new Left(under, slots, in, out), slots, in, out);
  }

  /** The heap form while the left side runs: what the right side then runs on. */
  private final class Left extends HeapForm {
    private final Object[] slots;
    private final Object in;
    private final HeapSink out;

    Left(HeapForm under, Object[] slots, Object in, HeapSink out) {
      super(under);
      this.slots = slots;
      this.in = in;
      this.out = out;
    }

    /** The left side ran out: one step, then the right side in its place; a false passes at once, without the step. */
    @Override
    void resume(Trampoline t, boolean more) {
      if (!more) {
        t.answer(under, false);
        return;
      }
      t.state.tick();
      t.start(right, under, slots, in, out);
    }
  }
}
