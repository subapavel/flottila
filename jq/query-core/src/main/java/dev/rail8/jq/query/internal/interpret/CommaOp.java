package dev.rail8.jq.query.internal.interpret;

import dev.rail8.jq.query.run.CallForm;
import dev.rail8.jq.query.run.RunState;
import dev.rail8.jq.query.run.Sink;

/**
 * {@code left, right}: the left side's results, then one step for entering the right side, whatever the left gave,
 * then the right side's results. A stop on the left returns at once, without the step.
 */
public final class CommaOp extends Op {
  private final Op left;
  private final Op right;

  public CommaOp(boolean valueForm, Op left, Op right) {
    super(valueForm);
    this.left = left;
    this.right = right;
  }

  /** Each side in its run form, a value-form side's value emitted here. */
  @Override
  public boolean run(RunState state, Object[] slots, Object in, Sink out) {
    if (left.valueForm) {
      Object v = left.eval(state, slots, in);
      if (v != CallForm.NONE && !out.emit(v)) {
        return false;
      }
    } else if (!left.run(state, slots, in, out)) {
      return false;
    }
    state.tick();
    if (!right.valueForm) {
      return right.run(state, slots, in, out);
    }
    Object w = right.eval(state, slots, in);
    return w == CallForm.NONE || out.emit(w);
  }

  /**
   * A value-form Comma's left side gives no result, or the Comma would be many: it runs, for its ticks and writes,
   * then the step and the right side's value.
   *
   * @throws IllegalStateException when the left side gives a result: the facts that made the Comma value form are
   *     wrong, and returning would lose a result
   */
  @Override
  public Object eval(RunState state, Object[] slots, Object in) {
    if (left.eval(state, slots, in) != CallForm.NONE) {
      throw new IllegalStateException("A value-form Comma's left side gave a result");
    }
    state.tick();
    return right.eval(state, slots, in);
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
